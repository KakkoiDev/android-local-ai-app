# Survival probe

A throwaway app that does nothing except keep running, so you can find out
whether this device lets it.

It is the cheapest possible answer to the question that decides whether
[the real thing](../README.md) is worth building: **does a foreground service
on your phone survive a night of OEM battery management, and is its loopback
socket still reachable afterwards?** Finding that out after integrating two C++
inference engines would be an expensive way to learn it.

No dependencies — not even AndroidX. Every library is something that could fail
for a reason unrelated to the question.

## Install

Download the APK from the
[`probe-latest`](https://github.com/KakkoiDev/android-local-ai-app/releases/tag/probe-latest)
release, allow install from unknown sources, open it.

1. **Start probe**
2. **Exempt from battery optimisation** — this only covers Android's own
   optimiser. If you are on Samsung, Xiaomi or OnePlus, also find the OEM
   setting (usually Settings → Battery → *app* → Unrestricted, or "Don't
   optimise"), because that layer kills services without reporting through any
   API.
3. Leave it. Overnight is the real test; an hour is the minimum for the
   verdict to say anything.

## Reading it

The app shows a running tally and a verdict. The log underneath it is the real
output — CSV, one line a minute:

```
event,wallMillis,elapsedRealtime,airplaneMode
```

Two clocks, on purpose. `elapsedRealtime` keeps counting while the device
sleeps and `wallMillis` does not distinguish sleeping from dying, so the pair of
them separates the two cases that a single clock confuses:

| Wall advanced | Elapsed advanced | What happened |
|---|---|---|
| yes | yes | **We were killed.** The device was awake and we were not running. |
| yes | no | Device was powered off. Not a result. |
| yes | yes, but ≤ 2 beats | Alarm batching. Normal, not a kill. |

A gap counts only above two missed beats plus slack, because Android batches
alarms and exactly one late beat means nothing.

Every `START` after the first is Android restarting us, which means something
stopped us. `restarts` is therefore the blunt version of the same signal, and
the gap in front of each restart is how long the service was gone.

## The verdict

| | |
|---|---|
| 0 gaps | Survived. A foreground service here is viable. |
| 1–2 gaps | Killed occasionally. Usable only if the client retries a start on a refused connection. |
| 3+ gaps | This device will not host the real app. |

## The other half: is it reachable?

Surviving is not enough — the socket has to still answer. While the probe runs:

```
http://127.0.0.1:8080/ping    status as JSON
http://127.0.0.1:8080/log     the raw CSV
```

Open `/ping` in Chrome on the phone and you have proven the socket is alive,
but **not** that a web app can reach it. A page at `http://localhost` talking to
`http://127.0.0.1` is loopback to loopback, which is the one case Chrome's
Local Network Access permission does not apply to.

To test what Echo will actually do, the page has to be served from a public
HTTPS origin. [`tools/localnet-check.html`](../tools/localnet-check.html) is
that page; host it anywhere with HTTPS and open it on the phone. You should see
Chrome's Local Network Access prompt exactly once. That prompt appearing is a
pass, not a problem.

## Airplane mode

Turn it on and leave the probe running. `beats in airplane` should keep
climbing and `/ping` should keep answering, because loopback is not a radio.
See [../docs/offline.md](../docs/offline.md) for what that does and does not
prove about the real app.

## Building it yourself

CI builds this on every push to `probe/`. Locally you need an Android SDK,
which the spec repo does not assume:

```
cd probe && ./gradlew assembleDebug
```

Debug-signed, deliberately: it is a throwaway, and a release keystore for it
would be a key to look after for no reason.
