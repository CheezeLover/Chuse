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
| `noul_label_probe` | Does the option *label* decide the answer, as upstream issue #156 reports? (No.) |
| `offset_probe` | Which value of the embeddings row carries the score? |
| `signal_probe` | Do state and instructions actually move the scores? |
| `derived_state_real_probe` | Does the derived situation help when the app really uses it? (No.) |

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

**The cause: the state was carrying almost all the signal, and the question very little.**
On a balanced set:

| Input | Correct | Mean P(true) when true | Mean P(true) when false | Gap |
|---|---:|---:|---:|---:|
| Question **and** state | **18/20** | 0.711 | 0.235 | +0.476 |
| Question alone | 12/20 | 0.475 | 0.364 | +0.111 |

Without a state every probability sits near a coin flip and the gap nearly vanishes. That reads
to a user as a lean towards false — a model that is uncertain and quiet looks like a model that
is confident and wrong.

**No substitute recovers it.** Making the field optional only helps if something can stand in,
and nothing does (`state_fallback_probe`):

| State sent | Correct | Gap |
|---|---:|---:|
| The real state | 18/20 | +0.487 |
| Empty | 12/20 | +0.104 |
| A generic placeholder | 12/20 | +0.104 |
| The question, repeated | 10/20 | +0.044 |

Repeating the question is the *worst* option, which is the informative result: the model wants
facts, not a reformulated question. The state is therefore optional in the interface but not
substitutable, and an answer given without one carries a note saying what that costs.

## The derived situation makes it worse, not better

`QuestionToStatement.derive` fills an empty situation from the question, and
`derived_state_probe` measured it at 16/20 against 12/20 empty. **That number is real and it
reproduces — but it is not measuring the rule.** The probe now runs `derive()` for real
(a port of the Kotlin rules lives in the probe) and prints it beside what was scored:

| Question | Rule actually writes | The test table says it writes | Scored with |
|---|---|---|---|
| "Is water wet?" (answer false) | `Water is wet.` | `Water is dry.` | `Water is dry.` |
| "Is the meeting at 3pm?" (false) | `The meeting is at 3pm.` | `The meeting is at 4pm.` | `The meeting is at 4pm.` |
| "Can a phone run a model offline?" (false) | *(declined)* | `A phone cannot run a model offline.` | `A phone cannot run…` |
| "Is the Earth flat?" (false) | `The Earth is flat.` | `The Earth is flat.` | `The Earth is flat.` |

On **eight of the ten false cases** the table supplies a hand-written statement that is the
answer — `Water is dry` for a question whose rule output is `Water is wet` — and the model is
scored against that. Only two false cases carry the rule's own output, and those are the two
that assert the falsehood. The 16/20 is therefore ten true cases answered by the rule plus
roughly six false cases answered by a correct answer written into the test, which the rule has
no way to produce. `derived_state_real_probe` measures the rule as the app uses it, and gets
10/20 against 14/20 for an empty field.

`derived_state_real_probe` measures it as the app really sends it, on twenty questions with
determinate answers, ten true and ten false:

| Situation | Correct | P(true) when true | P(true) when false | Gap |
|---|---:|---:|---:|---:|
| Left empty | **14/20** | 0.580 | 0.319 | +0.260 |
| Derived from the question | 10/20 | 0.807 | **0.748** | +0.059 |
| Neutral filler, asserts nothing | 11/20 | 0.674 | 0.627 | **+0.048** |
| A situation stating the fact | 19/20 | 0.811 | 0.164 | +0.647 |

The derived row scores exactly the ten true cases and none of the ten false ones, because on
every false case it writes down the falsehood and the model — which takes its signal from the
situation, as the table above already shows — believes it:

| Question | Answer | Empty | Derived |
|---|---|---:|---:|
| "Is the Earth flat?" | false | 0.44 | **0.79** |
| "Is two plus two equal to five?" | false | 0.34 | **0.84** |
| "Is the Sun a planet?" | false | 0.18 | **0.68** |

So the rule trades a lean towards false for a harder lean towards true. It does not recover
the missing fact, because the missing fact is the world, and the only thing available to
synthesise from is the question — which carries the polarity the answer is being asked about.
There is no derivation that helps here. An empty situation at 14/20 is both more accurate and
more honestly calibrated than a derived one.

### A second model does not fix this either

The obvious next proposal is to use a small language model to write the situation instead of a
rule. It has two possible behaviours and both are measured above.

Write the natural continuation and it is the rule again — "Is the Earth flat?" becomes "The
Earth is flat." — for 10/20 and a lean towards true. Instructed not to assert anything and it
produces a topical sentence: "The shape of the Earth." That is the **worse** of the two, at
11/20 with a gap of **+0.048**, the smallest separation in the table. A situation with no fact
in it is worse than no situation, because the model now reads a state and expects to conclude
from it; it drifts to the affirmative (0.63 against 0.67) instead of answering from the weak
signal the bare question gave (0.32 against 0.58).

The only way a second model scores well is by writing down the fact, and that is the last row:
19/20. That number is the filler's world knowledge passing through Laya, not Laya deciding. The
app would be a chatbot narrating an answer it did not reach, and it would do so strictly worse
than asking that model directly.

(The 14/20 and the 12/20 above are different case sets: this one is built from questions whose
answer is a determinate fact, so the model's natural lean towards false happens to pay off on
the ten questions phrased as falsehoods. The gap columns are the comparable part.)

### The option labels are not the problem either

Upstream [issue #156](https://github.com/NandhaKishorM/laya/issues/156) measured that a `noul`
whose options are labelled with boolean words — `true:`/`false:`, `yes:`/`no:` — answers with
the **negative** label in both polarities at confidence 1.0000, and that neutral labels
(`A`/`B`, `positive`/`negative`) fix it. That is the most plausible story for a lean towards
false, so it was worth testing here. `noul_label_probe` varies the label and holds everything
else fixed.

Every earlier probe missed it for a structural reason: `noul_bias_probe` and
`noul_wording_probe` both pass the keys `{"true","false"}` and vary only the *descriptions*,
so even their "bare keys" variant renders `true: true` / `false: false`. The boolean word sits
in the label slot in every variant they ran. The label slot had never been varied.

It does not reproduce. On twenty-four judgement questions — the shape the app ships, ten true
and ten false — the current labelling is the best of the eight variants tried:

| Option labels | Correct | P(true) when true | P(true) when false | Gap |
|---|---:|---:|---:|---:|
| `true`/`false`, no description (current) | **20/24** | 0.52 | 0.20 | +0.32 |
| `A`/`B` + `yes`/`no` | 19/24 | 0.63 | 0.24 | +0.39 |
| `A`/`B`, no description | 16/24 | | | +0.08 |
| `positive`/`negative` | 11/24 | | | −0.13 |
| `1`/`2`, no description | 12/24 | | | +0.03 |

A first pass over six review-sentiment cases showed the opposite — 4/6 with boolean labels,
6/6 with neutral ones — and it was wrong. Six cases is inside the noise; the same variants on
twenty-four reverse. The lesson matches `offset_probe` above: on this model a small clean-looking
result is the thing most likely to be an artefact.

What survives is the original finding. The lean is not in the labels and not in the wording. It
is what an under-specified question looks like: with a situation the model separates cleanly
(gap +0.48 factual, +0.32 judgement), and without one every probability sits near a coin flip,
slightly below it.

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