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

Everything is built and all 37 tests pass. The GGUF is **not committed** — 429 MB, and
gitignored — so drop it in before building:

```bash
mkdir -p app/src/main/assets
curl -L -o app/src/main/assets/model.gguf \
  https://huggingface.co/ggml-org/Laya-GGUF/resolve/main/Laya-Q8_0.gguf
```

Without it the app still installs and runs, answering through a stand-in that produces
stable, meaningless numbers. The home screen says so, permanently, so a demo can never be
mistaken for the model working.

The one thing this repository cannot tell you is whether the model's scores are *right* — see
[Parity](#parity-what-is-and-isnt-verified). The decision path compiles and its contract is
checked against upstream's own implementation, but it has never been executed. That needs a
device.

## Building

Requires JDK 17, the Android SDK (platform 35, build-tools 35.0.1, NDK 27, CMake 3.22.1).

llama.cpp is **not** committed — it is tens of megabytes of vendored source. Fetch the
pinned revision first, or the native build fails with a missing `CMakeLists.txt`:

```bash
./scripts/fetch-llama.sh   # pinned to v0.6.0, checksum-verified
```

Then:

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

Laya is not a language model that writes text. It is a classifier that answers a typed
question in **one encoder pass**, with no tokens generated and nothing sampled.

1. `LayaEngine` turns the question into an ordered option list — `["true", "false"]`, the
   choice labels, or the scale levels keyed by index — and asks a `CandidateScorer` to score
   them.
2. The JNI bridge renders the prompt exactly as the GGUF's own `systemone` template
   specifies, truncates the question and options to the model's `max_head_tokens`, and runs a
   single forward pass with embeddings enabled. Each option's score is one float read from the
   embeddings output at that option's `[MASK]` marker.
3. Those raw scores go through a softmax **divided by a temperature** the model calibrates per
   question type and option count, and the reported figures follow.

The temperature is not a tuning knob. It lives in the GGUF metadata and the raw scores are
only comparable once it is applied — skipping it, or defaulting to 1.0, produces
probabilities that look plausible and are wrong, which is worse than an error because nothing
downstream can tell. `LayaTemperatures` carries the values shipped in `Laya-Q8_0.gguf`; the
native layer reads the real ones at load time, so a different checkpoint needs no code change.

### The two confidence numbers

They are different questions, and the UI labels them separately:

- **`answer_confidence`** — the probability the chosen answer is correct. For a two-way
  question this is `max(p, 1 - p)`.
- **`confidence`** — how far the distribution is from a coin flip, using the TypeSafe
  formula. For `choice` that is `(p_max - 1/n) / (1 - 1/n)`; for `score` it is the mean
  distance from the modal level against the uniform baseline.

Worth knowing: `laya-shim` reports `confidence` as **one minus normalised entropy**, which is
a different quantity that happens to share the name — 0.842 against TypeSafe's 0.954 for the
same `0.977 / 0.023` split. Both are implemented and both are tested, because conflating them
would hide that. The app uses the TypeSafe formula, since that is what llama.cpp returns.

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
- The JNI bridge compiles against llama.cpp `v0.6.0` for arm64-v8a and x86_64, and its three
  exported symbols match the Kotlin declarations exactly.
- The GGUF's metadata and `systemone` template parse under llama.cpp's own reader.
- Model lifecycle, validation and both scoring paths (`LayaRepositoryTest`).
- The model bundles into the APK stored uncompressed, byte-identical to the source.

**Not verified, and why:**

- **The model's own scores.** The decision path compiles and its contract is checked against
  upstream's `server-decision.cpp`, but it has never been *executed* — that needs a device or
  an emulator. Whether the rendered prompt and marker layout produce sensible probabilities is
  the one thing this repo cannot tell you.
- **On-device speed.** A one-shot decision is a single forward pass over at most a couple of
  hundred tokens, so it should feel instant, but that has not been measured on real hardware.

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