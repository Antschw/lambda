# LLM Proxy

A small OpenAI-compatible proxy for agentic CLI tools (Cline, pi, opencode, or any client that
speaks the OpenAI API). It spreads requests across several LLM providers and fails over
transparently when a provider answers `429 Too Many Requests`.

```
Cline / pi / opencode / ... ──► http://localhost:4000/v1   (one provider for the tool)
                                         │
                                    llm-proxy  ── model discovery, round-robin, cooldowns, retries
                        ┌────────────────┼────────────────┐
                    provider A        provider B        provider C
                  (own key/URL)     (own key/URL)     (own key/URL)
```

- **Automatic model discovery**: the proxy reads every provider's `GET /models` at startup and
  every hour, and serves every model it finds. Pick any of them in your tool, as usual.
- **Per-model load spreading**: a model exposed by 3 providers is served by the 3 of them in
  turn, a model exposed by 2 by those 2, a model exposed by 1 always by that one.
- **Failover on 429 / 5xx / timeouts / network errors**: the request is replayed on the next
  provider before anything is sent to the client, so the tool never sees the 429.
- **Cooldowns**: a provider that returned 429 is skipped for the time it asks (`Retry-After`,
  `retry-after-ms`, `x-ratelimit-reset-*`), or with exponential backoff when it gives no hint.
- **Waits instead of failing** when every provider of a model is cooling down (bounded).
- **Optional proactive limit** per provider (`maxRequestsPerMinute`) to avoid 429s altogether.
- **Context limits** per provider or model: long requests skip providers that cannot hold them.
- **Silent truncation detection** and a **probe** command measuring a provider's real context.
- **Streaming (SSE)** passed through chunk by chunk, no buffering.
- **Virtual models** (optional): one name grouping models that are named differently on each
  provider.
- Works with any OpenAI-compatible client: nothing is tool-specific.
- Java 25, JDK HTTP server and client on virtual threads; only Jackson and SLF4J/Logback at runtime.

## Requirements

- JDK 25
- Maven 3.9+

No admin rights are needed, neither to build nor to run.

## Quick start

```bash
mvn clean package                      # runs the tests, produces target/llm-proxy.jar
cp config.example.json config.json     # put your providers' URLs in it
cp .env.example .env                   # put your providers' keys in it
bin/llm-proxy.sh start                 # runs in the background (Windows: see below)
bin/llm-proxy.sh status
curl http://localhost:4000/v1/models   # every discovered model and its providers
```

To run it in the foreground instead (logs in the terminal, Ctrl+C to stop):

```bash
set -a; . ./.env; set +a
java -jar target/llm-proxy.jar config.json
```

The configuration file is, in order: the first argument, the `LLM_PROXY_CONFIG` environment
variable, or `config.json` in the working directory.

Behind a corporate proxy, add the usual JVM options, e.g. in `.env`:
`JAVA_OPTS=-Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080`.

## Run in the background

`java -jar` cannot detach itself, so the `bin/` scripts do it. They only use the project folder
(`run/llm-proxy.pid`, `logs/`), load `.env` automatically, and need no admin rights.

### Linux, macOS, Git Bash

```bash
bin/llm-proxy.sh start      # start in the background (does nothing if already running)
bin/llm-proxy.sh status     # PID + /health
bin/llm-proxy.sh logs       # follow logs/llm-proxy.log
bin/llm-proxy.sh restart    # e.g. after changing config.json
bin/llm-proxy.sh stop       # graceful stop: in-flight requests are allowed to finish
bin/llm-proxy.sh probe api2 mistral-small   # measure a provider's real context (foreground)
```

### Windows (PowerShell)

```powershell
powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 start
powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 status
powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 logs
powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 stop
powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 probe api2 mistral-small max=131072
```

`-ExecutionPolicy Bypass` only applies to that command and needs no admin rights. The proxy
runs in a hidden window; logs go to `logs\llm-proxy.log` and `logs\llm-proxy.err.log`
(overwritten at each start). On Windows, `stop` ends the process immediately.

Both scripts accept these environment variables (or `.env` entries): `JAVA` (java executable,
default `JAVA_HOME`'s), `JAVA_OPTS`, `LLM_PROXY_JAR`, `LLM_PROXY_CONFIG`, `LLM_PROXY_PORT` (port
checked by `status`).

### Start automatically at login (optional, no admin)

- **Windows**: press `Win+R`, type `shell:startup`, and create a shortcut there with the target
  `powershell -WindowStyle Hidden -ExecutionPolicy Bypass -File C:\path\to\llm-proxy\bin\llm-proxy.ps1 start`.
- **Linux**: `crontab -e`, then add `@reboot /path/to/llm-proxy/bin/llm-proxy.sh start`
  (or use a `systemctl --user` service if your system has one).
- **macOS**: add the script to System Settings → General → Login Items, or use a user
  LaunchAgent in `~/Library/LaunchAgents`.

## Configuration

See [`config.example.json`](config.example.json). Every string value may reference environment
variables with `${NAME}` or `${NAME:-default}`: keep API keys in `.env` / the environment, never
in the file.

The minimal configuration lists the providers; models are discovered:

```json
{
  "localApiKeys": ["${LLM_PROXY_LOCAL_KEY:-}"],
  "upstreams": [
    { "name": "api1", "baseUrl": "https://api1.example/v1", "apiKey": "${API1_KEY}" },
    { "name": "api2", "baseUrl": "https://api2.example/v1", "apiKey": "${API2_KEY}" },
    { "name": "api3", "baseUrl": "https://api3.example/v1", "apiKey": "${API3_KEY}" }
  ]
}
```

| Key | Default | Description |
|-----|---------|-------------|
| `listen.host` | `127.0.0.1` | Bind address. Use `0.0.0.0` in a container. |
| `listen.port` | `4000` | Port. |
| `localApiKeys` | `[]` | Keys clients must send (`Authorization: Bearer` or `x-api-key`). Empty disables authentication; empty values after substitution are ignored. |
| `upstreams[].name` | — | Unique name, used in logs, `/v1/models` and virtual models. |
| `upstreams[].baseUrl` | — | Base URL **including** the version, e.g. `https://api.example.com/v1`. The client path after `/v1` is appended to it. |
| `upstreams[].apiKey` | — | Sent as `Authorization: Bearer <key>`. |
| `upstreams[].headers` | `{}` | Extra headers for this provider. |
| `upstreams[].discoverModels` | `true` | Read the provider's models from `GET {baseUrl}/models`. |
| `upstreams[].includeModels` | `[]` | Glob patterns (`*` = anything, case-insensitive) a discovered model must match; empty means all. |
| `upstreams[].excludeModels` | `[]` | Glob patterns of discovered models to ignore, e.g. `["*embed*", "*whisper*"]`. |
| `upstreams[].passthroughModels` | `[]` | Models always served by this provider, even if not discovered. |
| `upstreams[].maxRequestsPerMinute` | none | Proactive sliding-window limit for this provider. |
| `upstreams[].maxContextTokens` | none | Context size of this provider's models, in tokens. Requests estimated larger skip this provider. |
| `upstreams[].modelContextTokens` | `{}` | Context size by model glob pattern (first match wins), e.g. `{"*-128k": 128000}`; overrides `maxContextTokens`. |
| `models` | `{}` | Optional virtual models: name → list of `{ "upstream", "model" }` targets, see below. |
| `defaultModel` | none | Virtual model used when the request has no or an unknown `model`. |
| `context.charsPerToken` | `3.5` | Characters per token used to estimate request sizes. Lower is more cautious. |
| `context.truncationRatio` | `0.6` | A response whose `prompt_tokens` is below this share of the estimate is reported as a possible silent truncation. |
| `context.learnFromTruncation` | `false` | After a suspected truncation, use the size the provider reported as its limit (until restart), when no limit is configured. |
| `discovery.refreshInterval` | `1h` | How often model lists are read again; `0` reads them only at startup. |
| `discovery.timeout` | `10s` | Time allowed for a provider to answer `GET /models`. |
| `retry.maxAttempts` | `6` | Maximum provider calls per client request. |
| `retry.defaultCooldown` | `30s` | Cooldown when the provider gives no hint; doubles on each consecutive failure. |
| `retry.maxCooldown` | `5m` | Upper bound of any cooldown, hints included. |
| `retry.misconfiguredCooldown` | `10m` | Cooldown after `401`/`403` (bad key). |
| `retry.maxWaitWhenAllCooling` | `60s` | How long a request may wait when all providers of its model are cooling down; after that the client gets a `429` with `Retry-After`. |
| `timeouts.connect` | `10s` | Connection timeout to providers. |
| `timeouts.request` | `10m` | Time allowed until the provider's response headers arrive (streams themselves are not limited). |
| `timeouts.shutdown` | `10s` | Grace period for in-flight requests on shutdown. |

Durations accept `500ms`, `30s`, `5m`, `1h`, `1m30s`, a number of seconds, or ISO-8601 (`PT30S`).

### Model discovery

- At startup the proxy reads every provider's model list in parallel, then again every
  `discovery.refreshInterval`. New models appear and vanished ones disappear without restart;
  `bin/llm-proxy.sh restart` forces an immediate refresh.
- **Duplicate ids** in a provider's list (some providers list a model several times) are
  collapsed: the provider is used once per model, never over-weighted.
- If a provider's list cannot be read (down, bad key...), its **last known list is kept** and a
  warning is logged; at the very first start it only serves its `passthroughModels`.
- `GET /v1/models` shows every model with the providers serving it (`upstreams` field, ignored
  by clients).

### Virtual models (optional)

When the same model has a different id on each provider, group them under one name:

```json
"models": {
  "coder": [
    { "upstream": "api1", "model": "qwen3-coder" },
    { "upstream": "api2", "model": "Qwen/Qwen3-Coder" }
  ]
}
```

Clients ask for `coder`; the proxy sends each provider its own id. A virtual model wins over a
discovered model with the same name. The same provider can appear several times with different
models.

### Context limits and silent truncation

Agentic tools resend the whole conversation on every call: the tool's system prompt (with its
tool instructions), your task, and every action result so far. It grows quickly.

When a request is longer than the context a provider really deployed, some servers return an
error (`400 context_length_exceeded`), but others **silently drop the start of the prompt**
(typical of Ollama with a small `num_ctx`, or vLLM/TGI with a small `--max-model-len` and
truncation enabled). The first thing dropped is the system prompt and your task: the model only
sees the last tool results, believes it is done and answers "Task completed". A model can support
128k tokens while the provider deployed it with much less.

What the proxy does about it:

1. **Detects it.** For every request it logs the tokens it sent (estimate) and the tokens the
   provider says it processed (`usage.prompt_tokens`, read from the response or the last stream
   chunk without changing them):
   ```
   m1 -> api2/m1 attempt 1/6: 200 in 4.1s (first byte 1.2s), ~42.9k tokens sent, upstream counted 15.9k + 120 out, finish=stop
   WARN ... possible silent truncation: ~42.9k tokens sent but upstream counted 15.9k ...
   ```
   `/health` shows, per target, `truncationsSuspected` and `maxPromptTokensSeen`. A
   `finish=length` warning means the answer itself was cut by the output token limit.
2. **Measures it.** The probe command sends "needle in a haystack" conversations of growing size
   directly to one provider (a secret code at the start, filler, then "what was the code?") and
   finds the largest size the model still sees entirely:
   ```bash
   bin/llm-proxy.sh probe api2 mistral-small            # optional: --min 4000 --max 131072
   # Windows: powershell -ExecutionPolicy Bypass -File bin\llm-proxy.ps1 probe api2 mistral-small max=131072
   ```
   ```
     ~ 16,065 tokens sent  upstream counted 13,978  OK (code remembered)
     ~ 32,150 tokens sent  upstream counted 15,924  FORGOTTEN (start of the conversation was dropped)
     ...
   Works up to ~19,327 tokens, fails from ~20,228 tokens.
   Suggested setting for upstream 'api2': "maxContextTokens": 17000
   ```
   Each step is a real request (about 10 in total, some long): it uses the provider's quota.
3. **Routes around it.** With `maxContextTokens` (or `modelContextTokens`) set, a request whose
   estimate (prompt + requested `max_tokens`) exceeds a provider's limit skips that provider; it
   is still used for shorter requests. If no provider is large enough, the request goes to the
   one(s) with the largest limit, since the size is only an estimate. Limits advertised by a
   provider's `/models` (`context_length`, `max_model_len`...) are used when nothing is configured.
4. **Retries on explicit errors.** A `400`/`413` saying the context is too long makes the proxy
   try another provider for that request, without cooldown. Other `400`s are returned as is.

Sizes are estimates (no tokenizer): keep a margin, or lower `context.charsPerToken` (code and
non-English text use more tokens per character). The tool itself must also know the smaller
context to compact its history in time: give it the context of the provider(s) you want it to
fit in (the client templates use the largest known limit of each model).

### How provider statuses are handled

| Provider answer | Proxy behaviour |
|-----------------|-----------------|
| `2xx` / `3xx` | Streamed to the client; the target's failure counter is reset. |
| `429` | Target cooled down (provider hint or backoff), request replayed on the next target. |
| `408`, `500`, `502`, `503`, `504`, `520`–`599`, timeout, network error | Same as 429. |
| `401`, `403` | Logged as a configuration error; target set aside for `misconfiguredCooldown`; next target tried. |
| `404` | Next target tried for this request only (unknown model or endpoint on that provider), no cooldown. |
| `400`, `413` saying the context is too long | Next target tried for this request only, no cooldown. |
| Other `4xx` (e.g. `400`) | Returned as is: the request itself is wrong, retrying would not help. |

When every attempt fails, the client receives the last provider error, or a `429` with a
`Retry-After` header when all targets are rate limited.

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /v1/models`, `GET /v1/models/{id}` | Models served by the proxy (virtual first, then discovered, sorted), with their `upstreams` and, when every upstream's limit is known, the largest `context_length`. |
| `* /v1/...` | Forwarded with failover: `chat/completions`, `completions`, `embeddings`, `responses`... Any path works as long as the providers support it. The `model` field is forwarded unchanged (rewritten only for virtual models). |
| `GET /templates/opencode.json`, `GET /templates/pi.json` | Ready-to-paste client configurations listing every current model (see below). |
| `GET /health` | Health of every target (no authentication): availability, cooldown, counters, `contextLimit`, `maxPromptTokensSeen`, `truncationsSuspected`. Always `200`; `status` is `ok` or `degraded`. |

The `/v1` prefix is optional (`/chat/completions` works too), as tools differ on whether they add
it to the base URL. Every response carries an `X-LLM-Proxy-Target` header naming the target that
served it.

## Client setup

For every tool the settings are the same: an **OpenAI-compatible** provider with

- base URL: `http://localhost:4000/v1`
- API key: the value of `LLM_PROXY_LOCAL_KEY` (any non-empty value if authentication is disabled)
- model: any id from `curl http://localhost:4000/v1/models`

The templates below use the provider id `llm-proxy` and read the key from the
`LLM_PROXY_LOCAL_KEY` environment variable of the tool's shell. They were written against the
tools' documentation as of November 2026; if your version expects other field names, its own
documentation wins.

### Cline

Settings → API Provider: **OpenAI Compatible**

| Field | Value |
|-------|-------|
| Base URL | `http://localhost:4000/v1` |
| API Key | your `LLM_PROXY_LOCAL_KEY` |
| Model ID | a model id from `/v1/models`, e.g. `qwen3-coder` |

To switch model, change the Model ID (Cline's model field accepts any id; depending on the
version it may also offer the ids from `/v1/models`). Cline's own retry setting can stay on.

### pi

`~/.pi/agent/models.json` (or `$PI_CODING_AGENT_DIR/models.json`). Generate it with every
current model:

```bash
curl -s -H "Authorization: Bearer $LLM_PROXY_LOCAL_KEY" \
  http://localhost:4000/templates/pi.json > ~/.pi/agent/models.json
```

Merge it by hand if you already have custom providers in that file. The template looks like:

```json
{
  "$schema": "https://pi.dev/schemas/models.schema.json",
  "providers": {
    "llm-proxy": {
      "name": "LLM Proxy",
      "baseUrl": "http://localhost:4000/v1",
      "api": "openai-completions",
      "apiKey": "$LLM_PROXY_LOCAL_KEY",
      "models": [
        { "id": "qwen3-coder", "name": "qwen3-coder" },
        { "id": "gpt-oss-120b", "name": "gpt-oss-120b" }
      ]
    }
  }
}
```

`contextWindow` is filled in when the proxy knows the model's context (configured or advertised
limits). Other optional per-model fields: `maxTokens` (default 16384),
`reasoning`, `input`. Then select a `llm-proxy` model from pi's model selector.

### opencode

`opencode.json` in your project, or `~/.config/opencode/opencode.json` globally. Generate the
provider block with every current model:

```bash
curl -s -H "Authorization: Bearer $LLM_PROXY_LOCAL_KEY" \
  http://localhost:4000/templates/opencode.json
```

```json
{
  "$schema": "https://opencode.ai/config.json",
  "providers": {
    "llm-proxy": {
      "name": "LLM Proxy",
      "package": "@opencode/ai/providers/openai-compatible",
      "settings": {
        "baseURL": "http://localhost:4000/v1",
        "apiKey": "{env:LLM_PROXY_LOCAL_KEY}"
      },
      "models": {
        "qwen3-coder": { "name": "qwen3-coder" },
        "gpt-oss-120b": { "name": "gpt-oss-120b" }
      }
    }
  }
}
```

Merge the `llm-proxy` entry into your existing configuration, then pick a model with `/models`
or set `"model": "llm-proxy/qwen3-coder"`. `limit.context` is filled in when the proxy knows
the model's context; you may add `"output"` to it, e.g. `"limit": { "context": 128000, "output": 16384 }`.

Older opencode versions use `provider` / `npm: "@ai-sdk/openai-compatible"` / `options` instead:
`/templates/opencode.json?format=legacy` produces that format.

### Any other OpenAI-compatible tool

Anything that lets you set an OpenAI base URL works. Many tools and SDKs read:

```bash
export OPENAI_BASE_URL=http://localhost:4000/v1
export OPENAI_API_KEY=$LLM_PROXY_LOCAL_KEY
```

## Tips

- A conversation can be served by different providers from one turn to the next; this works
  because the whole history is resent each time.
- If a provider limits requests **per key**, all its models share one quota; if it limits **per
  model**, each model has its own.
- If you know a provider's quota, set `maxRequestsPerMinute` slightly below it.
- Exclude models your tools never use (embeddings, audio, images) to keep model lists short.
- `LOG_LEVEL=DEBUG` logs every incoming request. API keys and prompt contents are never logged.

## Docker

The proxy is container-ready: configuration from a mounted file plus environment variables, logs
on stdout, graceful shutdown on `SIGTERM`, `/health` for health checks.

```bash
docker build -t llm-proxy .
docker run --rm -p 4000:4000 \
  -v "$PWD/config.json:/config/config.json:ro" \
  --env-file .env -e LLM_PROXY_HOST=0.0.0.0 \
  llm-proxy
```

The image sets `LLM_PROXY_HOST=0.0.0.0`, which `config.example.json` reads through
`${LLM_PROXY_HOST:-127.0.0.1}`. The `.env` file overrides it, hence the explicit `-e` above.

## Project layout

```
bin/                        background start scripts (Bash, PowerShell)
src/main/java/fr/antschw/llmproxy/
  Main.java                 entry point
  config/                   configuration records, JSON loader, duration parser
  routing/                  target pool (round-robin, cooldowns, rate windows, context limits),
                            model discovery and filters, request size estimate, Retry-After parsing
  probe/                    context probe command
  http/                     HTTP server, forwarding with failover and streaming, client templates,
                            auth, responses
src/main/resources/logback.xml
src/test/java/...           JUnit 6 + AssertJ tests, with fake providers running in the test JVM
```

## Tests

```bash
mvn test
```

The tests start fake providers in the JVM and check round-robin, model discovery (spreading,
duplicates, filters, refresh, failures), context limits, silent truncation detection, the
context probe, model rewriting, failover on 429/5xx/network errors,
cooldowns, waiting when all targets are cooling, 401/404 handling, streaming, models listing,
client templates, authentication, and configuration validation.
