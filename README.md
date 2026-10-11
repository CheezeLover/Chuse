# Chuse

An Android app that asks Laya — a small decision model — one question at a time, and shows
the answer with how sure the model is. Three modes, no conversation, no history.

The model runs entirely on the device. The app declares no permissions and opens no
sockets; there is nothing to send and nothing to store.

| Mode | Asks | Returns |
|---|---|---|
| **Yes or no** | a statement and a question | P(true), and which way it leans |
| **Rank choices** | a question and two or more options | every option ranked, with probabilities |
| **Score it** | a question and a scale you define | an expected level, with the distribution behind it |

## Status

Everything is built and the tests pass, with one gap that needs an artifact I could not
reach: **the Laya GGUF is not in this repository.** See [Parity](#parity-what-is-and-isnt-verified).

Until it is added, the app installs and runs and answers through a stand-in that produces
stable, meaningless numbers. The home screen says so, permanently. Dropping the model in is
one file and a rebuild.

## Building

Requires JDK 17, the Android SDK (platform 35, build-tools 35.0.1, NDK 27, CMake 3.22.1).

```bash
./gradlew :core:test              # engine + parity tests
./gradlew :app:testDebugUnitTest  # repository and model lifecycle
./gradlew :app:assembleDebug      # APK
./gradlew :app:assembleRelease    # shrunk APK
```

The live parity check is opt-in, since the service is on a LAN:

```bash
./gradlew :core:test -Dchuse.shim=http://192.168.3.129:8081
```

## Layout

```
core/     Kotlin engine: question types, scoring, softmax, derived figures. No Android.
app/
  src/main/cpp/         llama.cpp + the JNI bridge
  src/main/java/com/chuse/
    core-ish glue       data/ (model lifecycle), inference/ (JNI), ui/ (Compose)
```

`core` is a plain JVM module so the arithmetic can be tested — and checked against the live
service — without an emulator.

## How an answer is produced

1. `LayaEngine` builds a prompt from the state and the question, and a list of candidate
   labels: `["true", "false"]`, the option names, or the scale levels.
2. `LlamaScorer` hands that to llama.cpp, which returns one raw log-probability per
   candidate. It scores by teacher forcing, not sampling: the prompt is decoded once and its
   KV cache reused, so every candidate is scored on the same footing. Sampling would give one
   arbitrary answer; ranking needs all of them.
3. `Math.softmax` turns the scores into a distribution, and the reported figures follow.

### The two confidence numbers

The shim returns both, and they are different questions:

- **`answer_confidence`** — the probability the chosen answer is correct. For a two-way
  question this is `max(p, 1 - p)`.
- **`confidence`** — one minus normalised entropy: how far the whole distribution is from a
  coin flip. A decisive answer on three options scores *lower* here than an equally decisive
  answer on two, because there was more room to be uncertain.

Showing only the first would overstate what the model said, so the UI shows both.

### A note on `score`

`score` is an **expectation**, not a label: the probability-weighted mean of the scale levels.
A result of 2.4 on a five-level scale means the model spread its mass around level 2, and the
UI shows the distribution alongside it so a hedged answer cannot be read as a firm one.

## Parity: what is and isn't verified

The engine's arithmetic was reverse-engineered from `laya-shim 0.4.1` by probing its
`/decide` endpoint and reproducing its numbers exactly. `confidence` being normalised entropy
rather than the top probability was recovered this way, and `ParityTest` locks it in against
recorded responses.

**Verified:**

- The derived arithmetic matches the shim on every recorded response (`ParityTest`).
- The recorded responses still match the live service (`-Dchuse.shim=…`).
- The JNI bridge compiles against llama.cpp `b4600` for arm64-v8a, and its exported symbols
  match the Kotlin declarations.
- Model lifecycle, validation and both scoring paths (`LayaRepositoryTest`).

**Not verified, and why:**

- **The model's own probabilities.** These need the GGUF and the real shim source. Until the
  GGUF is dropped in, the native path has never been executed — it compiles and its symbols
  are right, which is as far as this machine can take it.
- **The exact prompt wording.** `PromptFormat` is a single object, written to be a faithful
  reconstruction, but the shim's real phrasing is unknown. It is isolated there precisely so
  it can be replaced with one edit when the source is available; nothing downstream depends
  on the wording.
- **On-device speed.** A one-shot decision needs a single forward pass over a few hundred
  tokens, so it should feel instant, but that has not been measured on real hardware.

## Chinese phones

- No Google Play Services, no Firebase, no analytics — nothing that has to be fetched from a
  Google endpoint at runtime.
- No permissions at all in the manifest, which is what keeps Chinese OEM permission prompts
  quiet.
- System fonts, so Chinese and Latin render from one family. A bundled display font would look
  better in English and fall back per-glyph in Chinese, visibly breaking headline rhythm.
- Fully offline after install.
- Material 3, so it follows the system look on MIUI, HarmonyOS, ColorOS and OriginOS.
- arm64-v8a, minSdk 26.