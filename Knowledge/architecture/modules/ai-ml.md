# Modules: mlkit / opusmt / whisper (on-device AI/ML)

On-device translation and speech recognition used by the `translate` and `subgen` features.

## mlkit — `me.aap.fermata.mlkit` (1 file)
- Backed by `com.google.mlkit:translate` 17.0.3. Provides a translation `Transcriptor`/translator
  implementation using Google ML Kit's on-device models.
- The base manifest **removes** the ML Kit init provider (`tools:node="remove"` on
  `MlKitInitProvider` in `fermata/src/main/AndroidManifest.xml`) so it is only active once the
  `mlkit` dynamic module is installed.

## opusmt — `me.aap.fermata.opusmt` (3 files)
Neural machine translation via **ONNX Runtime** (`onnxruntime-android` 1.30.0):
- `OpusMtTranslateAddon.java` (10 KB) — addon entry; downloads/selects OPUS-MT language-pair models,
  exposes a translator.
- `OpusMtModel.java` — wraps an ONNX encoder/decoder session for a language pair.
- `SentencePieceTokenizer.java` — SentencePiece tokenization/detokenization for model I/O.

## whisper — `me.aap.fermata.whisper` (2 Java files + 1 native C++)
- Speech-to-text using a **Whisper** model via ONNX Runtime; provides a `SubGenAddon.Transcriptor`
  consumed by `SpeechRecognitionService` (`fermata/.../service/SpeechRecognitionService.java`).
- Native layer: `modules/whisper/src/main/cpp/whisper_jni.cpp` — the only C/C++ source in the
  repository; a JNI bridge to the native whisper inference. Built via the NDK
  (`ndkVersion 29.0.14206865`, ABI filters `arm64-v8a`/`armeabi-v7a`/`x86_64`).

## How they plug in
- `TranslateAddon` / `SubGenAddon` (`fermata/.../addon`) are the façades. `SubGenAddon.depends =
  "exoplayer,translate"` (root `build.gradle`), so enabling subtitle generation pulls ExoPlayer and
  the translate chain.
- `SpeechRecognitionService` obtains a `Transcriptor` via
  `AddonManager.get().getOrInstallAddon(SubGenAddon.class).then(sg -> sg.getTranscriptor(prefs))`,
  records 16 kHz PCM, and streams chunks to the transcriptor.

## Grounding notes
- `[UNVERIFIED]` Exact model download URLs/hosts for OPUS-MT/Whisper were not traced in this pass;
  they are fetched at runtime into app storage.
