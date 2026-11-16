<div align="center">

  # FlixelGDX Net

  [![CI](https://github.com/flixelgdx/flixelgdx-net/actions/workflows/ci_build.yml/badge.svg)](https://github.com/flixelgdx/flixelgdx-net/actions/workflows/ci_build.yml)
  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
  [![Java 25+](https://img.shields.io/badge/Java-25%2B-orange)](https://adoptium.net/temurin/releases?version=25&os=any&arch=any)

  FlixelGDX Net is the multiplayer extension for the game framework [FlixelGDX](https://github.com/flixelgdx/flixelgdx).
</div>

> [!WARNING]
> This extension is in early development and is not published to Maven Central yet. Its API can still change.

---

## The idea

Networking is built in layers, and your game can stop at whichever one it needs:

1. **Transport** moves packets of bytes between a host and its clients. Each packet is sent either *reliably*
   (it always arrives, in order) or *unreliably* (it may be lost, but nothing ever waits for it).
2. **Session and messages** turn those bytes into typed messages between named peers.
3. **Replication** keeps game objects in sync for you, with smoothing and instant-feeling controls.
4. **Rollback** is a separate module for games that need every player to simulate the exact same thing.

The rules that hold everywhere:

- Your code only ever runs on the game thread. Network news arrives once per frame, inside `update`.
- Sending and receiving do not create garbage.
- Bytes from the network are never trusted. A broken or hostile packet is dropped; it cannot crash the game.
- Desktop, Android and browser players can all be in the same game.

## A first taste

```java
@NetMessage
public class ChatLine {
  public final FlixelString text = new FlixelString();
}

FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1).add(ChatLineNetCodec.INSTANCE);
FlixelNetSession session = new FlixelNetSession(protocol);

session.onConnect.add(peer -> Flixel.info("Peer " + peer.getId() + " connected"));
session.on(ChatLine.class, (peer, line) -> Flixel.info(peer.getId() + ": " + line.text));

FlixelNet.host(session, 7777);                    // Or: FlixelNet.join(session, "192.168.1.20:7777");

// Every frame:
session.update(elapsed);

// Whenever you like:
ChatLine line = session.obtain(ChatLine.class);
line.text.set("hello");
session.send(line, FlixelNetChannel.RELIABLE);
```

## Documentation

The **[handbook](handbook/README.md)** walks through everything from the first message to dedicated servers, and
assumes no networking experience.

| Page | |
| --- | --- |
| [Getting started](handbook/getting-started.md) | Modules, sessions and messages. |
| [Hosting and joining](handbook/hosting-and-joining.md) | UDP, WebSocket and WebRTC; routers, STUN and TURN. |
| [Replication](handbook/replication.md) | Entities, pawns, smoothing and prediction. |
| [Dedicated servers](handbook/dedicated-server.md) | Running the game with no window. |
| [Rollback](handbook/rollback.md) | Deterministic multiplayer where only controls travel. |
| [Native libraries and licenses](handbook/natives-and-licenses.md) | The WebRTC library and what to ship. |

## Modules

| Module | What it is | Runs on |
| --- | --- | --- |
| `flixelgdx-net-core` | Sessions, messages, replication, and an in-process loopback transport with pretend lag and loss. | Everywhere |
| `flixelgdx-net-processor` | The annotation processor that writes a codec for each `@NetMessage` class. | Build time |
| `flixelgdx-net-jvm` | UDP, WebSocket and WebRTC transports. | Desktop, Android, servers |
| `flixelgdx-net-natives-*` | The native WebRTC library, one jar per desktop platform. Optional. | Desktop, servers |
| `flixelgdx-net-android` | The native WebRTC library for Android and the network permission. | Android |
| `flixelgdx-net-html5` | The browser's WebSocket and WebRTC. | Web |
| `flixelgdx-net-server` | A launcher that runs a game with no window. | Servers |
| `flixelgdx-net-rollback` | Rollback netcode. | Everywhere |

## Building

The build needs a Java 25 JDK ([Eclipse Temurin](https://adoptium.net/temurin/releases?version=25&os=any&arch=any)
is recommended). FlixelGDX itself is pulled from Maven Central.

```bash
./gradlew build
```

The Android module is only built when an Android SDK is configured; copy `example.local.properties` to
`local.properties` and fill it in. The native WebRTC libraries are built by a GitHub workflow, not locally; see
[Native libraries and licenses](handbook/natives-and-licenses.md).

## License

FlixelGDX Net is released under the [MIT License](LICENSE). The optional native WebRTC library contains
third-party software under its own licenses; see
[Native libraries and licenses](handbook/natives-and-licenses.md).
