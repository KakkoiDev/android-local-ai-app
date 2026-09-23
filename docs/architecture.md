# Architecture

## One supervisor, four children

A single foreground service owns a supervisor. The supervisor owns nothing but
process lifecycles and a port map. It writes no inference code and proxies no
bytes: each engine binds its own loopback port and clients talk to it directly.

```
                    ┌──────────────────────────────────────────┐
  browser           │  Foreground service (persistent notice)  │
  ───────────────►  │                                          │
  127.0.0.1:8080    │   Router  ─┬─► llama-server      :8081   │
                    │            ├─► whisper-server    :8082   │
                    │            ├─► morphology        :8083   │
                    │            └─► classifier        :8084   │
                    └──────────────────────────────────────────┘
```

The router is the only listener a client sees. It exists for three reasons and
no others:

1. **One port, one permission.** Chrome's Local Network Access prompt is
   per-origin-and-port. Four ports would be four prompts.
2. **Start on demand.** A request for a service whose process is not running
   blocks while the supervisor starts it, rather than failing.
3. **One CORS policy**, applied in one place, rather than four engines
   configured four different ways.

It does not transform requests. A `/v1/chat/completions` body is forwarded to
llama-server byte for byte, and the response streams back untouched, because the
moment the router starts rewriting bodies it owns a compatibility surface it did
not write and cannot test against upstream.

## Getting a binary to run at all

Android has blocked `exec()` on files in an app's writable data directory since
API 29. The one directory that stays executable is `nativeLibraryDir`, which the
installer populates from `jniLibs` and marks executable.

So each engine is built as a normal executable and **packaged under a `lib`
name**:

```
jniLibs/arm64-v8a/libllama-server.so     ← an ELF executable, not a shared object
jniLibs/arm64-v8a/libwhisper-server.so
jniLibs/arm64-v8a/libmecab.so
```

`extractNativeLibs=true` must stay set in the manifest, or the installer leaves
them compressed inside the APK with no path on disk to exec.

This is a packaging trick, not a hack around a security boundary: the files are
shipped in the APK, verified by the installer, and never writable at runtime.

The alternative — JNI bindings plus a hand-written HTTP layer — was rejected.
See [decisions.md](decisions.md#d1-run-upstream-binaries-rather-than-jni-bindings).

## The classifier is different

GLiNER2.5 has no upstream server binary. It is served by ONNX Runtime through
its Android AAR, in-process, with a thin Kotlin handler implementing
`/v1/classify`. That makes it the one service whose request handling this
project actually owns, and the one place where a schema has to be validated
rather than forwarded. See [classifier.md](classifier.md).

## Lifecycle

Each service is in exactly one of four states:

| State | Meaning |
|---|---|
| `absent` | Weights not downloaded |
| `cold` | Weights on disk, process not running |
| `warming` | Process started, model loading |
| `ready` | Accepting requests |

A request arriving in `cold` triggers a start and waits. A request arriving in
`absent` returns `409` with a body naming the model to download — never a
silent download, because these are hundreds of megabytes and the user may be on
mobile data.

Idle timeouts are per service, because their reload costs differ by two orders
of magnitude:

| Service | Default idle timeout | Reload cost |
|---|---|---|
| Chat | 5 minutes | seconds (mmap, but page cache is cold after eviction) |
| Speech | 30 minutes | ~1 s |
| Morphology | never | 1 ms — measured; there is nothing to reclaim |
| Classification | 15 minutes | ~1 s estimated |

Chat is the only one that genuinely needs evicting. Holding a 1.2 GB model
resident on a 6 GB phone is what gets the whole service killed by the low-memory
killer, and the user blames the app, not the model. See
[models.md](models.md#the-6-gb-budget).

## Failure is visible, not silent

Every state change is written to a ring buffer the UI renders. An engine that
dies is restarted at most twice; the third failure leaves the service in `cold`
with the last 64 lines of its stderr attached to the `503` body, because
"translation failed" with no reason is the failure mode that wastes the most
time.
