<div align="center">

  # FlixelGDX Net

  [![CI](https://github.com/flixelgdx/flixelgdx-net/actions/workflows/ci_build.yml/badge.svg)](https://github.com/flixelgdx/flixelgdx-net/actions/workflows/ci_build.yml)
  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
  [![Java 25+](https://img.shields.io/badge/Java-25%2B-orange)](https://adoptium.net/temurin/releases?version=25&os=any&arch=any)

  FlixelGDX Net is the multiplayer extension for the game framework [FlixelGDX](https://github.com/flixelgdx/flixelgdx).
</div>

> [!WARNING]
> This extension is in early development and is not published yet. Only the pieces listed under
> [Status](#status) exist today, and their API can still change.

---

## The idea

Networking is built in layers, and your game can stop at whichever one it needs:

1. **Transport** moves packets of bytes between a host and its clients. Each packet is sent either *reliably*
   (it always arrives, in order) or *unreliably* (it may be lost, but nothing ever waits for it).
2. **Session and messages** turn those bytes into typed messages between named peers.
3. **Replication** keeps game objects in sync for you.
4. **Rollback** is a separate module for games that need every player to simulate the exact same thing.

The rules that hold everywhere:

- Your code only ever runs on the game thread. You ask for news once per frame by polling.
- Sending and receiving do not create garbage.
- Bytes from the network are never trusted. A broken or hostile packet raises an exception you can catch; it
  cannot crash the game.

## Status

| Piece | Module | State |
| --- | --- | --- |
| `FlixelNetBuffer` (compact, safe packet reading and writing) | `flixelgdx-net-core` | Done |
| `FlixelNetTransport` interface and event queue | `flixelgdx-net-core` | Done |
| `FlixelNetLoopback` (in-process transport with simulated lag and packet loss) | `flixelgdx-net-core` | Done |
| Session, handshake and typed messages | `flixelgdx-net-core` | Planned |
| UDP and WebSocket transports | `flixelgdx-net-jvm`, `flixelgdx-net-html5` | Planned |
| WebRTC transport | `flixelgdx-net-jvm`, `flixelgdx-net-html5` | Planned |
| Replication | `flixelgdx-net-core` | Planned |
| Dedicated headless server | `flixelgdx-net-server` | Planned |
| Rollback | `flixelgdx-net-rollback` | Planned |

## A first taste

The loopback transport connects a host and a client inside one program, which is how the extension tests
itself and how a host can also be a player:

```java
FlixelNetLoopback loopback = new FlixelNetLoopback();
FlixelNetTransport host = loopback.getHost();
FlixelNetTransport client = loopback.newClient();
FlixelNetBuffer buffer = new FlixelNetBuffer();

host.listen(0);
client.connect("");

// Once per frame:
loopback.advance(elapsed);
host.poll(hostListener);
client.poll(clientListener);

// Send a position in 4 bytes plus an id.
buffer.clear();
buffer.writeVarUInt(playerId);
buffer.writeQuantized(x, 0f, 1280f, 16);
buffer.writeQuantized(y, 0f, 720f, 16);
client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, buffer.getData(), 0, buffer.getSize());
```

To see how your game copes with a bad connection, turn on lag and packet loss:

```java
loopback.setLatency(0.08f); // 80 ms each way
loopback.setLoss(0.05f);    // 5% of unreliable packets go missing
```

## Building

The build needs a Java 25 JDK ([Eclipse Temurin](https://adoptium.net/temurin/releases?version=25&os=any&arch=any)
is recommended). FlixelGDX itself is pulled from Maven Central.

```bash
./gradlew build
```

## License

FlixelGDX Net is released under the [MIT License](LICENSE).
