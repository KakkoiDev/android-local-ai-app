# Decisions

What was tried, what was measured, and what was rejected. Recorded so the same
ground is not covered twice — several of these went against the expectation.

Measurements were taken on a server CPU unless stated. Treat them as ratios
between options, not as phone numbers.

---

## D1. Run upstream binaries rather than JNI bindings

**Decided.** Build `llama-server` and `whisper-server` as native executables and
supervise them, instead of binding the libraries through JNI and writing an
OpenAI-compatible HTTP layer in Kotlin.

The binaries bring their own OpenAI surface, SSE streaming, CORS flags, GBNF
JSON mode and every future upstream fix. The JNI route means owning that surface
forever, and the compatibility this whole project depends on is the part least
worth reimplementing.

Cost: two C++ cross-compiles in CI, and the `jniLibs` packaging trick.

---

## D2. kuromoji.js in the browser — rejected

**Measured.** kuromoji.js with IPADIC, in Node:

| | |
|---|---|
| Dictionary | **17 MB gzipped** across 12 files |
| Build time | 1400 ms |
| Retained heap | 77 MB |
| Annotate | 1.0 ms/sentence |
| Accuracy | 102 tokens annotated, 1 left plain, over 30 sentences |

The alignment quality is excellent. The packaging is wrong for a browser: 17 MB
to download and 77 MB held in a tab, on a device that is also meant to be
running a 1.2 GB model.

Worth noting since it is widely repeated: the commonly cited "~4 MB dictionary"
figure is wrong. It is 17 MB.

---

## D3. The compact reading dictionary — rejected

**Measured, and this one was a surprise.** If the cost matrix is what makes
kuromoji big, drop it: extract every IPADIC entry containing kanji to a flat
surface→reading table and use longest-match.

The table works out at 238,075 entries, **1.62 MB gzipped** — a genuine 10×.
Then it was compared against kuromoji's Viterbi over 30 sentences:

**60% sentence agreement.** And where it disagreed it was badly wrong:
`本【もと】` for a book, `頭【つむり】`, `右手【うて】`, `雨【う】`, `家【か】`.

The connection-cost matrix is not overhead; it is the disambiguation. There is
no cheap version of it. This is why D4 exists: if the dictionary cannot be made
small, it has to go somewhere that does not care how big it is.

---

## D4. The tokenizer goes next to the model, not in the page

**Decided, from D2 and D3.** Same dictionary, two engines:

| | Build | Resident | Per sentence |
|---|---|---|---|
| MeCab + IPADIC (fugashi) | **1 ms** | **14 MB** | 0.12 ms |
| kuromoji.js + IPADIC | 1400 ms | 77 MB heap | 1.0 ms |

Identical output, three orders of magnitude apart on startup, because MeCab
mmaps the dictionary and kuromoji decompresses it into the heap. In a process
with a foreground service, 14 MB is free and the build happens once at start
rather than once per browser session.

---

## D5. IPADIC over UniDic

**Measured, against the expectation.** UniDic was expected to fix the compounds
IPADIC gets wrong. It does not; it breaks different ones.

| Input | IPADIC | UniDic-lite |
|---|---|---|
| 日本語 | にほんご | 日本 + 語 |
| 金曜日 | きんようび | 金曜 + 日【ひ】 |
| 一度 | いちど | 一 + 度 |
| 私 | わたし | わたくし |
| 一階 | 一 + 階 | 一 + 階 |

UniDic segments by short unit word — correct for verifying a reading one
morpheme at a time, wrong for writing furigana. It split five compounds IPADIC
kept whole, fixed none of the gemination, and costs 248 MB on disk against
IPADIC's ~50 MB.

The gemination is fixed by an override table in JP Core instead, because
`一階` → いっかい is a property of the compound and no analyser that segments it
can recover it.

---

## D6. Whisper over the Web Speech API

**Decided, reluctantly.** Chrome 139+ has `processLocally` and
`SpeechRecognition.install()` for on-device recognition, but the models ship on
desktop only — on Android `available({processLocally: true})` still resolves
`unavailable`. Until that changes, Web Speech means audio goes to Google.

Whisper in the browser was considered and rejected: `tiny` is ~75 MB, runs 2–3×
realtime on a *desktop* CPU, and its Japanese accuracy is poor — worst exactly
where it is needed most.

The cost of this decision falls on the client, and it is not small. Replacing
`SpeechRecognition` means `MediaRecorder`, resampling to 16 kHz mono WAV in the
page, and implementing end-of-speech detection that Web Speech did for free.
This app does not reduce that work; it only replaces "install Termux and type
six commands" with an install.

A client should keep the Web Speech path and add a capability check, so it flips
to on-device automatically the day Chrome ships the Android packs.

---

## D7. A classifier alongside the LLM

**Decided.** GLiNER2.5 as a fourth service. An encoder that emits N logits over
N declared labels in one forward pass cannot answer off-schema, needs no parser
or retry loop, is deterministic, returns calibrated probabilities, and costs two
orders of magnitude less than a 1.7 B generating tokens.

Open: which checkpoint after int8, whether every operator has an ONNX Runtime
Android kernel, and what the first real use is. See
[classifier.md](classifier.md).

---

## D8. One port, not four

**Decided.** Chrome's Local Network Access prompt is per origin and port. Four
services on four ports is four prompts, on a permission users are primed to
dismiss. One router, one prompt, one CORS policy.

The router forwards bodies verbatim. Rewriting them would mean owning a
compatibility surface this project did not write and cannot test against
upstream.

---

## D9. Loopback only by default

**Decided.** Binds `127.0.0.1`, and the CORS allowlist names origins rather than
defaulting to `*`.

A wildcard means any page in the browser can drive the models, read whatever a
client sends them, and flatten the battery. It stays available in settings and
is not the default. A LAN listener is a separate setting with its own warning.

---

## Open questions

| | |
|---|---|
| ONNX export for GLiNER2.5, and operator coverage on Android | Blocks D7 |
| Real latency for chat, speech and classification on a 6 GB device | Every phone figure here is an estimate |
| Whether OEM battery management kills the service in practice | Decides whether this is usable at all |
| First real use for the classifier | It is cheap enough to build first and answer second |
| Whether Vulkan beats the CPU backend on the target GPU | Default is CPU until proven |
