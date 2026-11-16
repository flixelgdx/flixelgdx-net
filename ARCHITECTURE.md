# Project Structure

FlixelGDX Net is the multiplayer extension for [FlixelGDX](https://github.com/flixelgdx/flixelgdx). This page
explains how it is put together and why. To *use* the extension, read the [handbook](handbook/README.md)
instead.

Every module depends on the framework; nothing in the framework depends on this extension. That one-way
relationship is why the extension lives in its own repository.

## The layers

Networking is built like a postal service, in layers that each only know the one below them:

```
  Your game
     |
  Features        FlixelNetWorld (replication)   FlixelRollbackSession (rollback)
     |
  Session         FlixelNetSession: peers, handshake, ping, timeouts, typed messages
     |
  Transport       FlixelNetTransport: bytes on a reliable and an unreliable channel
     |
  The network     UDP, WebSocket, WebRTC, or the in-memory loopback
```

- **Transport (the trucks).** Moves packets of bytes between a host and its clients, each on one of two
  channels: `RELIABLE` (always arrives, in order) or `UNRELIABLE` (may be lost, but nothing waits for it). It has
  no idea what a game or a message is.
- **Session (the post office).** Turns bytes into typed messages between peers. It checks that both sides run
  the same protocol, measures ping, notices dead connections, hands out player ids, and packs many small
  messages into few packets.
- **Features (ready-made ways to use the post office).** Replication keeps objects the same everywhere, with the
  host as the judge. Rollback keeps a deterministic simulation in step by sending only controls. Both are built
  only on the session's public API, so a game can skip them and talk to the session directly.

Keeping the layers apart is what lets the same game code run over any transport, and what lets the tests run
everything without a real network.

## Modules

- **`flixelgdx-net-core`**: Everything that does not need a real network. It never opens a socket, starts a
  thread or calls a browser API, so it runs on every platform, including inside unit tests.
  - `org.flixelgdx.net`: `FlixelNetSession`, `FlixelNetPeer`, `FlixelNetBuffer` (the reusable byte box every
    packet is written into), and `FlixelNet`, the shortcut that picks the platform's transports for
    `host(...)` and `join(...)`.
  - `org.flixelgdx.net.message`: `@NetMessage`, `@NetRange`, `FlixelNetCodec` and `FlixelNetProtocol`, the list
    of messages both sides must agree on. Its hash is compared during the handshake.
  - `org.flixelgdx.net.transport`: the `FlixelNetTransport` contract and the shared building blocks for
    implementing it:
    - `FlixelNetDatagramTransport` turns loose packets into connections with a handshake, resends, ordering and
      a secret per connection. It is written against `FlixelNetDatagramSocket`, a tiny interface the platform
      fills in, so the tricky reliability code lives in core and is tested with a fake socket that loses and
      shuffles packets on purpose.
    - `FlixelNetEventQueue` is the waiting line that background threads fill and the game thread empties.
    - `FlixelNetLoopback` is an in-memory network with optional lag and loss. Tests use it, and so does a host
      that is also a player.
    - `FlixelNetIceConfig` holds the STUN and TURN servers WebRTC may use.
  - `org.flixelgdx.net.replication`: `FlixelNetWorld`, `FlixelNetEntity` and `FlixelNetPawn`.
- **`flixelgdx-net-processor`**: An annotation processor. For each `@NetMessage` class `Foo` it writes
  `FooNetCodec`, which reads and writes every field directly (no reflection) and can copy and blend two messages,
  which replication uses for smoothing.
- **`flixelgdx-net-jvm`**: The real transports for desktop, Android and servers.
  - `FlixelJvmDatagramSocket`: a UDP socket. A background thread receives packets and puts them on a shelf for
    the game thread.
  - `FlixelJvmWebSocketTransport`: a hand-written WebSocket client and server.
  - `FlixelJvmWebTransport`: a WebSocket that upgrades itself to WebRTC when both sides can, using the
    WebSocket to arrange it.
  - `FlixelJvmRtc`: the thin JNI doorway to the native WebRTC library.
  - `FlixelJvmNet.install()`: registers these with `FlixelNet`.

  Because Android runs this module too, it only uses Java library APIs that Android has.
- **`flixelgdx-net-natives`**: A folder that groups one packaging-only module per desktop platform,
  `flixelgdx-net-natives-<os>-<arch>`. Each holds no Java, only the native WebRTC library for that platform
  and its `licenses.txt`, so a game ships only the platforms it needs. Their Gradle paths are
  `:flixelgdx-net-natives:flixelgdx-net-natives-<os>-<arch>`.
- **`flixelgdx-net-android`**: The Android packaging: the native WebRTC library for the four Android CPU types,
  the `INTERNET` permission, and `FlixelAndroidNet.install()`. The transports themselves come from
  `flixelgdx-net-jvm`. This module is only part of the build when the `includeAndroid` flag is set (see
  [COMPILING.md](COMPILING.md)).
- **`flixelgdx-net-html5`**: The browser transport, `FlixelHtml5WebTransport`, which uses the browser's own
  WebSocket and WebRTC through small `@JSBody` functions with only numbers, strings and typed arrays, so it works
  on both of TeaVM's targets (JavaScript and WebAssembly). A browser can join but never host.
- **`flixelgdx-net-server`**: `FlixelHeadlessRunner` drives a game's update loop at a fixed rate with no window,
  and `FlixelServerLauncher.launch(game)` sets everything up for a dedicated server.
- **`flixelgdx-net-rollback`**: `FlixelRollbackSession` and the interfaces a deterministic game implements. It
  depends on core and nothing else.

## How a packet travels

Take a client sending one chat line to the host over UDP:

1. Game code calls `session.obtain(ChatLine.class)`, fills it in and calls `session.send(...)`. The message
   object comes from a pool, so nothing is allocated.
2. The session writes the message's id and fields (through its generated codec) into the outgoing batch for that
   peer and channel. Many messages share one packet.
3. Once per frame, inside `session.update(...)`, the session flushes each batch to the transport.
4. `FlixelNetDatagramTransport` adds its header (connection secret, sequence numbers, acknowledgements) and hands
   the bytes to the UDP socket. Reliable packets are kept until the other side acknowledges them, and are sent
   again if it does not.
5. On the host, a background thread receives the packet and puts it on a shelf.
6. In the host's next `session.update(...)`, the transport takes the packet off the shelf, checks it, puts it in
   order, and gives the bytes to the session.
7. The session reads the message id, finds the codec, reads the fields into a pooled `ChatLine`, and calls the
   game's handler, on the game thread.

Every step reuses its buffers, which is why sending and receiving produce no garbage once a game is running.

## How the routes are chosen

`FlixelNet.host(session, port)` listens on **two** transports at once on the same port number: UDP (for desktop
and Android players) and the WebSocket-plus-WebRTC transport (for browsers). A session can listen on several
transports, and peers from all of them end up in the same game.

`FlixelNet.join(session, address)` picks by the address:

- `"host:port"` joins over UDP.
- `"ws://..."` or `"wss://..."` joins over WebSocket, then tries to upgrade to WebRTC. A browser always takes this
  path, since it cannot use UDP.

The WebRTC upgrade works like this:

1. The WebSocket opens, and both sides can already exchange packets over it.
2. The joining side creates a WebRTC offer and sends it to the host over the WebSocket (this is called
   *signaling*). The host answers the same way, and both sides trade possible network addresses (ICE
   candidates).
3. WebRTC opens two data channels: one reliable and ordered, one unreliable and unordered.
4. Packets move to WebRTC without the game noticing. The connection keeps its id, and reliable packets stay in
   order across the switch.
5. If WebRTC cannot connect (no native library, or a firewall in the way), the connection simply stays on the
   WebSocket.

The host's WebRTC uses the same port number as the game, so a server only needs that one port open, for both UDP
and TCP.

## Threads

On desktop, Android and servers, sockets are read on background threads. Those threads never call game code;
they only put events into a queue. The game thread empties the queue inside `session.update(...)`, so every
callback a game sees runs on the game thread, in a predictable order, with no locks in game code. A browser has
only one thread, and the same polling contract holds there.

## Native code

The native WebRTC library is [libdatachannel](https://github.com/paullouisageneau/libdatachannel) with
[Mbed TLS](https://github.com/Mbed-TLS/mbedtls), plus a small C++ file, `native/flixelnet_rtc.cpp`, that exposes
what `FlixelJvmRtc` needs over JNI. JNI is used rather than Java's newer foreign function API because Android
does not have that API.

The libraries are never committed and never built on a contributor's computer. The
[Build Natives](.github/workflows/natives.yml) workflow runs `scripts/build_natives.sh` on a GitHub-hosted
machine of each kind (Linux, Windows and macOS, each on both 64-bit Intel or AMD and 64-bit ARM, plus four Android CPU types through the Android NDK),
checks that each library loads with `native/smoke`, and bundles them into one artifact laid out like the
repository. The third-party versions are pinned in `scripts/natives.versions`.

## Build system

FlixelGDX Net uses **Gradle** with the Kotlin DSL, mirroring the framework.

- **`settings.gradle.kts`**: The modules in the build, the repositories the framework is resolved from, and the
  `includeAndroid` flag (from `-PincludeAndroid=true` or `local.properties`).
- **`build.gradle.kts`**: The root aggregator. It holds no source and deliberately has no group, so a game that
  includes this build as a composite build only sees the `org.flixelgdx:flixelgdx-net-*` modules.
- **`gradle/libs.versions.toml`**: The version catalog. The `flixelgdx` version pins which framework release the
  extension is built against.
- **`build-logic/`**: The convention plugins (`flixelgdx.*`) every module applies: Java and Android library
  setup, Spotless, Checkstyle, publishing, and `flixelgdx.natives-jar` for the natives modules.
- **`example.local.properties`**: A template for `local.properties`, which holds the Android SDK path and the
  `includeAndroid` flag.

The version is not stored in any file; it comes from the latest git tag.

## GitHub integration

- **[CI Build Check](.github/workflows/ci_build.yml)** runs on every push and pull request: a Spotless formatting
  check, a Checkstyle check, a Javadoc check, the unit tests, and a full build.
- **[Build Natives](.github/workflows/natives.yml)** rebuilds the native libraries whenever `native/`, the build
  script, the pinned versions or the workflow itself change on `master`, and can be started by hand.
