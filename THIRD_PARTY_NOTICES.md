# Third-party notices

## xn-ptts (Phonon runtime)

- Source: https://github.com/gradium-ai/xn-ptts, commit `e5110afd3ead725c940b6411eec4985fb8b111aa`
- License: MIT OR Apache-2.0, at your option
- Used for: `libptts_ffi.so`, built from this source by `scripts/build-native.sh` and packaged
  in the APK. `app/src/main/java/io/github/enoky/androidtts/PhononTTS.kt` is copied from
  `android/PhononTTS.kt` with its package changed. `PhononService.kt`, `Model.kt`,
  `DataActivity.kt`, `SettingsActivity.kt`, `EngineTest.kt` and the resources are adapted from
  `android/tts-engine`.

The MIT license of xn-ptts:

```
Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

`libptts_ffi.so` also links the Rust crates listed in xn-ptts's `Cargo.lock`, each under its own
license.

## Phonon 31M weights

- Source: https://huggingface.co/gradium/phonon-31m-en, commit `8c64b55ec301728e2d1695bfea87a08a267d0c2d`
- License: Apache 2.0
- Not distributed with this project: the app downloads the files at runtime.

## JNA

- Source: https://github.com/java-native-access/jna, `net.java.dev.jna:jna:5.17.0`
- License: Apache 2.0 OR LGPL 2.1+, at your option (used here under Apache 2.0)
