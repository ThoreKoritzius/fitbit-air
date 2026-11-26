# Bundled native transport

The plugin ships Golden Gate's core (`libgg.so`) and JNI bridge (`libxp.so`)
for `arm64-v8a`, `armeabi-v7a` and `x86_64` (the ABIs Flutter supports) under
`android/core/src/main/jniLibs/`. They are built from pinned public source:

- Golden Gate `b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa` (Apache-2.0)
- Mbed TLS 2.28.10, the final 2.28 LTS release (Apache-2.0), replacing
  Golden Gate's 2.28.0 pin
- lwIP at Golden Gate's submodule pin (BSD-3-Clause)
- Android NDK 28.2.13676358, minimum API 24, 16 KB page alignment

Rebuild and replace the committed libraries (also rewrites `manifest.json`):

```sh
python3 tool/native/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
```

Check that the committed libraries match a fresh build:

```sh
python3 tool/native/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --check
```

Source paths are normalized and the libraries are stripped, so the output does
not depend on the machine or checkout location. The **Native transport**
workflow runs the check for every ABI when these files change and uploads the
built libraries.

The Kotlin bindings in `android/core/src/main/kotlin/com/fitbit/goldengate/bindings/`
must match this upstream JNI ABI; `NativeBindingSignaturesTest` guards it.
