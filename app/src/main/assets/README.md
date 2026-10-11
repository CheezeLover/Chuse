# Model

Place the Laya GGUF here as **`model.gguf`**.

## Which file

`Laya-Q8_0.gguf` from [ggml-org/Laya-GGUF](https://huggingface.co/ggml-org/Laya-GGUF):

```bash
curl -L -o app/src/main/assets/model.gguf \
  https://huggingface.co/ggml-org/Laya-GGUF/resolve/main/Laya-Q8_0.gguf
```

- 429 MB, sha256 `c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2`
- Architecture `modern-bert`, decision type `laya`, 201 tensors
- Needs **llama.cpp v0.6.0 or later** — the `modern-bert` architecture and the typed
  decision API arrived in PR #29818, merged 2026-10-02

The file is gitignored. It is large, and it is added per-machine rather than committed.

### Not this one

`mys/laya-GGUF` is a *different* model in a *different* format. It is compiled by
**ggmlc**, not llama.cpp, and its card states plainly that loading it in llama.cpp will
fail. It also requires the `laya` CLI, which ships no Android build.

### English only

This checkpoint is English. Its authors note it "does not degrade gracefully off English
(it stays confident while collapsing)" — non-English input returns confident nonsense
rather than admitting ignorance. The interface is therefore English-only.

For multilingual use the relevant build is `mys/laya-multilingual-GGUF` (mmBERT, 100+
languages), but that is the ggmlc format, so it needs a different runtime and this app
would not load it.

## Packaging

The build marks `*.gguf` as `noCompress`, so the APK stores the model uncompressed and the
app copies it to its cache directory on first launch rather than inflating it in place.
Expect the APK to grow by roughly the model size.

Check the result before distributing:

```bash
./gradlew :app:assembleRelease
ls -lh app/build/outputs/apk/release/
```

## Without a model

A build with no `model.gguf` still installs and runs, answering through `StandInScorer` —
stable, meaningless numbers that exist so the interface can be exercised. Those answers
are labelled as such on the home screen, so a demo cannot be mistaken for the model.