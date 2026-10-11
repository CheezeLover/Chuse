# Model

Drop the Laya GGUF here as **`model.gguf`** and rebuild.

The build marks `*.gguf` as `noCompress`, so the APK stores it uncompressed and the app
copies it out to its cache directory on first launch rather than inflating it in place.

## What is expected

- A single-file GGUF (not sharded), named exactly `model.gguf`.
- arm64-v8a. The build produces x86_64 too, for emulator work, but that covers development
  rather than shipping.
- A quantisation that suits phone RAM. Laya is small enough on the Quadro P400 that it is
  almost certainly ≤ 2B parameters; Q4_K_M is the sensible default, Q8 if there is headroom.

## Without a model

A build with no `model.gguf` still installs and runs. It answers through `StandInScorer`,
which hashes the prompt and the candidates to produce plausible, stable-looking numbers so
the interface can be exercised end to end.

Those answers are not judgements. The app labels them on the home screen and in
`ModelStatus.Ready.isStandIn`, so a demo can never be mistaken for the model working.

## Size

The model dominates the APK. Check the size before distributing:

```bash
./gradlew :app:assembleRelease
ls -lh app/build/outputs/apk/release/
```