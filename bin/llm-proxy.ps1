<#
.SYNOPSIS
    Runs the LLM proxy in the background on Windows, without admin rights.

.DESCRIPTION
    Usage: powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 start|stop|restart|status|logs

    Everything lives in the project folder: run\llm-proxy.pid, logs\llm-proxy.log and
    logs\llm-proxy.err.log (both overwritten at each start). Variables from .env (project root)
    are loaded when the file exists. Overridable with environment variables:
      JAVA              java executable (default: $env:JAVA_HOME\bin\java.exe, then java)
      JAVA_OPTS         extra JVM options, e.g. "-Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080"
      LLM_PROXY_JAR     jar to run (default: target\llm-proxy.jar)
      LLM_PROXY_CONFIG  configuration file (default: config.json)
      LLM_PROXY_PORT    port used by "status" for the health check (default: 4000)
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs')]
    [string] $Command = 'status'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# Load .env into this process; the proxy inherits it.
$EnvFile = Join-Path $Root '.env'
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) { continue }
        $name, $value = $trimmed.Split('=', 2)
        $value = $value.Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($name.Trim(), $value, 'Process')
    }
}

function Get-Setting([string] $Name, [string] $Default) {
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

$Java = Get-Setting 'JAVA' ''
if ($Java -eq '') {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $Java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $Java = 'java'
    }
}
$Jar = Get-Setting 'LLM_PROXY_JAR' 'target\llm-proxy.jar'
$Config = Get-Setting 'LLM_PROXY_CONFIG' 'config.json'
$Port = Get-Setting 'LLM_PROXY_PORT' '4000'
$JavaOpts = Get-Setting 'JAVA_OPTS' ''
$PidFile = Join-Path $Root 'run\llm-proxy.pid'
$LogFile = Join-Path $Root 'logs\llm-proxy.log'
$ErrFile = Join-Path $Root 'logs\llm-proxy.err.log'

function Get-RunningProcess {
    if (-not (Test-Path $PidFile)) { return $null }
    $processId = (Get-Content $PidFile -Raw).Trim()
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -eq $process) { Remove-Item $PidFile -Force; return $null }
    return $process
}

function Start-Proxy {
    $running = Get-RunningProcess
    if ($running) { Write-Host "LLM proxy already running (PID $($running.Id))."; return }
    if (-not (Test-Path $Jar)) { throw "Jar not found: $Jar (run: mvn clean package)" }
    if (-not (Test-Path $Config)) { throw "Configuration not found: $Config (copy config.example.json)" }
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'run'), (Join-Path $Root 'logs') | Out-Null

    $arguments = @()
    if ($JavaOpts -ne '') { $arguments += $JavaOpts.Split(' ', [StringSplitOptions]::RemoveEmptyEntries) }
    $arguments += @('-jar', "`"$Jar`"", "`"$Config`"")
    $process = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $Root `
        -WindowStyle Hidden -RedirectStandardOutput $LogFile -RedirectStandardError $ErrFile -PassThru
    Set-Content -Path $PidFile -Value $process.Id
    Start-Sleep -Seconds 2
    if (Get-RunningProcess) {
        Write-Host "LLM proxy started (PID $($process.Id)), logs: $LogFile"
    } else {
        Write-Host 'LLM proxy failed to start, last log lines:'
        Get-Content $LogFile, $ErrFile -Tail 20 -ErrorAction SilentlyContinue
        exit 1
    }
}

function Stop-Proxy {
    $running = Get-RunningProcess
    if (-not $running) { Write-Host 'LLM proxy is not running.'; return }
    # Windows has no SIGTERM for a hidden console process: this ends it immediately.
    Stop-Process -Id $running.Id -Force
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host 'LLM proxy stopped.'
}

function Show-Status {
    $running = Get-RunningProcess
    if (-not $running) { Write-Host 'LLM proxy is not running.'; exit 3 }
    Write-Host "LLM proxy running (PID $($running.Id))."
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 5
        Write-Host "Health: $($health.status)"
    } catch {
        Write-Host "Health check on port $Port failed."
    }
}

switch ($Command) {
    'start' { Start-Proxy }
    'stop' { Stop-Proxy }
    'restart' { Stop-Proxy; Start-Proxy }
    'status' { Show-Status }
    'logs' { Get-Content $LogFile -Tail 50 -Wait }
}
