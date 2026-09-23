# Android constraints

The platform rules that shaped the architecture. Most of these are not
negotiable and several are the reason a design that looks obvious does not work.

## Local Network Access

Chrome 142 enforces [Local Network
Access](https://developer.chrome.com/blog/local-network-access): a public HTTPS
origin reaching a loopback or private address needs an explicit permission
prompt. `https://echo.kakkoi.dev` fetching `http://127.0.0.1:8080` is exactly
that case.

Three consequences:

- **The prompt is unavoidable and one-time.** It is not a bug to work around;
  the first-run instructions should show what it looks like so nobody dismisses
  it by reflex.
- **Mixed content stops mattering.** Permission-gated local requests are exempt
  from mixed-content blocking, so `https:` → `http://localhost` is fine once
  granted. Without the exemption this whole design would need TLS on a
  loopback listener, which means a certificate nobody can issue.
- **Chrome has to know the destination is local before it resolves.** The
  literal hostname `localhost` qualifies. A client fetching a LAN IP should
  annotate the fetch with `targetAddressSpace: "local"`.

## Executable files

`exec()` on an app's writable data directory has been blocked since API 29.
`nativeLibraryDir` stays executable, so engines ship as `jniLibs/arm64-v8a/lib*.so`
and `extractNativeLibs=true` must remain set or they stay compressed in the APK
with no path to exec. See [architecture.md](architecture.md#getting-a-binary-to-run-at-all).

## Foreground service

Android 14+ requires a declared `foregroundServiceType`, and there is no
category for local inference. `dataSync` is the closest fit and is a slightly
dishonest manifest — irrelevant for a sideloaded APK, a problem the day anyone
wants this on Play. Worth knowing before that day, not after.

A persistent notification is mandatory. Make it useful: which services are
resident, and a stop-all action.

## OEM battery management

Samsung, Xiaomi and OnePlus kill foreground services aggressively regardless of
type. Request the battery-optimisation exemption explicitly, and expect some
devices to kill the service anyway.

The client-side consequence matters more than the app-side one: a client must
treat a connection refused as "service was killed", not as "user has no local
AI". Retry once after a start, then fall back to the cloud provider with a
message that says what happened.

## ABI

`arm64-v8a` only. `armeabi-v7a` is a 32-bit address space, which is not a
sensible place to load a 1.2 GB model, and shipping both doubles the binary
payload for devices that cannot run the product anyway.

## Storage

App-specific external storage. No permission needed, uninstall reclaims it, and
`MANAGE_EXTERNAL_STORAGE` — which a "download models anywhere" feature would
need — is a Play-policy fight for no benefit.

## Distribution

GitHub Releases, `arm64-v8a` APK, and users enabling "install unknown apps".

F-Droid is a plausible later step and is more work than it looks: reproducible
builds from source, and prebuilt native blobs are exactly what that policy is
designed to exclude. The two C++ engines would have to build in their pipeline.

## What this rules out

- **Serving anything to the LAN by default.** Binds `127.0.0.1`. A LAN listener
  is a separate, deliberate setting with its own warning, because the models
  would then answer to anything on the network.
- **Reaching other apps' models.** Google AI Edge Gallery and ML Kit are native
  APIs with no HTTP surface; a browser cannot touch them. That is the gap this
  app fills.
- **Background downloads on a metered connection** without asking. A first run
  that wants everything is about 1.6 GB.
