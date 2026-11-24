# LLM Proxy

A small OpenAI-compatible proxy that spreads requests from agentic tools (Cline, opencode, pi or
any OpenAI-compatible client) across several LLM providers, and fails over transparently when a
provider answers `429 Too Many Requests`.

```
Cline / opencode / pi ──► http://localhost:4000/v1  (one provider, one model: "coder")
                                   │
                              llm-proxy  ── round-robin, cooldowns, retries
                  ┌────────────────┼────────────────┐
             provider A        provider B        provider C
           (own key/URL,     (own key/URL,      (own key/URL,
            own model name)   several models)    own model name)
```

- **Round-robin** over every configured target (a provider + one of its models).
- **Failover on 429 / 5xx / timeouts / network errors**: the request is replayed on the next
  target before anything is sent to the client, so the tool never sees the 429.
- **Cooldowns**: a target that returned 429 is skipped for the time given by `Retry-After`,
  `retry-after-ms` or `x-ratelimit-reset-*`, or with exponential backoff when there is no hint.
- **Waits instead of failing** when every target is cooling down (bounded, configurable).
- **Optional proactive limit** per provider (`maxRequestsPerMinute`) to avoid 429s altogether.
- **Streaming (SSE)** passed through chunk by chunk, no buffering.
- **Virtual models**: tools ask for `coder`; the proxy sends each provider its own model name.
- Java 25, JDK HTTP server and client on virtual threads; only Jackson and SLF4J/Logback at runtime.

## Requirements

- JDK 25
- Maven 3.9+

## Build and run

```bash
mvn clean package                      # runs the tests, produces target/llm-proxy.jar
cp config.example.json config.json     # then edit it
cp .env.example .env                   # then fill in the keys
set -a; . ./.env; set +a               # export the variables (bash/zsh)
java -jar target/llm-proxy.jar config.json
```

The configuration file is, in order: the first argument, the `LLM_PROXY_CONFIG` environment
variable, or `config.json` in the working directory.

On Windows (PowerShell), set the variables with `$env:PROVIDER_A_API_KEY = "..."` before running
`java -jar`.

Behind a corporate proxy, pass the usual JVM options:
`java -Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080 -jar target/llm-proxy.jar`.

## Configuration

See [`config.example.json`](config.example.json). Every string value may reference environment
variables with `${NAME}` or `${NAME:-default}`: keep API keys in the environment, not in the file.

| Key | Default | Description |
|-----|---------|-------------|
| `listen.host` | `127.0.0.1` | Bind address. Use `0.0.0.0` in a container. |
| `listen.port` | `4000` | Port. |
| `localApiKeys` | `[]` | Keys clients must send (`Authorization: Bearer` or `x-api-key`). Empty disables authentication; empty values after substitution are ignored. |
| `upstreams[].name` | — | Unique name, used in `models` and logs. |
| `upstreams[].baseUrl` | — | Base URL **including** the version, e.g. `https://api.example.com/v1`. The client path after `/v1` is appended to it. |
| `upstreams[].apiKey` | — | Sent as `Authorization: Bearer <key>`. |
| `upstreams[].headers` | `{}` | Extra headers for this provider. |
| `upstreams[].passthroughModels` | `[]` | Real model names clients may request directly (sent unchanged). |
| `upstreams[].maxRequestsPerMinute` | none | Proactive sliding-window limit for this provider. |
| `models` | — | Virtual model → list of `{ "upstream", "model" }` targets, used in round-robin order. The same provider can appear several times with different models. |
| `defaultModel` | none | Virtual model used when the request has no or an unknown `model`. |
| `retry.maxAttempts` | `6` | Maximum upstream calls per client request. |
| `retry.defaultCooldown` | `30s` | Cooldown when the provider gives no hint; doubles on each consecutive failure. |
| `retry.maxCooldown` | `5m` | Upper bound of any cooldown, hints included. |
| `retry.misconfiguredCooldown` | `10m` | Cooldown after `401`/`403` (bad key). |
| `retry.maxWaitWhenAllCooling` | `60s` | How long a request may wait for a target when all are cooling down; after that the client gets a `429` with `Retry-After`. |
| `timeouts.connect` | `10s` | Connection timeout to providers. |
| `timeouts.request` | `10m` | Time allowed until the provider's response headers arrive (streams themselves are not limited). |
| `timeouts.shutdown` | `10s` | Grace period for in-flight requests on shutdown. |

Durations accept `500ms`, `30s`, `5m`, `1h`, `1m30s`, a number of seconds, or ISO-8601 (`PT30S`).

### How upstream statuses are handled

| Upstream answer | Proxy behaviour |
|-----------------|-----------------|
| `2xx` / `3xx` | Streamed to the client; the target's failure counter is reset. |
| `429` | Target cooled down (upstream hint or backoff), request replayed on the next target. |
| `408`, `500`, `502`, `503`, `504`, `520`–`599`, timeout, network error | Same as 429. |
| `401`, `403` | Logged as a configuration error; target set aside for `misconfiguredCooldown`; next target tried. |
| `404` | Next target tried for this request only (unknown model or endpoint on that provider), no cooldown. |
| Other `4xx` (e.g. `400`) | Returned as is: the request itself is wrong, retrying would not help. |

When every attempt fails, the client receives the last upstream error, or a `429` with a
`Retry-After` header when all targets are rate limited.

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /v1/models`, `GET /v1/models/{id}` | Models served by the proxy (virtual first, then passthrough). |
| `* /v1/...` | Forwarded with failover: `chat/completions`, `completions`, `embeddings`, `responses`... Any path works as long as the providers support it. |
| `GET /health` | Health of every target (no authentication). Always `200`; `status` is `ok` or `degraded`. |

The `/v1` prefix is optional (`/chat/completions` works too), as tools differ on whether they add
it to the base URL. Every response carries an `X-LLM-Proxy-Target` header naming the target that
served it.

## Client setup

Use the proxy as an **OpenAI-compatible** provider with base URL `http://localhost:4000/v1`, the
local key from `LLM_PROXY_LOCAL_KEY` (any value if authentication is disabled), and a virtual model
name such as `coder`.

### Cline

Settings → API Provider: **OpenAI Compatible**

- Base URL: `http://localhost:4000/v1`
- API Key: your `LLM_PROXY_LOCAL_KEY`
- Model ID: `coder`

Cline's own retry setting can stay on; with the proxy it should rarely trigger.

### opencode

`opencode.json` (project) or `~/.config/opencode/opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "llm-proxy": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "LLM Proxy",
      "options": {
        "baseURL": "http://localhost:4000/v1",
        "apiKey": "{env:LLM_PROXY_LOCAL_KEY}"
      },
      "models": {
        "coder": { "name": "Coder (round-robin)" }
      }
    }
  }
}
```

Then pick `llm-proxy/coder` with `/models`.

### pi

Declare a custom provider in pi's models file (`~/.pi/agent/models.json`) using the OpenAI
completions API, for example:

```json
{
  "providers": {
    "llm-proxy": {
      "baseUrl": "http://localhost:4000/v1",
      "api": "openai-completions",
      "apiKey": "LLM_PROXY_LOCAL_KEY",
      "models": [
        { "id": "coder", "name": "Coder (round-robin)", "contextWindow": 128000, "maxTokens": 16384 }
      ]
    }
  }
}
```

Field names follow pi's custom-provider documentation at the time of writing; check it if your
pi version differs.

### Any other tool

Anything that lets you set an OpenAI base URL works. For tools reading environment variables:

```bash
export OPENAI_BASE_URL=http://localhost:4000/v1
export OPENAI_API_KEY=$LLM_PROXY_LOCAL_KEY
```

## Tips

- Put the **same model** (or equivalent ones) in one virtual model: the provider can change
  between two turns of a conversation, which works because the whole history is resent each time,
  but different models may behave slightly differently.
- If a provider serves several models with separate quotas, list each of them as a target of the
  virtual model: more targets means more capacity.
- If you know a provider's quota, set `maxRequestsPerMinute` slightly below it.
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
src/main/java/fr/antschw/llmproxy/
  Main.java                 entry point
  config/                   configuration records, JSON loader, duration parser
  routing/                  target pool (round-robin, cooldowns, rate windows), Retry-After parsing
  http/                     HTTP server, forwarding with failover and streaming, auth, responses
src/main/resources/logback.xml
src/test/java/...           JUnit 6 + AssertJ tests, with fake upstreams running in the test JVM
```

## Tests

```bash
mvn test
```

The tests start fake providers in the JVM and check round-robin, model rewriting, failover on
429/5xx/network errors, cooldowns, waiting when all targets are cooling, 401/404 handling,
streaming, models listing, authentication, and configuration validation.
