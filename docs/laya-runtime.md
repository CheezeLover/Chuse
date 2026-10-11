# Laya on Android — implementation notes

Facts verified directly against `ggml-org/Laya-GGUF` and `llama.cpp v0.6.0`, using
llama.cpp's own metadata reader rather than anything reconstructed. This file records what
was established and how, because most of it is not discoverable from the app code.

## The model

| Property | Value |
|---|---|
| Architecture | `modern-bert` |
| Decision type | `laya` |
| `n_embd_out` | 3 |
| `block_count` | 2 |
| `max_head_tokens` | 192 |
| mask / sep token | 50284 / 50282 |
| Size (Q8_0) | 429 MB |
| sha256 | `c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2` |

## How a decision is computed

Not autoregressive. One encoder pass, no tokens generated:

1. Build the prompt from the GGUF's `tokenizer.chat_template.systemone`:
   `[CLS]{type} question: {instructions}[SEP]` then `[MASK] {option}[SEP]` per option,
   then `{state}[SEP]`.
2. Truncate question and options to `max_head_tokens` (192), shrinking options evenly
   when they would overflow. The model was trained this way, so the layout is not free.
3. Run the encoder with embeddings enabled (`llama_set_embeddings`).
4. Read `embeddings[column]` at each option's marker position — one float per option.
   For Laya there is no pointer token, so the score is a single float, not a dot product.
5. Softmax the scores **divided by a temperature** that depends on the question type and
   the number of options. Without this the probabilities are not comparable.
6. Apply the TypeSafe confidence formulas.

The template is reimplemented in C++ rather than run through a Jinja engine: it is ~1.5 KB
of known text with no branching beyond the three question types, and an interpreter for it
would cost far more than the few lines that render it.

A `noul` question keeps the keys `"true"` and `"false"` even when the user supplies their own
wording — the model's two output columns mean those things positionally, and the criteria
are the description, not the key.

## Temperatures (from the model)

Selected by `temperature.<type>.<bucket>`, falling back to `temperature.<type>`:

| Bucket | choice | score | noul |
|---|---:|---:|---:|
| `.2` | 1.906356 | — | 1.983400 |
| `.3_5` | 1.760152 | 1.251430 | — |
| `.6_10` | 1.000016 | — | — |
| `.11` | 0.100583 | — | — |
| (default) | 1.636903 | 1.251430 | 1.983400 |

Buckets: `<=2 → 2`, `<=5 → 3_5`, `<=10 → 6_10`, else `11`.

## Confidence formulas

These are the TypeSafe formulas, **not** normalised entropy:

- `choice`: `(p_max − 1/n) / (1 − 1/n)`
- `score`: `1 − Σ p_i·|i − mode| / Σ |i − (n−1)/2| / n`

The app's `Math.confidence()` implements normalised entropy, which is what the
**laya-shim** reports. The two are genuinely different: for `0.977 / 0.023`, entropy gives
`0.842` and the TypeSafe formula gives `0.954`. They must not be conflated.

`answer_confidence` is not a llama.cpp concept at all — it is shim-only. For a two-way
question it equals `max(p, 1−p)`, which coincides with the TypeSafe choice confidence only
when the uniform denominator matches.

## What is still unverified

The native decision path has never been executed on a device — that needs real hardware or
an emulator. What *is* verified:

- llama.cpp v0.6.0 cross-compiles for arm64-v8a and x86_64, and the three JNI entry points
  are exported under exactly the names Kotlin's `external` declarations resolve to
  (`Java_com_chuse_inference_LlamaBridge_native{Load,Free,Score}`).
- The GGUF's metadata and template parse correctly under llama.cpp's own reader on the host.
- Both test suites pass, including live parity against `laya-shim`.

Not yet verified: that the rendered prompt produces sensible scores, and that the marker
layout matches what the model was trained on. Both are only observable by running it.

## Where the pieces live

| Concern | File |
|---|---|
| Prompt rendering, truncation, marker read | `app/src/main/cpp/chuse_jni.cpp` |
| Option ordering, temperature, softmax, confidence | `core/src/main/kotlin/com/chuse/core/` |
| Model loading and lifecycle | `app/src/main/java/com/chuse/data/LayaRepository.kt` |
| JNI surface | `app/src/main/java/com/chuse/inference/LlamaBridge.kt` |

The engine refuses at load time anything that is not a `modern-bert` model with decision type
`laya`, rather than loading it and returning meaningless numbers later.