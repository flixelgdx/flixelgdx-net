# Contributing to FlixelGDX Net

FlixelGDX Net follows the exact same contribution guidelines as the main framework. Rather than duplicate them
here (and risk the two drifting out of sync), please read the canonical version:

> [!IMPORTANT]
> **All contribution guidelines live in the main framework repository:**
> **[flixelgdx/flixelgdx -> CONTRIBUTING.md](https://github.com/flixelgdx/flixelgdx/blob/master/CONTRIBUTING.md)**
>
> That document covers the JDK requirement, coding style, our stance on AI, the branch and pull request workflow,
> commit conventions, and everything else expected of a contribution. It all applies here unchanged.

## What is specific to this repository

- **How to build and test it.** The extension depends on the framework and, for WebRTC, on a native library
  that is built in the cloud rather than on your computer. See **[COMPILING.md](COMPILING.md)**.
- **How it is put together.** See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the modules, the layers, and how a
  packet travels from one game to another.
- **The networking design rules.** [AGENTS.md](AGENTS.md) lists them under "Networking design rules". They apply
  to people as much as to AI assistants. The short version:
  - `flixelgdx-net-core` never opens a socket, starts a thread or touches a browser API. Real networking lives in
    the platform modules.
  - Game code only ever runs on the game thread. Network events are queued and handed over inside `update`.
  - Sending and receiving create no garbage once a connection is warmed up.
  - Bytes from the network are never trusted. A broken or hostile packet is dropped and never crashes the game.
- **Tests come with changes.** Most behavior can be tested without a network through `FlixelNetLoopback`, which
  can also add pretend lag and packet loss. Add or update tests in the module you changed.
- **Changes to the C++ glue** (`native/`) or the pinned library versions (`scripts/natives.versions`) are built
  by the [Build Natives](.github/workflows/natives.yml) workflow, not locally. Mention in your pull request that
  the workflow passed on your branch.
