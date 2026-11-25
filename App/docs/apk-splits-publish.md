# APK splits — publish step

App-speed (Nov 2026): the release build is split per ABI
(`App/app/build.gradle.kts`, `splits.abi`, enabled only with
`-PabiSplits=true`). A release build produces exactly four APKs and no
universal:

```
App/app/build/outputs/apk/release/
  app-arm64-v8a-release.apk      <- the one real phones get
  app-armeabi-v7a-release.apk
  app-x86-release.apk
  app-x86_64-release.apk
```

## What the download link serves

`https://radd-stream.ccwu.cc/download/radd-stream.apk` keeps serving a
working file for real phones: the publish step copies
**`app-arm64-v8a-release.apk`** to that path. Almost every Android phone
in the field is arm64-v8a; a wrong-ABI APK cannot install on a phone
anyway (`INSTALL_FAILED_NO_MATCHING_ABIS`), so serving the arm64 build
cannot put the wrong native libraries on a device.

The other three ABIs are not served from the main link. They stay
available as build artifacts (GitHub Actions run artifacts) for the rare
x86 / 32-bit device, and the publish step may copy them alongside the
main file (`radd-stream-armeabi-v7a.apk`, `radd-stream-x86.apk`,
`radd-stream-x86_64.apk`) if a second download path is ever needed.

## What stays unchanged

* The **debug build is never split** (no `-PabiSplits` flag on debug
  builds), so the Testsy/device flow keeps one universal file:
  `app-debug.apk`, published alongside as before
  (`radd-stream-debug.apk` or the existing debug publish path).
* Version codes are identical across the four ABIs (direct download,
  not Play multi-APK — see the `splits` comment in `build.gradle.kts`),
  so the in-app update check still compares one panel-published
  versionCode.
* The FFmpeg decoder stays in every APK (AC-3/E-AC-3/DTS sound). The
  size saving comes from the split alone.

## Building a split release

```sh
# From App/ (unsigned — signing needs KEYSTORE_BASE64, see build.gradle.kts):
./gradlew :app:assembleRelease -PabiSplits=true
```

Without `-PabiSplits=true` the release build is one universal APK, as
before — so a forgotten flag produces a working (just larger) APK,
never a broken one.
