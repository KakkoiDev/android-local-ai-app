# Models

## Chat

The target device is a 6 GB phone, which after Android and its own services
leaves roughly 3–3.5 GB to work with. That rules out a 4 B model if anything
else is to stay resident.

| Checkpoint | Q4_K_M | Verdict |
|---|---|---|
| Qwen3 1.7B Instruct | ~1.1 GB | **Default.** Fits beside the other three. |
| Gemma 3 1B | ~0.8 GB | Fallback for tighter devices. See the caution below. |
| TranslateGemma 4B | ~2.5 GB | Best translation quality available; only on 8 GB+ |

Translation quality falls off a cliff below 4 B, and it is worth being concrete
about how far. Gemma 3's own technical report gives WMT24++ scores of 36.7 for
the 1 B, 48.4 for the 4 B, 53.9 for the 12 B and 55.7 for the 27 B — the largest
single jump in the family is 1 B to 4 B.

Two things make a 1.7 B viable here anyway, and neither is optional:

1. **Furigana leaves the prompt.** It goes to `/v1/morphology`, where it is a
   dictionary lookup with a known answer rather than a generation task with a
   plausible one.
2. **Registers leave the prompt.** The casual/polite pair was dropped from
   Echo's local path by decision. What remains is "translate this sentence",
   which is the one thing small instruct models do well.

For pairs that are not Japanese, a dedicated MT model is worth considering over
an LLM entirely: an Opus-MT pair is tens of megabytes and near-instant. It
cannot follow instructions, emit JSON or produce two registers — which, after
the two changes above, it no longer needs to.

## Speech

| Checkpoint | Size | Notes |
|---|---|---|
| `ggml-base` | ~150 MB | **Default.** |
| `ggml-tiny` | ~75 MB | Poor on Japanese. Do not default to it. |
| `ggml-small` | ~500 MB | Roughly 2× the latency of base |

Whisper's accuracy on Japanese degrades faster with model size than on European
languages, and `tiny` is the size at which it starts inventing plausible kana.
For 3–5 second shadowing utterances `base` should land in the low seconds on a
recent phone — estimated, not measured, and the first thing to benchmark once
anything runs.

## Morphology

MeCab with **IPADIC**, not UniDic. This is measured, and the measurement went
against the expectation.

UniDic segments by short unit word, which is correct for verifying a reading one
morpheme at a time and wrong for writing furigana, where the compound's own
reading is the answer. Over a 30-sentence corpus:

| Input | IPADIC | UniDic-lite |
|---|---|---|
| 日本語 | にほんご | 日本 + 語 |
| 金曜日 | きんようび | 金曜 + 日【ひ】 |
| 一度 | いちど | 一 + 度 |
| 私 | わたし | わたくし |

UniDic split five compounds IPADIC kept whole and fixed none of the gemination
that motivated looking at it. IPADIC also costs less on disk (~50 MB against
unidic-lite's 248 MB).

Runtime, measured in Python via fugashi on a server CPU:

| | Build | Resident | Per sentence |
|---|---|---|---|
| MeCab + IPADIC | 1 ms | 14 MB | 0.12 ms |
| kuromoji.js + IPADIC | 1400 ms | 77 MB heap | 1.0 ms |

Same dictionary, same output, three orders of magnitude apart on startup,
because MeCab mmaps the dictionary and kuromoji decompresses it into the heap.
This is the measurement that decided the tokenizer belongs in a process and not
in a browser tab. See
[decisions.md](decisions.md#d3-the-tokenizer-goes-next-to-the-model-not-in-the-page).

## Classification

| Checkpoint | Params | fp32 | int8 (est.) |
|---|---|---|---|
| `fastino/gliner2.5-small-v1` | 74 M | ~300 MB | ~90 MB |
| `fastino/gliner2.5-base-v1` | 194 M | ~780 MB | ~220 MB |
| `fastino/gliner2.5-multi-v1` | 287 M | ~1.1 GB | ~320 MB |

**Default `multi`**, against the upstream recommendation of `base`, for one
reason: the English-only checkpoints are the wrong tool for an app whose whole
point is language pairs. `base` is the right default for an English-only
deployment.

Sizes at fp32 are from the source gist; the int8 figures are estimates from the
usual ~3.5× reduction and must be confirmed by exporting. Static quantisation
with calibration data, not dynamic — dynamic int8 is the configuration that
quietly loses accuracy on encoders.

## The 6 GB budget

Everything resident at once, `multi` at int8 and Qwen3 1.7B at Q4_K_M:

| | Resident |
|---|---|
| Android and system | ~2.5 GB |
| Chat (Qwen3 1.7B Q4_K_M) | ~1.2 GB |
| Speech (whisper base) | ~0.2 GB |
| Classification (GLiNER2.5-multi int8) | ~0.4 GB |
| Morphology (MeCab + IPADIC) | ~0.02 GB |
| Browser tab | ~0.05 GB |
| **Total** | **~4.4 GB** |

That leaves roughly 1.5 GB of headroom on a 6 GB device, which is enough to
survive a browser that decides to allocate, and not enough to be careless with.
Swap chat to a 4 B and the total passes 5.7 GB and the low-memory killer starts
making the decisions instead.

This is why chat has the short idle timeout and why `/v1/status` reports
resident bytes: the budget is the product, and it has to be visible.

## Storage

Nothing ships in the APK. Weights land in app-specific external storage, which
means uninstalling reclaims them and no storage permission is needed. A first
run that wants all four downloads about 1.6 GB, so the UI states the total
before the first byte, per service, and never downloads on a `409`.
