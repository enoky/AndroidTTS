# AndroidTTS

A system text-to-speech engine for Android that runs **Gradium Phonon 31M** entirely on the phone.
Once installed and chosen as the preferred engine, every app that speaks through Android's
`TextToSpeech` uses it: TalkBack, e-book readers, navigation, Select to Speak and so on.

- **Model:** [`gradium/phonon-31m-en`](https://huggingface.co/gradium/phonon-31m-en), English,
  Apache 2.0, pinned to commit `8c64b55e`. The app downloads it once from Hugging Face. It is not
  bundled in the APK.
- **Runtime:** Phonon's Rust library `libptts_ffi.so`, built from
  [gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts) at commit `e5110afd`, running on
  the CPU. Audio streams to Android as it is generated, at 24 kHz.
- **Privacy:** the internet permission is used only for that one model download. Text is never
  sent anywhere.

## Install on a Galaxy S21 Ultra

1. **Get the APK.** On GitHub, open the repo's **Actions** tab, then the latest green **Android**
   run, and download the **AndroidTTS-apk** artifact. It is a zip: open it and take
   `AndroidTTS-<n>.apk`. If a release has been tagged, the **Releases** page has the APK directly.
2. **Install it.** Open the APK on the phone. Android asks you to allow installs from the app
   you opened it with (My Files or Chrome); allow it, then tap Install.
3. **Download the voice.** Open **AndroidTTS** and tap **Download the voice model**. This is a
   one-time download of a few tens of megabytes. When the status says *Ready*, tap
   **Play a sample**.
4. **Make it the system voice.** Tap **Open the system text-to-speech settings**, or go to
   Settings → General management → Text-to-speech output (search Settings for
   "text-to-speech" if One UI names it differently). Set **Preferred engine** to AndroidTTS
   and the language to English. For TalkBack, also check TalkBack → Settings →
   Text-to-speech settings.

   With USB debugging, this does the same:
   `adb shell settings put secure tts_default_synth io.github.enoky.androidtts`

### Notes for the S21 Ultra

- Depending on the region, the S21 Ultra has a Snapdragon 888 or an Exynos 2100. Both have
  arm64 ARMv8.2 cores with the dotprod and fp16 instructions that Phonon's fast q8 kernels
  need, so the same APK runs at full speed on either.
- The engine uses 2 CPU threads, upstream's measured default for phones that mix fast and slow
  cores. You can change `THREADS` in `Model.kt` if your own measurements favour more.
- Phonon's Snapdragon NPU path (QNN) is not used. It needs a model bundle compiled for each
  chip, has no CPU fallback, and would not run on Exynos units at all.
- Android 11 through 14, everything the S21 has run, is supported (minimum is Android 7.0).

## Limitations

- **English only.** Phonon 31M is the English model. Apps that ask for another language get
  "language not supported", so the system falls back to another engine for those. The
  multilingual Phonon 40M models are CC BY-NC 4.0 (non-commercial) and gated. They can be
  imported with **Import a checkpoint folder** if their license suits you.
- **The speech rate and pitch sliders do nothing yet.** This is an upstream limitation: the
  31M checkpoint has no rate control, so both would need audio processing on the output.
- The APK is built for `arm64-v8a` (phones) and `x86_64` (emulator). 32-bit phones cannot run
  Phonon.

## Build from source

You need JDK 17+, the Android SDK with platform 36, Rust, the Android NDK and cargo-ndk.

```sh
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
scripts/build-native.sh        # fetches xn-ptts at the pinned commit, builds libptts_ffi.so
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

`scripts/build-native.sh` puts the library in `app/src/main/jniLibs/`. The build refuses to
package an APK without it, since such an APK would crash on first use. The Kotlin binding
`PhononTTS.kt` declares the C interface of that same xn-ptts commit, so move both together when
updating.

The debug build installs beside the release build, as "AndroidTTS (debug)".

### CI

`.github/workflows/android.yml` runs on every push. It compiles the Kotlin and runs the unit
tests, builds `libptts_ffi.so`, then builds both APKs and uploads them as the `AndroidTTS-apk`
artifact. Pushing a `v*` tag also publishes the release APK on the Releases page.

### Signing

Android only installs an update over an app signed with the same key. Without a key configured,
CI signs each run's release APK with that run's throwaway debug key. Installing a newer build
then needs an uninstall first, which also deletes the downloaded model. To sign every build with
one key, create it once:

```sh
keytool -genkeypair -v -keystore androidtts.jks -alias androidtts -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 androidtts.jks   # on macOS: base64 -i androidtts.jks
```

Then add four repository secrets under Settings → Secrets and variables → Actions:
`ANDROIDTTS_KEYSTORE_BASE64` (the base64 output), `ANDROIDTTS_KEYSTORE_PASSWORD`,
`ANDROIDTTS_KEY_ALIAS` (`androidtts`) and `ANDROIDTTS_KEY_PASSWORD`. Keep `androidtts.jks` safe
and out of the repo. Local builds read the same names from environment variables, with
`ANDROIDTTS_KEYSTORE` as the path to the file.

## Tests

- `./gradlew testDebugUnitTest`: JVM tests for how the download picks, parses and checks the
  model files.
- `./gradlew connectedDebugAndroidTest`: drives the installed engine through `TextToSpeech` on
  a connected phone or emulator, the way any app would. With the model downloaded in the debug
  app, it writes WAV files and checks that `QUEUE_FLUSH` cuts speech short. Add
  `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, or Gradle uninstalls the
  app afterwards, taking the model with it.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/java/.../PhononService.kt` | The `TextToSpeechService` Android binds to |
| `app/src/main/java/.../Model.kt` | Installs, loads and shares the checkpoint |
| `app/src/main/java/.../ModelDownload.kt` | Downloads Phonon 31M from Hugging Face, verifying sizes and SHA-256 |
| `app/src/main/java/.../SettingsActivity.kt` | The app screen: download, sample, folder import |
| `app/src/main/java/.../DataActivity.kt` | Answers the system settings' data and sample-text queries |
| `app/src/main/java/.../PhononTTS.kt` | JNA binding to `libptts_ffi.so` |
| `scripts/build-native.sh` | Builds `libptts_ffi.so` from xn-ptts at the pinned commit |

## Credits and licenses

This project is MIT licensed (see `LICENSE`). The engine code is adapted from xn-ptts's
`android/tts-engine` by Gradium (MIT OR Apache-2.0). Phonon 31M's weights are Apache 2.0 and are
downloaded from Hugging Face, not distributed here. See `THIRD_PARTY_NOTICES.md`.

This is an independent project, not affiliated with or endorsed by Gradium.
