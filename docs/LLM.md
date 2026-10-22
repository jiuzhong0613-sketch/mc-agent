# Hosted LLM mode

The mod contains the agent loop itself. It calls a hosted, OpenAI-compatible
`/chat/completions` endpoint with the player's own API key and executes the
returned tool calls in game.

**Local and self-hosted endpoints are not supported.** `llm.baseUrl` must be
`https` and must not resolve to loopback, RFC1918, link-local or `.local`
hosts. This is enforced at request time, not documented as a suggestion.

## Supported providers

Any hosted OpenAI-compatible API works. Tested shapes:

| Provider | baseUrl | Notes |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | reference implementation |
| DeepSeek | `https://api.deepseek.com/v1` | OpenAI compatible |
| Moonshot | `https://api.moonshot.cn/v1` | OpenAI compatible |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4` | OpenAI compatible |
| OpenRouter | `https://openrouter.ai/api/v1` | model names are prefixed |
| SiliconFlow | `https://api.siliconflow.cn/v1` | OpenAI compatible |

The model must support tool/function calling. Models without tool support will
answer in prose and the loop will stop after one step.

## Where the key lives

Resolution order, first hit wins:

1. environment variable `MCAGENT_API_KEY` (best: launcher profile env);
2. `config/mcagent/secret.txt` (written with 0600 on POSIX);
3. `llm.apiKey` in `config/mcagent-client.toml`.

The key is read into memory per request, never logged, never included in
prompts, never returned by `/state` or `/health`, and never sent to the HTTP
bridge. `/agent key` only reports `set` or `missing`.

## Commands

| Command | Effect |
|---|---|
| `/agent start <goal>` | run the loop until done, step limit or `/agent stop` |
| `/agent stop` | cancel the loop and the running action |
| `/agent status` | running, step count, model, key presence |
| `/agent key` | where the key is read from |

## Cost guards

- `llm.maxSteps` (default 20) caps model calls per run;
- `llm.maxTokens` caps each response;
- `llm.timeoutSeconds` bounds a hung request;
- 429 and 5xx are retried once after 4 seconds, everything else stops the run;
- state sent to the model is a compressed summary, never the full block scan.

## Failure behaviour

| Symptom | Result |
|---|---|
| missing key | start refused, no request sent |
| 401/403 | "API key rejected", loop stops |
| 429 | one retry, then "rate limited" |
| non-https or local baseUrl | request refused with a config error |
| model returns no tool call | treated as completion |

## Security reality

A client-side mod cannot protect a secret from other mods, memory dumps or crash
reports. Storing the key outside the TOML and keeping it out of logs raises the
bar; it does not make the key safe. Players who do not trust their modpack should
use a separate, spend-limited key.

## Why no local models

Requested product constraint: every run must go through a hosted API key. There
is no "offline" or "llama.cpp" path in this project, and the endpoint check
rejects one.
