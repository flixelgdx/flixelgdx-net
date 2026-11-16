# Native WebRTC libraries

Each `flixelgdx-net-natives-<platform>` folder here becomes one small jar of the same name that holds the native WebRTC library
for that platform. A game adds the jars of the platforms it ships to.

The libraries themselves are **not** in the repository. They are built by the
[Build Natives](../.github/workflows/natives.yml) workflow on a GitHub-hosted machine of each kind, from
[`native/`](../native) and the pinned third-party versions in
[`scripts/natives.versions`](../scripts/natives.versions).

To use them in a checkout, download the `rtc-natives-all` artifact of the latest successful run and unpack it into
the root of the repository. It is laid out exactly like the repository, so every file lands in the right place.

Each library comes with a `licenses.txt` that lists the third-party software linked into it.
