# 1. Getting started

## The idea in one minute

Think of multiplayer as a group phone call.

- One player **hosts**: they open the line. Everyone else **joins** it. In this extension a
  `FlixelNetSession` is one end of that call.
- What you say on the call are **messages**: small Java classes such as "player 3 moved to here" or a line of
  chat. You write the class; the extension turns it into bytes and back.
- Every message goes out one of two ways. **Reliable** is like a registered letter: it always arrives, and in
  order. **Unreliable** is like shouting across a room: fast, but a word may be lost. Use reliable for things
  that must not be missed (chat, a player joining), and unreliable for things that are replaced a moment later
  anyway (positions).

The host is in charge. Clients talk to the host, and the host decides what to pass on to everyone else. This is
called *client-server*, and it is what makes cheating hard and joining late easy.

## Adding the modules

> [!NOTE]
> The extension is not on Maven Central yet. Until it is, clone this repository next to your game and add
> `includeBuild("../flixelgdx-net")` to your game's `settings.gradle.kts`; the coordinates below then resolve
> to your checkout.

Each platform module of your game gets the matching net module:

```kotlin
// core/build.gradle.kts (shared game code)
dependencies {
  api("org.flixelgdx:flixelgdx-net-core:$netVersion")
  annotationProcessor("org.flixelgdx:flixelgdx-net-processor:$netVersion")
}

// desktop/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-jvm:$netVersion")
  // Optional: lets browser players use WebRTC. One line per platform you ship to.
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-linux-x86_64:$netVersion")
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-windows-x86_64:$netVersion")
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-macos-aarch64:$netVersion")
}

// android/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-android:$netVersion")
}

// html5/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-html5:$netVersion")
}
```

Then tell each launcher how its platform reaches the network. It is one line, before the game starts:

```java
// Desktop
public static void main(String[] args) {
  FlixelJvmNet.install();
  FlixelDesktopLauncher.launch(new MyGame());
}

// Android: FlixelAndroidNet.install();
// Web:     FlixelHtml5Net.install();
```

One more line belongs in your game's setup:

```java
Flixel.autoPause = false;
```

By default a FlixelGDX game pauses when its window loses focus. A paused host stops answering the network, and
everyone connected to it freezes and then times out. Multiplayer games must keep running in the background.

## Messages

A message is a plain class marked with `@NetMessage`. Its fields are what gets sent.

```java
@NetMessage
public class PlayerMoved {
  @NetRange(min = 0, max = 255)   public int id;
  @NetRange(min = 0, max = 1280)  public float x;
  @NetRange(min = 0, max = 720)   public float y;
}

@NetMessage
public class ChatLine {
  public int from;
  public final FlixelString text = new FlixelString();
}
```

`@NetRange` is optional and makes messages smaller. A `float` normally takes four bytes because it could be any
number. If you promise that `x` stays between 0 and 1280, it fits in two. Smaller messages mean less lag for
players on poor connections.

**Fields can be:** `boolean`, `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `String`,
`FlixelString`, enums, arrays of those number types, and other `@NetMessage` classes. Fields must not be
`private`. Mark a field `transient` to leave it out.

When you build, the annotation processor writes a *codec* for each message, named after the class with
`NetCodec` on the end. List all of them once, in a **protocol**:

```java
public static FlixelNetProtocol protocol() {
  return new FlixelNetProtocol("my-game", 1)
      .add(PlayerMovedNetCodec.INSTANCE)
      .add(ChatLineNetCodec.INSTANCE);
}
```

The protocol is the language both sides agree to speak. The name stops two different games from connecting to
each other by accident. Raise the version number whenever you release a build that should not play with older
ones. If a client's messages do not match the host's exactly, it is turned away with `VERSION_MISMATCH` instead
of reading nonsense.

## Your first session

```java
public class PlayState extends FlixelState {

  private FlixelNetSession session;

  @Override
  public void create() {
    super.create();
    session = new FlixelNetSession(Messages.protocol());

    session.onConnect.add(peer -> Flixel.info("Peer " + peer.getId() + " connected"));
    session.onDisconnect.add(peer -> Flixel.info("Peer " + peer.getId() + " left: " + peer.getDisconnectReason()));
    session.onJoinFailed.add(reason -> Flixel.info("Could not join: " + reason));

    session.on(ChatLine.class, (peer, line) -> Flixel.info(line.from + ": " + line.text));

    if (hosting) {
      FlixelNet.host(session, 7777);
    } else {
      FlixelNet.join(session, "192.168.1.20:7777");
    }
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);
    session.update(elapsed);   // Receives, calls your handlers, and sends.
  }

  @Override
  public void destroy() {
    session.close();           // Tells everyone you are leaving.
    super.destroy();
  }
}
```

Everything happens inside `session.update(...)`: that is the moment your `onConnect` and message handlers run.
Nothing ever calls your code from another thread, so you never need locks.

## Sending

```java
ChatLine line = session.obtain(ChatLine.class);  // Borrow a message...
line.text.set("hello");
session.send(line, FlixelNetChannel.RELIABLE);   // ...and send it. Do not touch it afterwards.
```

`obtain` hands you a recycled message instead of creating a new one, so sending sixty positions a second leaves
no garbage behind. Once you pass it to `send`, it belongs to the extension again.

| Call | Who receives it |
| --- | --- |
| `session.send(msg, channel)` | On a client: the host. On the host: every client. |
| `session.sendTo(peer, msg, channel)` | One peer. |
| `session.sendExcept(peer, msg, channel)` | On the host: every client but one. Handy for passing a message along. |

Clients never talk to each other directly. If player 2's chat should reach player 3, the host passes it on:

```java
// On the host:
session.on(ChatLine.class, (peer, line) -> {
  ChatLine copy = session.obtain(ChatLine.class);
  copy.from = peer.getId();      // The host fills this in, so nobody can pretend to be someone else.
  copy.text.set(line.text);
  session.send(copy, FlixelNetChannel.RELIABLE);
});
```

Messages handed to a handler are also recycled when the handler returns. Copy out what you need; do not keep the
message object.

## Who is who

- Every peer has a number, `peer.getId()`. The host is always `FlixelNetSession.HOST_ID` (zero), and clients
  are numbered from one in the order they joined.
- `session.getLocalId()` is your own number.
- `peer.getPing()` is the round trip time to that peer in milliseconds.
- `peer.setData(...)` lets you hang your own object (such as the player's sprite) on a peer.

## A host that also plays

The simplest way to structure a game is to write the *client* once, and let the host's own player join through a
connection that never leaves the program:

```java
FlixelNet.host(hostSession, 7777);
FlixelNet.joinLocal(hostSession, mySession);   // Returns a FlixelNetLoopback.
```

Now the host's player is a client like everyone else, and there is only one code path to test.

## Testing on a bad connection

Your home network is far kinder than your players' connections will be. `joinLocal` returns a loopback that can
pretend to be a bad one:

```java
FlixelNetLoopback loopback = FlixelNet.joinLocal(hostSession, mySession);
loopback.setLatency(0.08f);  // 80 ms each way.
loopback.setLoss(0.05f);     // 5% of unreliable messages vanish.
```

Play your game like this early. Problems that only appear with lag are much cheaper to fix before the game is
built around them.

## Safety

Bytes from the network can be anything, including an attack. Every read is checked; a broken packet raises a
`FlixelNetException` inside the extension, the packet is dropped, and a peer that keeps sending them is
disconnected. Your handlers only ever see well-formed messages. What they cannot check is whether a message is
*honest*, so the host should always decide what is true: a client says "I pressed jump", never "I am now at the
finish line".

Next: [Hosting and joining](hosting-and-joining.md)
