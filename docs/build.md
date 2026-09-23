# Build and release

## Layout

```
app/                     Kotlin: service, supervisor, router, classifier handler, UI
  src/main/jniLibs/arm64-v8a/    populated by the native build, not checked in
native/
  llama.cpp/             submodule
  whisper.cpp/           submodule
  mecab/                 submodule or vendored release
  build.sh               cross-compiles all three, stages into jniLibs
models/
  manifest.json          checkpoint ids, URLs, sizes, checksums
spec/openapi.yaml
```

## Native build

NDK r27+, CMake, `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-29`.

Each project builds its server target as an executable, then gets **renamed** to
`lib<name>.so` when staged into `jniLibs`. The rename is the packaging trick
from [android-constraints.md](android-constraints.md#executable-files); the
binaries are ordinary ELF executables and nothing about the build makes them
shared objects.

Notes per engine:

- **llama.cpp** — CPU backend first. Vulkan on Adreno and Mali is often no
  faster than the CPU path on phones and adds a large surface of driver bugs;
  make it a build flag, prove it on the target device, and do not default to it.
- **whisper.cpp** — without ffmpeg, so the server takes 16 kHz mono WAV only.
  That is a deliberate contract choice, not an oversight: linking ffmpeg to
  decode a format the client can convert in thirty lines is a large dependency
  for no benefit. It is stated in [http-api.md](http-api.md).
- **MeCab** — build against IPADIC, not UniDic. The dictionary is data and is
  downloaded with the other models rather than packaged, since it is ~50 MB.

## Version pinning

Every submodule is pinned to a tag, and the pin is bumped deliberately. These
are the projects whose OpenAI compatibility this app inherits wholesale; a
silent upgrade that changes a response shape breaks clients that never touched
this repo.

## Release

CI builds the three native targets, assembles the APK, and attaches it to a
GitHub Release with its SHA-256. The APK carries no weights; `models/manifest.json`
is the source of truth for what gets downloaded, with a checksum per entry so a
truncated download fails loudly rather than producing an engine that will not
start.

Signing: a release keystore in repository secrets. Debug-signed APKs must never
be attached to a release — installing one blocks the upgrade path, because
Android refuses to update across a signature change and the user has to
uninstall, losing their downloaded models.

## Verifying it works

An emulator proves the APK installs and the HTTP surface answers. It does not
prove anything about thermal throttling, the low-memory killer, or OEM battery
management, which are the three things most likely to sink this. Every
performance number in [models.md](models.md) marked as an estimate needs a real
6 GB device.

Smoke test, in order — each step is the cheapest thing that can fail:

1. `GET /v1/status` with nothing downloaded → all four `absent`.
2. Download morphology alone → `POST /v1/morphology` returns the annotated
   sentence. Cheapest service, proves the whole path.
3. Download chat → `POST /v1/chat/completions` with `response_format` json_object
   returns parseable JSON.
4. Record 3 seconds, convert to 16 kHz WAV, `POST /v1/audio/transcriptions`.
5. `POST /v1/classify` with a three-label schema; check the scores sum to 1.
6. All four resident, `GET /v1/status` → compare resident bytes against the
   budget in [models.md](models.md#the-6-gb-budget).
7. Leave it an hour. Chat should have evicted itself; morphology should not
   have.
8. Lock the phone, wait, come back. Did the OEM kill the service?

Step 8 is the one that decides whether this is usable.
