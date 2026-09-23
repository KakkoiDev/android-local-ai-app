# Airplane mode

The requirement is that every model works with the radios off. This is what
that depends on, component by component, and where it can still go wrong.

## The models

None of the four touches the network at inference time. That is a property of
the engines, not a configuration:

| | Offline at inference | Caveat |
|---|---|---|
| llama.cpp | Yes | — |
| whisper.cpp | Yes | — |
| MeCab + IPADIC | Yes | — |
| GLiNER2.5 via ONNX Runtime | Yes | **The tokenizer must be bundled.** |

The GLiNER caveat is the only real one and it is a classic. Hugging Face
tooling resolves a tokenizer by repo id and will reach for the network to fetch
`tokenizer.json` the first time, so a build that works on a developer's machine
fails on a plane. The export step must write the tokenizer next to the model and
load it from that path, and the way to be sure is to test with the radios off,
on a device that has never downloaded it, not by reading the code.

Weights obviously have to be downloaded first. `/v1/status` reports `absent`
rather than pretending, and a request in that state returns `409` naming the
model instead of silently trying to fetch it.

## Loopback

Airplane mode takes down the radios. It does not take down `lo`. A server bound
to `127.0.0.1` keeps accepting, and a browser on the same device keeps
connecting.

This is the assumption the probe exists to confirm rather than assert — see
[../probe/README.md](../probe/README.md). It should hold trivially. Assumptions
that should hold trivially are the ones worth ten minutes of checking.

## The part that actually breaks

Not the models. The web app.

Echo is served from `https://echo.kakkoi.dev`. With the radios off there is no
DNS and no TLS, so the page can only come from the service worker's cache. That
means:

1. **It must have been opened online at least once** on that device, with the
   service worker installed and every asset precached.
2. **The service worker must be network-first with a cache fallback**, which
   Echo's is: each fetch tries the network, and on failure falls back to the
   cache. In airplane mode every fetch fails fast and every asset comes from
   the cache. Installing it to the home screen makes this the normal path
   rather than a lucky one.
3. **A cache miss is fatal and silent.** An asset that was never precached and
   is not in the runtime cache simply does not load, and the failure surfaces
   as a broken page rather than an error.

So "works in airplane mode" is a claim about the client's offline story first
and the models second. The models are the easy half.

## What to verify, in order

Each step is the cheapest thing that can fail at that point:

1. **Probe, radios on.** `/ping` answers from a browser on the phone.
2. **Probe, radios off.** `/ping` still answers, `beatsInAirplaneMode` climbs.
   Loopback confirmed.
3. **Probe, radios off, from an HTTPS origin.**
   [`../tools/localnet-check.html`](../tools/localnet-check.html) hosted
   somewhere public, opened on the phone. This is the only step that exercises
   Chrome's Local Network Access permission, because loopback-to-loopback skips
   it. Expect the prompt once.
4. **Echo, radios off.** Open it from the home screen with no connection. If the
   app shell does not load, nothing downstream matters.
5. **Echo, radios off, pointed at the probe.** Set the local endpoint to
   `http://127.0.0.1:8080` and confirm the request leaves the page and comes
   back. It will fail at the model — the probe has none — but a `404` from the
   probe proves the whole path.

Step 4 is the one most likely to fail, and it has nothing to do with this repo.

## What none of this proves

That the models are fast enough, or that four of them fit. Airplane mode is a
connectivity question. The memory budget in
[models.md](models.md#the-6-gb-budget) and the latency figures marked as
estimates are separate, and they need a real device with real weights on it.
