# HTTP API

Base URL `http://127.0.0.1:8080`. The machine-readable contract is
[../spec/openapi.yaml](../spec/openapi.yaml); this document is the reasoning
behind it.

## Compatibility is the point

Three of the four services speak dialects that already have clients. A web app
that talks to OpenAI, or to Ollama, or to a llama.cpp server on a laptop, should
need a changed base URL and nothing else. Echo, for example, already has a
`local` provider that posts to a configurable `/v1/chat/completions` — pointing
it here is a settings change, not a code change.

So the shapes below are not designed. They are adopted.

## `POST /v1/chat/completions`

llama.cpp's implementation, forwarded verbatim. Streaming via SSE works because
nothing in the path buffers.

Two notes for clients:

- **`response_format: {"type": "json_object"}` is supported**, and should be
  used. llama.cpp implements it with GBNF grammars, which constrain generation
  rather than asking politely, so a 1–2 B model that would otherwise wrap its
  JSON in prose returns parseable output. A client that disables JSON mode for
  local providers — as Echo's `api.js` currently does — is leaving the single
  biggest reliability win on the table.
- **Prefer small prompts over clever ones.** Every instruction a 1.7 B model has
  to satisfy simultaneously is a chance to satisfy none of them. Ask for the
  translation; get the readings from `/v1/morphology` and the labels from
  `/v1/classify`.

## `POST /v1/audio/transcriptions`

OpenAI's multipart shape: `file`, `model`, optional `language`,
`response_format`.

The wrinkle is the audio. whisper.cpp wants 16 kHz mono PCM, and unless it was
built against ffmpeg it will not decode the WebM/Opus that `MediaRecorder`
produces. The contract therefore states **16 kHz mono WAV** and the client
converts, which in a browser is `AudioContext.decodeAudioData` plus about thirty
lines of WAV header writing and no dependency.

A browser client also has to do something the Web Speech API did for free:
decide when the speaker stopped. `MediaRecorder` will happily record silence
forever. Either an RMS threshold over an `AnalyserNode` or an explicit stop
button — but it is the client's problem, and it is the main reason swapping
Web Speech for this is a feature and not a config change.

## `POST /v1/morphology`

No OpenAI equivalent, so a minimal contract of our own.

```json
{ "text": "受け付けは一階にあります。", "annotate": true }
```

```json
{
  "annotated": "受【う】け付【つ】けは一階【いっかい】にあります。",
  "tokens": [
    { "surface": "受け付け", "reading": "ウケツケ" },
    { "surface": "は",       "reading": "ハ" },
    { "surface": "一",       "reading": "イチ" },
    { "surface": "階",       "reading": "カイ" }
  ]
}
```

`tokens` is the raw analyser output. `annotated` is the same stream after
alignment and the override table, in JP Core's `漢字【かんじ】` notation — so
`一` + `階` becomes `一階【いっかい】`, which no analyser that segments the
compound can produce on its own.

Both halves are returned because a client that already has the alignment (Echo
carries it in `core.js`) may want the tokens and nothing else, and a client that
does not should never be made to reimplement it.

The alignment and the override table are
[JP Core](https://github.com/KakkoiDev/jp-core)'s, not this project's. This
service is a transport for `jp_core.reading`, and any disagreement between them
is a bug here.

## `POST /v1/classify`

See [classifier.md](classifier.md) for the shape and the reasoning.

## Operational endpoints

| | |
|---|---|
| `GET /v1/status` | Every service's state, resident bytes, and idle countdown |
| `POST /v1/models/{service}/download` | Explicit, because these are hundreds of megabytes |
| `DELETE /v1/models/{service}` | Reclaim the disk |
| `POST /v1/services/{service}/stop` | Evict now rather than at the idle timeout |

## Errors

Plain JSON, and always with a reason:

| Status | When |
|---|---|
| `409` | Weights absent. Body names the model and its size. Never auto-downloads. |
| `503` | Engine failed to start or died. Body carries its last 64 stderr lines. |
| `507` | Not enough free memory to load without evicting something. Body names what. |

`503` with no detail is the failure that costs the most time to diagnose from
the browser side, where all you see is a rejected fetch.

## CORS and the browser

The router answers preflight for the configured origins. `Content-Type:
application/json` on a cross-origin POST forces a preflight on every request, so
this is not optional.

The default allowlist is `https://echo.kakkoi.dev` and `http://localhost:*`.
Wildcard `*` is available in settings and is a genuine risk: any page in the
browser could then drive the models, read anything the client sends, and run the
battery down. It is not the default.

Requests from an HTTPS page to `http://localhost` also need Chrome's Local
Network Access permission — see
[android-constraints.md](android-constraints.md#local-network-access).
