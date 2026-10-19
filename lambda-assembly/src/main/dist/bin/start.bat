@echo off
rem Démarre le serveur Quarkus (port 8080 par défaut, modifiable : -Dquarkus.http.port=9090)
cd /d "%~dp0.."
java %JAVA_OPTS% -jar quarkus-run.jar %*
