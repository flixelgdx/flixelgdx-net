# 6. Native libraries and licenses

## What the native library is

Browsers have WebRTC built in. A desktop, Android or dedicated host needs it too, so it can accept browser
players on the fast road (see [Hosting and joining](hosting-and-joining.md)). That part comes from a small native
library, `flixelnetrtc`, which wraps two open-source projects:

| Project | What it does | License |
| --- | --- | --- |
| [libdatachannel](https://github.com/paullouisageneau/libdatachannel) | WebRTC data channels | MPL-2.0 |
| [Mbed TLS](https://github.com/Mbed-TLS/mbedtls) | Encryption for those channels | Apache-2.0 (or GPL-2.0-or-later, at your choice) |

libdatachannel also contains libjuice (MPL-2.0), usrsctp (BSD-3-Clause) and plog (MIT).

The library is **optional**. Without it everything still works, and browser players stay on the WebSocket.

## Which artifacts to add

| Platform | Artifact |
| --- | --- |
| Linux, 64-bit Intel/AMD | `flixelgdx-net-natives-linux-x86_64` |
| Linux, 64-bit ARM | `flixelgdx-net-natives-linux-aarch64` |
| Windows, 64-bit Intel/AMD | `flixelgdx-net-natives-windows-x86_64` |
| Windows, 64-bit ARM | `flixelgdx-net-natives-windows-aarch64` |
| macOS, Intel | `flixelgdx-net-natives-macos-x86_64` |
| macOS, Apple silicon | `flixelgdx-net-natives-macos-aarch64` |
| Android (all four CPU types) | Included in `flixelgdx-net-android` |
| Web | Nothing; the browser provides it |

Add the ones for the platforms you ship to as `runtimeOnly` dependencies of your desktop or server module. Each
is a small jar, and only the one matching the computer the game runs on is ever loaded.

## What this means for your game's license

FlixelGDX Net itself is MIT licensed, and so is its own Java and C++ code. The native library additionally
contains the projects above, under their own licenses. In plain terms:

- **Your game's code is not affected.** The MPL-2.0 is a "file-level" license: it covers libdatachannel's own
  source files, not programs that use the library. You can keep your game closed-source or license it however
  you like.
- **Ship the license notices.** Each natives jar contains a `licenses.txt` with the full text of every license
  involved (in the Android library it is at `META-INF/flixelgdx-net/licenses.txt`). Keep it in your packaged game, or copy its contents into your credits or "third-party licenses"
  screen.
- **Say where the source is.** The MPL-2.0 asks that people who receive the library can find its source. The
  libraries are built from unmodified, tagged upstream releases; the exact versions are pinned in
  [`scripts/natives.versions`](../scripts/natives.versions), and `licenses.txt` names them too. Pointing at that
  is enough.

This is a plain-language summary, not legal advice. For a commercial release, have someone qualified read the
licenses themselves.

## Where the binaries come from

The libraries are never committed to the repository or built on a contributor's computer. The
[Build Natives](../.github/workflows/natives.yml) workflow builds each one from source on a GitHub-hosted
machine of the matching kind, for all ten targets, and checks that each one loads.

To work on the extension itself with WebRTC enabled, download the `rtc-natives-all` artifact from the latest
successful run of that workflow and unpack it into the root of the checkout. It is laid out exactly like the
repository, so every file lands in place. With the GitHub CLI, find the run's number with
`gh run list --workflow natives.yml` and then:

```bash
gh run download RUN_ID --name rtc-natives-all --dir .
```

## Using a library you built yourself

Point the `flixel.net.rtc.library` system property at the file:

```bash
java -Dflixel.net.rtc.library=/path/to/libflixelnetrtc.so -jar mygame.jar
```
