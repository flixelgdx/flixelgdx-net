# Compiling & Testing

FlixelGDX Net is a library extension, not a standalone game, so it cannot be run by itself. Testing a change has
two parts: building the extension and running its unit tests here, and then trying it in a real game.

This guide covers what is specific to this repository. For the full environment setup (installing JDK 25 with
Eclipse Temurin, Git, IDE configuration and platform troubleshooting), follow the framework's guide, which applies
here unchanged: **[flixelgdx/flixelgdx -> COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/master/COMPILING.md)**.

---

## Table of contents

1. [Prerequisites](#prerequisites)
2. [Getting the source](#getting-the-source)
3. [Building and checking](#building-and-checking)
4. [Running the tests](#running-the-tests)
5. [Getting the native WebRTC library](#getting-the-native-webrtc-library)
6. [Including the Android module](#including-the-android-module)
7. [Building against a local framework checkout](#building-against-a-local-framework-checkout)
8. [Trying it in a game](#trying-it-in-a-game)
9. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Java (JDK 25, Eclipse Temurin).** The build uses the Gradle wrapper and a Java 25 toolchain.
- **Git**, to clone this repository.
- **(Android module only)** The Android SDK with the `android-36` platform. See
  [Including the Android module](#including-the-android-module).
- **(WebRTC only)** The native WebRTC library, downloaded from a GitHub workflow run. See
  [Getting the native WebRTC library](#getting-the-native-webrtc-library). Everything else builds and runs
  without it.

You do **not** need a C++ compiler. The native library is only ever built on GitHub's machines.

---

## Getting the source

```bash
git clone https://github.com/flixelgdx/flixelgdx-net.git
cd flixelgdx-net
```

If you are contributing, fork the repository first, clone your fork, and add the upstream remote, exactly as
described in the framework's [CONTRIBUTING.md](https://github.com/flixelgdx/flixelgdx/blob/master/CONTRIBUTING.md).

---

## Building and checking

Build every module:

```bash
./gradlew assemble
```

These are the same checks CI runs on every push. Run them one at a time on a slower computer:

```bash
./gradlew spotlessApply    # fix formatting (CI runs spotlessCheck)
./gradlew checkstyleMain   # code quality and Javadoc completeness (rules in gradle/checkstyle)
./gradlew javadoc          # Javadoc with doclint
./gradlew test             # unit tests
```

---

## Running the tests

```bash
./gradlew test
```

Or one module at a time, which is lighter:

```bash
./gradlew :flixelgdx-net-core:test
```

What the tests cover:

| Module | What runs | Needs |
| --- | --- | --- |
| `flixelgdx-net-core` | Buffers, codecs, protocols, sessions, replication, and the UDP reliability layer against a fake socket that loses and shuffles packets on purpose. | Nothing |
| `flixelgdx-net-jvm` | Real UDP and WebSocket connections on `127.0.0.1`, and a full WebSocket-to-WebRTC upgrade. | The native library for the WebRTC test, which is skipped without it |
| `flixelgdx-net-server` | A real headless game hosting over UDP, a client joining it, and a clean shutdown. | Nothing |
| `flixelgdx-net-rollback` | Two or more players over the loopback, with lag, rewinds and checksums. | Nothing |

Most behavior can be tested without any network through `FlixelNetLoopback`, which can add pretend lag and
packet loss. Prefer it when you add tests.

---

## Getting the native WebRTC library

The libraries are never committed. The [Build Natives](.github/workflows/natives.yml) workflow builds them for
every platform and uploads one artifact, `rtc-natives-all`, laid out exactly like the repository. To use it,
download the artifact of the latest successful run on `master` and unpack it into the root of your checkout.

With the [GitHub CLI](https://cli.github.com/):

```bash
gh run list --workflow natives.yml --branch master --status success --limit 1
gh run download RUN_ID --name rtc-natives-all --dir .
```

Or from the website: open the workflow run under the repository's **Actions** tab, download `rtc-natives-all`
at the bottom of the page, and unzip it into the repository root.

The files land in:

- `flixelgdx-net-natives/flixelgdx-net-natives-<platform>/src/main/resources/natives/<platform>/` for desktop.
- `flixelgdx-net-android/src/main/jniLibs/<abi>/` for Android.

Both are ignored by git.

### Changing the native code

To change `native/flixelnet_rtc.cpp` or the pinned versions in `scripts/natives.versions`, push your branch and
start the **Build Natives** workflow on it from the **Actions** tab (or with
`gh workflow run natives.yml --ref YOUR_BRANCH`). It builds and load-tests all ten libraries, then download its
artifact as above to try them.

---

## Including the Android module

The Android module (`flixelgdx-net-android`) is off by default, so a normal build does not need an Android SDK.
Turn it on with either of these:

- Pass `-PincludeAndroid=true` on the command line.
- Add `includeAndroid=true` to a `local.properties` file in the project root.

The Android SDK location comes from `sdk.dir` in the same `local.properties` file (or the `ANDROID_HOME`
environment variable). A template is in [`example.local.properties`](example.local.properties); copy it to
`local.properties` and edit the values. `local.properties` is machine-specific and is never committed.

```bash
./gradlew :flixelgdx-net-android:assembleRelease -PincludeAndroid=true
```

The module packages the Android libraries from `src/main/jniLibs`, so download the natives first if you want
WebRTC to work on a phone.

---

## Building against a local framework checkout

The extension uses the published framework from Maven Central, at the version in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). If you are changing the framework and the extension
together, attach your framework checkout from the command line instead of editing any file:

```bash
./gradlew assemble --include-build ../flixelgdx
```

Gradle then builds the framework from source, whatever version number the catalog says.

---

## Trying it in a game

Unit tests prove the pieces work. To see them work for real, consume your local extension from a separate test
game with a **Gradle composite build**:

1. In the game's `settings.gradle.kts`, below `rootProject.name`, add:

   ```kotlin
   includeBuild("../flixelgdx-net")
   ```

   Use the real path to your checkout. On Windows, use forward slashes (`C:/Users/You/flixelgdx-net`).
2. Declare the dependencies as usual. Gradle swaps them for your local modules, so the version number you write
   does not matter while the composite build is active:

   ```kotlin
   // core module
   api("org.flixelgdx:flixelgdx-net-core:0.0.0")
   annotationProcessor("org.flixelgdx:flixelgdx-net-processor:0.0.0")

   // desktop module
   implementation("org.flixelgdx:flixelgdx-net-jvm:0.0.0")
   runtimeOnly("org.flixelgdx:flixelgdx-net-natives-linux-x86_64:0.0.0")   // one per platform you test on
   ```

   The [handbook](handbook/getting-started.md) lists the dependencies for every platform.
3. Run the game, for example `./gradlew :desktop:run`.

To test a real connection, start two copies of the game: one hosts and the other joins `127.0.0.1:7777`. For a
browser, run the game's web build and join `ws://127.0.0.1:7777`.

---

## Troubleshooting

### WebRTC never connects, and players stay on WebSocket

- **Cause**: the native library is not on the classpath, so the host cannot speak WebRTC.
  `FlixelJvmRtc.isAvailable()` returns `false`, and `FlixelJvmRtc.getProblem()` says why.
- **Fix**: download the natives as described [above](#getting-the-native-webrtc-library) and make sure the game
  depends on the `flixelgdx-net-natives-*` jar for your platform.

### `WARNING: flixelgdx-net-natives-... is empty`

- **Cause**: the build packaged a natives jar without its library. This is only a warning; everything else
  works. Publishing refuses an empty natives jar.
- **Fix**: download the natives if you need WebRTC, or ignore the warning.

### The `flixelgdx-net-android` project is not found

- **Fix**: pass `-PincludeAndroid=true` or set `includeAndroid=true` in `local.properties`, and make sure
  `sdk.dir` points at your Android SDK.

### Spotless or Checkstyle failures

- **Fix**: run `./gradlew spotlessApply` for formatting. For Checkstyle, read the violation it prints (file,
  line and rule) and fix the code. The rules match the framework's.

For anything not covered here, the framework's
[COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/master/COMPILING.md#troubleshooting) troubleshooting
section applies to this repository as well.
