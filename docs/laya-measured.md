# Measured behaviour of Laya-Q8_0.gguf

Produced by `tools/decision_probe.cpp`, which runs the real model on a workstation using the
same `chuse_decision.cpp` the app uses. Regenerate with:

```bash
./scripts/fetch-llama.sh
cmake -B /tmp/probe -S app/src/main/cpp -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build /tmp/probe --target decision_probe -j
/tmp/probe/decision_probe app/src/main/assets/model.gguf
```

## Why this file exists

Four bugs in the decision path were invisible to Kotlin unit tests and obvious the moment the
model actually ran:

1. **`llama_decode` on an encoder.** ModernBERT refuses it — *"cannot decode batches with this
   context (calling encode() instead)"* — and aborts. It needs `llama_encode`.
2. **`batch.seq_id[0][i]` overwrote one shared row.** `seq_id` is an array of per-token rows,
   so this left most of them uninitialised and produced garbage sequence ids. Every token needs
   `batch.seq_id[i][0]`.
3. **A negated size query read as an error.** `llama_tokenize` returns `-n` for the size, not
   an overflow sentinel, so checking `if (upper < 0)` made every prompt look untokenisable.
4. **Column 0 was read for every question type.** The embeddings output carries one column per
   type (`CHOICE=0, SCORE=1, NOUL=2`). Reading column 0 always scored yes/no questions against
   the *choice* head — the worst kind of bug, because the output looked like a real
   probability rather than an error.

## Answers

### A question alone, with no separate statement, works

The `state` segment of the prompt may be empty. The model still returns a usable
distribution, which is what makes a one-field UI possible:

| Input | true | false |
|---|---|---|
| "Is the sky blue during the day?" + "The sky is green." | 0.070 | **0.930** |
| "Is the sky blue during the day?" + *(nothing)* | **0.700** | 0.300 |

With a stated fact the model is sharp and correctly confident. With only the question it
still leans, less decisively — which is the expected behaviour, since there is less to judge.

### Yes/no with a stated fact

| Input | true | false |
|---|---|---|
| "The sky is blue." | 0.473 | 0.527 |
| "The sky is green." | 0.070 | 0.930 |

**An obviously-true statement scores a coin flip.** The model is reliable on false and weak
on true, so a yes/no UI that promises a verdict will look broken roughly half the time. This
is the strongest argument for showing the probability and the two confidence figures rather
than a bare True/False.

### Ranking

| Input | restore | reinstall | buy |
|---|---|---|---|
| "The laptop will not boot after an update." | 0.155 | **0.799** | 0.046 |
| *(no statement)* | 0.428 | 0.318 | 0.254 |

With a statement the winner is decisive and sensible. Without one the ranking flattens to
little better than chance.

### Scoring

| Input | negligible | minor | moderate | major | catastrophic |
|---|---|---|---|---|---|
| "Production database deleted." | 0.028 | 0.242 | 0.297 | **0.347** | 0.086 |
| "A typo in a comment." | 0.114 | **0.621** | 0.223 | 0.031 | 0.010 |

Ordering is correct in both directions and the distributions separate cleanly. Scores need no
extra scale input to be useful, though the model hedges toward the middle — a deleted database
reads as *major*, not *catastrophic*.

## Consequence for the interface

A single input can drive all three modes. The `state` field is optional rather than removed:

- **Yes/no** — one field. The question text becomes the instruction; the state is empty.
- **Choice** — a question plus the options. Without a separate statement the ranking is weak,
  so the field is optional but worth keeping.
- **Score** — one field. A scale still helps: the model's own level labels are read
  literally, so generic levels work without being defined.

The honest caveat: with no statement, yes/no is close to a coin flip. Showing a percentage
with the two confidence readings is not decoration here — it is the difference between a model
that appears broken and one that appears calibrated.