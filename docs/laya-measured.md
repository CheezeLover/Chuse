# Measured behaviour of Laya-Q8_0.gguf

Produced by `tools/decision_probe.cpp`, which runs the real model on a workstation using the
same `chuse_decision.cpp` the app uses. Regenerate with:

```bash
./scripts/fetch-llama.sh
cmake -B /tmp/probe -S app/src/main/cpp -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build /tmp/probe --target decision_probe noul_bias_probe noul_order_probe \
                             noul_wording_probe offset_probe signal_probe -j
/tmp/probe/decision_probe app/src/main/assets/model.gguf
/tmp/probe/noul_bias_probe app/src/main/assets/model.gguf
```

Each probe answers one question the others cannot:

| Probe | Question it settles |
|---|---|
| `decision_probe` | What do the prompts and scores look like? |
| `noul_bias_probe` | Is there a real yes/no bias, and does wording move it? |
| `noul_order_probe` | Are the two yes/no columns swapped? (They are not.) |
| `noul_wording_probe` | How much does the option wording matter? |
| `offset_probe` | Which value of the embeddings row carries the score? |
| `signal_probe` | Do state and instructions actually move the scores? |

`offset_probe` reported byte-identical embeddings for different questions, which `signal_probe`
then showed was an artefact of that probe. Both are kept: the first is what raised the question
and the second is what answered it.

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

## The negative bias, and what it actually was

Reported twice, and the first two explanations were both wrong. The record matters more than
the conclusion, because the wrong answers were all plausible.

**Not a column swap.** llama.cpp builds noul options as `[false, true]` for Laya while the app
rendered `[true, false]`, which looks exactly like the symptom. `tools/noul_order_probe.cpp`
renders every case both ways: both orderings score exactly 6/9, which is chance. Swapping would
have flipped the sign and changed nothing.

**Not the option wording, either — though wording does matter.** The GGUF's fallback is
`yes, the statement holds` versus `no, the statement does not hold`, which is not symmetric. On
a balanced twenty-case set the wording makes a small difference and none of it is the main
effect:

| Option wording | Correct | Mean P(true) when true | Mean P(true) when false |
|---|---:|---:|---:|
| Bare keys | 18/20 | 0.711 | 0.235 |
| `true` / `false` | 18/20 | 0.711 | 0.235 |
| `yes` / `no` | 17/20 | 0.712 | 0.200 |
| GGUF long fallback | 17/20 | 0.711 | 0.190 |

Bare keys are kept, but they are worth one or two cases, not the effect being chased.

**A measurement artefact nearly sent this the wrong way too.** `offset_probe` reported two
opposite questions returning byte-identical embeddings. That is impossible if the read position
carries the answer, so it was worth chasing: `signal_probe` showed the scores do move correctly
with both state and instructions, and repeat identically for identical input. The identical rows
were an artefact of that probe, not a property of the model. Recorded here because the instinct
on seeing impossible output is to trust it and look for a bug elsewhere.

**The actual cause: the state was optional when it should not have been.** An earlier
measurement appeared to show that a question alone still answered usefully, and the interface
was simplified to one field on that basis. On a balanced set it does not:

| Input | Correct | Mean P(true) when true | Mean P(true) when false | Gap |
|---|---:|---:|---:|---:|
| Question **and** state | **18/20** | 0.711 | 0.235 | +0.476 |
| Question alone | 12/20 | 0.475 | 0.364 | +0.111 |

Without a state every probability sits near a coin flip and the gap nearly vanishes. That reads
to a user as a lean towards false — a model that is uncertain and quiet looks like a model that
is confident and wrong. Upstream agrees: `/decide` rejects a request with no `state`.

The state is now required, the engine rejects an empty one, and the interface asks for both
fields with the context one always visible rather than hidden behind a toggle.

### What remains weak

One case fails under every wording: "Does ice melt at room temperature?" against the state
"Ice does not melt at room temperature." The model answers True at 0.85, because the
instruction and the state contradict each other and it appears to weight the instruction's
polarity over the negated state. That looks like a limitation of the checkpoint rather than a
prompt defect, and it is left visible rather than papered over.

## Answers

### The state is required

| Input | true | false |
|---|---|---|
| "Is the sky blue during the day?" + "The sky is green." | 0.070 | **0.930** |
| "Is the sky blue during the day?" + "The sky is blue." | **0.618** | 0.382 |

With a state the model is sharp in both directions. Without one it answers, but at near chance
— see [the bias section](#the-negative-bias-and-what-it-actually-was).

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