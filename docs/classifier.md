# Classification

## What GLiNER2.5 is

An encoder that does schema-driven information extraction: entities,
classification, structured records, relations and span attributes, all declared
in one schema and answered in **one forward pass**. Three checkpoints, Apache
2.0, on Hugging Face under `fastino/gliner2.5-{small,base,multi}-v1`, built on
DeBERTa-v3 (mDeBERTa for `multi`).

The 2.5 release replaces GLiNER's span enumeration with boundary prediction —
scoring start and end positions directly — so inference scales linearly with
document length rather than quadratically with candidate spans.

Sources: [the release
post](https://fastino.ai/blog/gliner2-5-span-free-information-extraction), the
[GLiNER2 paper](https://arxiv.org/pdf/2507.18546), and the checkpoint cards
([base](https://huggingface.co/fastino/gliner2.5-base-v1),
[multi](https://huggingface.co/fastino/gliner2.5-multi-v1),
[small](https://huggingface.co/fastino/gliner2.5-small-v1)).

## Why it earns a slot next to an LLM

Because it is not doing the same job, and doing it with an LLM is worse in every
dimension that matters on a phone.

Give it N labels and it emits N logits and softmaxes over exactly those N. The
output layer's width *is* the schema. That buys four things a generative model
cannot give you:

- **It cannot answer off-schema.** There is no token to sample that is not one
  of your labels. No "I'd say mostly positive, though...", no retry loop, no
  parser.
- **Calibrated probabilities**, so a caller can set a confidence floor and fall
  back rather than acting on a coin flip dressed as an answer.
- **Deterministic.** No sampling, no temperature, no seed to pin.
- **Two orders of magnitude cheaper.** A ~200 M encoder doing one forward pass
  against a 1.7 B decoder generating tokens sequentially — on a device where the
  chat model is the thing most likely to get the whole service killed.

The benchmark table in the source gist puts GLiNER2 (the legacy large
checkpoint) at 0.795 micro accuracy and ~93 ms against a proprietary baseline's
0.974 and ~302 ms on a 78-case suite, with GLiNER2.5-multi better calibrated
than that baseline on DAIR Emotion (Brier 0.668 against 0.846). Those numbers
are quoted from the gist, on hardware it does not name, and are not a phone. The
accuracy gap is real and worth knowing: this is a cheaper, more constrained
model, not a better one.

## The contract

```json
POST /v1/classify
{
  "text": "すみません、もう一度言ってください。",
  "schema": {
    "register":  { "type": "choice", "labels": ["casual", "polite", "formal"] },
    "difficulty":{ "type": "choice", "labels": ["n5","n4","n3","n2","n1"] },
    "is_question":{ "type": "choice", "labels": ["yes","no"] }
  }
}
```

```json
{
  "register":   { "label": "polite", "score": 0.91,
                  "scores": { "casual": 0.04, "polite": 0.91, "formal": 0.05 } },
  "difficulty": { "label": "n5", "score": 0.62, "scores": { "...": 0 } },
  "is_question":{ "label": "no", "score": 0.88, "scores": { "...": 0 } }
}
```

Full distributions are always returned, not just the argmax. A caller that wants
a confidence floor needs the runner-up to decide whether the top answer is worth
anything, and recomputing it means a second forward pass.

`type` covers `choice` (single label), `multi` (independent labels), and
`entities` (spans). `score` is a first-class field, not an extra.

The schema is defined **per request**, not per deployment. That is the whole
appeal — a new question costs a JSON object, not a fine-tune — and it is also
why this is the one service whose input must be validated rather than forwarded:
label counts, text length against the token budget, and schema depth all need
bounds before they reach the model.

## Running it on Android

ONNX Runtime's Android AAR, in-process, no separate binary. The export path is
the work:

1. Export each checkpoint to ONNX. The gist mentions a Rust engine,
   `gliner25-rs`, which implies a supported export; I could not confirm it
   independently and it needs checking before this is planned around.
2. Quantise **statically**, with calibration data. Dynamic int8 is the setting
   that silently costs accuracy on encoders; static with domain calibration
   closes most of the gap to fp16 at half the memory.
3. Verify every operator has an ONNX Runtime Android kernel before trusting the
   size win. Missing kernels fall back to fp32 and the compression evaporates —
   a documented failure mode for some architectures on Android.

Step 3 is the risk. A DeBERTa encoder is a much more ordinary graph than the
convolutional backbones where this usually bites, but "ordinary" is not
"verified", and the first milestone is an export that runs on-device at all,
before any of this is designed around.

## What it might be for

Marked as proposals. None of this is decided, and the classifier is worth having
before any of them is.

- **Register detection** — restoring something like the casual/polite
  distinction that was dropped from the local path, but as a *label on an
  existing sentence* rather than a second generation the small model has to get
  right.
- **Difficulty tagging** for ordering a review queue, where today nothing knows
  whether a sentence is N5 or N1.
- **Routing** — deciding whether an utterance needs the chat model at all.
- **Grading a shadowing attempt** as a classification over the diff, rather than
  a string comparison that counts every kana equally.

The honest summary: this is a capability looking for its first use, and it is
cheap enough that that is a reasonable thing to build.
