# android-local-ai-app

One sideloadable Android app that runs four local models behind HTTP endpoints on
`localhost`, starts each on demand, and stops it again when nothing has asked for
it in a while.

It exists so that a web app — [Echo](https://github.com/KakkoiDev/jp-echo) first,
but nothing here is specific to it — can point a settings field at
`http://localhost:8080` and get translation, dictation, Japanese readings and
classification without a single request leaving the device.

**Status: specification only.** Nothing here is built. Every number in these
documents is either measured, cited, or marked as an estimate, and
[docs/decisions.md](docs/decisions.md) records what was tried and rejected so
the same ground is not covered twice.

## The four services

| Service | Engine | Endpoint | Why it is here |
|---|---|---|---|
| Chat | llama.cpp `llama-server` | `/v1/chat/completions` | Translation, and anything else a small instruct model can do |
| Speech | whisper.cpp `whisper-server` | `/v1/audio/transcriptions` | Dictation, because the Web Speech API cannot go on-device on Android |
| Morphology | MeCab + IPADIC | `/v1/morphology` | Japanese readings, which are a dictionary lookup and not a generation task |
| Classification | GLiNER2.5 via ONNX Runtime | `/v1/classify` | Deterministic labels in one forward pass, no sampling, no prompt |

Three of them speak an OpenAI-compatible dialect so existing clients need no new
code. The two that have no OpenAI equivalent — morphology and classification —
get their own small contracts, defined in [spec/openapi.yaml](spec/openapi.yaml).

## Why an app rather than Termux

Termux already does all of this, and the setup is six commands plus a package
build. That is a fine answer for one person and a bad one for anybody else. The
app replaces those commands with an install, and nothing else about the
architecture changes — it runs the same upstream binaries, so it inherits their
OpenAI compatibility, their CORS flags and their bug fixes for free.

## What it will not do

- **Ship weights.** The APK carries binaries, not models. Everything is
  downloaded on first use and stored in app-specific external storage.
- **Serve anything but loopback by default.** The listener binds `127.0.0.1`.
  Serving a LAN is a deliberate, separate setting.
- **Reimplement inference.** If a fix belongs upstream it goes upstream.

## Read next

| | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Processes, the supervisor, and how a binary gets to be executable on Android at all |
| [docs/http-api.md](docs/http-api.md) | The contract, and what a client has to do differently per service |
| [docs/models.md](docs/models.md) | Which checkpoints, how big, and whether four of them fit in 6 GB |
| [docs/classifier.md](docs/classifier.md) | GLiNER2.5: what it is, and what it is worth having on a phone |
| [docs/android-constraints.md](docs/android-constraints.md) | The platform rules that shape all of the above |
| [docs/build.md](docs/build.md) | Cross-compiling two C++ projects and shipping an APK |
| [docs/decisions.md](docs/decisions.md) | What was measured, what was rejected, and why |
