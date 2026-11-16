---
name: update-natives
description: Bump the pinned libdatachannel or Mbed TLS version (or change native/flixelnet_rtc.cpp), rebuild every native WebRTC library on GitHub Actions, and install the result into this checkout. Use when asked to update, rebuild, or fetch the native WebRTC libraries.
argument-hint: "[libdatachannel version] [mbedtls version]"
---

# Update the native WebRTC libraries

The libraries are never built on this computer and never committed. They come from the **Build Natives**
workflow (`.github/workflows/natives.yml`), which builds all ten targets on GitHub-hosted machines.

## Bumping a version

1. Look up the new release tags before changing anything:
   - libdatachannel: https://github.com/paullouisageneau/libdatachannel/releases (tags look like `v0.24.6`).
   - Mbed TLS: https://github.com/Mbed-TLS/mbedtls/releases. Stay on the 3.6 long-term-support line unless the
     user asks otherwise; the archive name is `mbedtls-<version>.tar.bz2`.
   Read the release notes for breaking changes to the C API used in `native/flixelnet_rtc.cpp`, and for license
   changes. A license change must be raised with the user before going further.
2. Edit only `scripts/natives.versions`.
3. If the upstream API changed, update `native/flixelnet_rtc.cpp` and, if needed, `FlixelJvmRtc`. Keep the C
   function names unchanged unless both sides change together.
4. Update the versions named in `handbook/natives-and-licenses.md` if it lists them.

## Rebuilding

Pushing a change to `native/`, `scripts/natives.versions`, `scripts/build_natives.sh` or the workflow on
`master` starts the workflow. On another branch, start it by hand:

```bash
gh workflow run natives.yml --ref BRANCH
gh run list --workflow natives.yml --limit 1
```

Do not poll the run in a loop. Check it again only when the user asks, or wait for it once in the background.
A failed job's log is available with `gh run view RUN_ID --log-failed`.

## Installing the result

```bash
gh run download RUN_ID --name rtc-natives-all --dir .
```

The artifact is laid out like the repository, so the libraries land in
`flixelgdx-net-natives/flixelgdx-net-natives-<target>/src/main/resources/natives/<target>/` and
`flixelgdx-net-android/src/main/jniLibs/<abi>/`. Both are ignored by git; never commit them.

## Checking

Run the JVM tests, which include a full WebSocket-to-WebRTC upgrade that only runs when the library loads:

```bash
./gradlew :flixelgdx-net-jvm:test
```

Then commit the version or source change with a present-tense title such as
"Update libdatachannel to v0.25.0", with no co-author trailer.
