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
- **Voices:** the model's built-in voice, plus, with one tap, every voice in Gradium's
  [voice catalog](https://huggingface.co/gradium/phonon-voices) that the model can speak, plus
  your own cloned voice (see [Your own voice](#your-own-voice)). Pick the default voice in the
  app.
- **Privacy:** the internet permission is used only to download the model and voices. Text is
  never sent anywhere.

## Install on a Galaxy S21 Ultra

1. **Get the APK.** On GitHub, open the repo's **Actions** tab, then the latest green **Android**
   run, and download the **AndroidTTS-apk** artifact. It is a zip: open it and take
   `AndroidTTS-<n>.apk`. If a release has been tagged, the **Releases** page has the APK directly.
2. **Install it.** Open the APK on the phone. Android asks you to allow installs from the app
   you opened it with (My Files or Chrome); allow it, then tap Install.
3. **Download the voice.** Open **AndroidTTS** and tap **Download the voice model**. This is a
   one-time download of a few tens of megabytes. When the status says *Ready*, tap
   **Play a sample**.
4. **Get more voices (optional).** Tap **Download all voices**, then tap a voice in the list to
   hear it and make it the default. ▶ only plays it.
5. **Make it the system voice.** Tap **Open the system text-to-speech settings**, or go to
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

## Voices

**Download all voices** fetches every voice in Gradium's catalog
[`gradium/phonon-voices`](https://huggingface.co/gradium/phonon-voices) that is in a language
the model speaks (English for Phonon 31M), plus any with no language listed. It uses the same
pinned catalog commit (`da6c9ec0`) as upstream's runtime. Each voice is one safetensors file.
The app checks free space before starting and shows the total size as it downloads.

How it behaves:

- **Checked:** every file is verified against the size and SHA-256 Hugging Face lists for it.
- **Resumable:** files are kept in a staging folder until all are in. If you cancel, lose the
  network or the app is killed, tapping the button again picks up from the files already
  checked. Voices already installed are never downloaded twice.
- **Partial failures don't stop the rest:** a voice that can't be fetched after three tries is
  skipped and named in the result. Tap the button again to retry it.
- **Safe to install:** the model reloads once with all the new voices. Phonon loads every voice
  file with the model, so one bad file would stop the engine; if the reload fails, the new
  voices are removed and the previous voices come back.
- **Kept:** "Download the voice model again" and folder imports keep downloaded voices, unless
  the new checkpoint has its own voice of the same name.
- **Removable:** **Remove downloaded voices** deletes them all; the built-in voice stays.

Apps see each voice as `<language>-<name>`, such as `en-Zoey`, with the catalog's accent as its
locale (en-US, en-GB, …), so apps that list voices by locale can show them properly. Android's
system settings have no voice picker for an engine, so the voice an app gets when it doesn't
ask for one is the one chosen in this app. Apps that are already running switch to a newly
chosen voice the next time they set their language, or when restarted.

## Your own voice

Phonon can speak in your voice, or anyone's who agrees to it, from a voice file that Gradium
makes from a short recording. The Phonon models on Hugging Face can't clone on their own, so
cloning goes through Gradium. After that, the voice runs offline like any other.

1. **Record about 10 seconds of clear speech:** a quiet room, one speaker, no music. Your phone's
   recorder is fine.
2. **Make the voice file.** Either:
   - in [Gradium Studio](https://studio.gradium.ai), in any browser including the phone's: create
     a voice from your recording, then on the voice tap **⋮ → Download for Phonon**; or
   - on a computer, with a Gradium API key and [uv](https://docs.astral.sh/uv/):
     ```sh
     export GRADIUM_API_KEY=...
     uv run https://raw.githubusercontent.com/gradium-ai/xn-ptts/main/scripts/clone-voice.py me.wav --lang en --name Me -o Me.safetensors
     ```
3. **Import it.** In the app, tap **Import a voice file**, pick the `.safetensors` file and name
   the voice. Then tap it in the voice list to hear it and make it the default.

Your recording is uploaded to Gradium to make the voice, and the voice is also kept in your
Gradium account. Synthesis afterwards happens on the phone only.

Imported voices are listed as "Your voice" and are offered in every language the model speaks.
They're kept when the model is downloaded again, are never overwritten by a catalog download,
and each has its own ✕ to remove it. The app checks that the file is a voice file, a valid
safetensors file under 64 MB, before installing it. If the model then fails to load with it,
it's removed again, just like a bad catalog voice.

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
artifact. It also publishes releases, with the release APK attached as `AndroidTTS-<tag>.apk`.
Any of these works:

- **Actions → Android → Run workflow**, pick the branch (usually `main`) and type a new tag such
  as `v0.2.0`. CI builds it, creates the tag on that commit, and publishes the release.
- Publish a release on GitHub (Releases → Draft a new release, new tag). The APK appears on it a
  few minutes later.
- Push a `v*` tag with git, and CI creates the release.

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

- `./gradlew testDebugUnitTest`: JVM tests for picking, parsing and checking the model files;
  reading and filtering the voice catalog (languages, name clashes, unsafe names and paths);
  which voice each language gets; and installing, removing and carrying over voice files.
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
| `app/src/main/java/.../Hub.kt` | Hugging Face listings and checked, retried downloads |
| `app/src/main/java/.../ModelDownload.kt` | Which files make the Phonon 31M checkpoint |
| `app/src/main/java/.../VoiceCatalog.kt` | Reads the voice catalog and picks the voices a model can speak |
| `app/src/main/java/.../VoiceDownload.kt` | Downloads catalog voices, resumably, and installs them |
| `app/src/main/java/.../VoiceImport.kt` | Imports a voice file, such as a cloned voice |
| `app/src/main/java/.../VoiceStore.kt` | The added voices, from the catalog or imported: files and records |
| `app/src/main/java/.../VoiceSet.kt` | Which voices each language offers, and the default |
| `app/src/main/java/.../Downloads.kt` | Runs one download at a time and reports progress |
| `app/src/main/java/.../SettingsActivity.kt` | The app screen: downloads, voice list, samples, folder import |
| `app/src/main/java/.../DataActivity.kt` | Answers the system settings' data and sample-text queries |
| `app/src/main/java/.../PhononTTS.kt` | JNA binding to `libptts_ffi.so` |
| `scripts/build-native.sh` | Builds `libptts_ffi.so` from xn-ptts at the pinned commit |

## Credits and licenses

This project is MIT licensed (see `LICENSE`). The engine code is adapted from xn-ptts's
`android/tts-engine` by Gradium (MIT OR Apache-2.0). Phonon 31M's weights are Apache 2.0 and are
downloaded from Hugging Face, not distributed here. See `THIRD_PARTY_NOTICES.md`.

This is an independent project, not affiliated with or endorsed by Gradium.
