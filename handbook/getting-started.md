# 1. Getting started

On this page you add the extension to a game, open your first connection, and send your own messages. By the
end you have a small game where several players steer colored squares around one arena and can chat.

If words like *host*, *port* or *reliable* are new to you, read [Networking basics](networking-basics.md) first.

## How the pieces fit

Think of multiplayer as a group phone call with an operator.

- One game **hosts**: it is the operator, and opens the line. Everyone else **joins** it. In code, each end of
  the call is a `FlixelNetSession`.
- What people say on the call are **messages**: small Java classes such as "player 3 is here" or a line of chat.
  You write the class; the extension turns it into bytes and back.
- Each message goes out on one of two **channels**. `RELIABLE` always arrives, in order. `UNRELIABLE` is faster
  but may be lost. Use reliable for things that must not be missed (chat, a player joining) and unreliable for
  things that are replaced a moment later anyway (positions).
- Clients only ever talk to the host. If one client's chat should reach the others, the host passes it on. That
  one rule is what makes cheating hard: the host decides what is true.

## Step 1: add the modules

> [!NOTE]
> The extension is not on Maven Central yet. Until it is, clone this repository next to your game and add
> `includeBuild("../flixelgdx-net")` to your game's `settings.gradle.kts`. The coordinates below then resolve to
> your checkout, and the version number you write does not matter.

A FlixelGDX game is split into modules: `core` holds the game itself, and one module per platform holds its
launcher. Each one gets the matching net module.

```kotlin
// core/build.gradle.kts: your game code, shared by every platform.
dependencies {
  api("org.flixelgdx:flixelgdx-net-core:0.0.0")
  // Writes the code that turns each message into bytes. Needed wherever @NetMessage classes live.
  annotationProcessor("org.flixelgdx:flixelgdx-net-processor:0.0.0")
}
```

```kotlin
// desktop/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-jvm:0.0.0")
  // Optional, see page 6: lets browser players use the faster WebRTC connection.
  // Add one line per desktop platform you ship to.
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-linux-x86_64:0.0.0")
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-windows-x86_64:0.0.0")
  runtimeOnly("org.flixelgdx:flixelgdx-net-natives-macos-aarch64:0.0.0")
}
```

```kotlin
// android/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-android:0.0.0")
}
```

```kotlin
// html5/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-net-html5:0.0.0")
}
```

## Step 2: tell each launcher how to reach the network

A session does not know how to reach other computers by itself; each platform does it differently. One line in
each launcher, before the game starts, installs the right way:

```java
// desktop: DesktopLauncher.java
public final class DesktopLauncher {

  public static void main(String[] args) {
    FlixelJvmNet.install();
    FlixelDesktopLauncher.launch(new SquaresGame(args.length > 0 ? args[0] : null));
  }
}
```

```java
// android: AndroidLauncher.java
public class AndroidLauncher extends Activity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    FlixelAndroidNet.install();
    FlixelAndroidLauncher.launch(this, new SquaresGame(null));
  }
}
```

```java
// html5: Html5Launcher.java
public final class Html5Launcher {

  public static void main(String[] args) {
    FlixelHtml5Net.install();
    FlixelHtml5Launcher.launch(new SquaresGame(null));
  }
}
```

## Step 3: keep the game running in the background

```java
public class SquaresGame extends FlixelGame {

  /**
   * @param start "host" to host, "join:ADDRESS" to join, anything else for a single-player arena.
   */
  public SquaresGame(String start) {
    super(new FlixelConfig.Builder("Squares").size(1280, 720).build(), () -> new ArenaState(start));
    // A multiplayer game must never pause on its own.
    Flixel.autoPause = false;
  }
}
```

By default a FlixelGDX game pauses when its window loses focus. A paused game stops answering the network, and to
everyone else that looks exactly like a lost connection: they freeze, then drop you. So every multiplayer game
sets `Flixel.autoPause = false`.

The `start` text comes from the desktop launcher's arguments, so you can start a host with
`./gradlew :desktop:run --args=host` and a player with `./gradlew :desktop:run --args=join:127.0.0.1:7777`. A real
game would use a menu instead.

## Step 4: write the messages

A message is a plain class marked with `@NetMessage`. Its fields are what gets sent. Keeping all of a game's
messages in one class makes them easy to find:

```java
public final class Messages {

  private Messages() {}

  /** Where one player's square is. */
  @NetMessage
  public static class Position {
    @NetRange(min = 0, max = 255)
    public int id;
    @NetRange(min = 0, max = 1280)
    public float x;
    @NetRange(min = 0, max = 720)
    public float y;
  }

  /** A line of chat. */
  @NetMessage
  public static class ChatLine {
    @NetRange(min = 0, max = 255)
    public int from;
    public final FlixelString text = new FlixelString();
  }

  /** Sent by the host when a player leaves, so everyone removes their square. */
  @NetMessage
  public static class PlayerLeft {
    @NetRange(min = 0, max = 255)
    public int id;
  }
}
```

**What `@NetRange` does.** It is optional and makes messages smaller. A `float` normally takes four bytes,
because it could be any number. If you promise `x` stays between 0 and 1280, it fits in two, accurate to a small
fraction of a pixel. A value outside the range is clamped to the nearest end. Smaller messages mean less lag for
players on poor connections.

**What a field can be:** `boolean`, `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `String`,
`FlixelString`, an enum, an array of the number types, or another `@NetMessage` class. Fields must not be
`private` or `final` (except `FlixelString` and nested messages, which are filled in place). Mark a field
`transient` to leave it out. A message class needs a constructor with no parameters, and one nested in another
class must be `static`.

### The protocol

When you build, the annotation processor writes a **codec** for every message: the code that turns it into
bytes and back. A message named `ChatLine` inside `Messages` gets a codec named `MessagesChatLineNetCodec` (the
enclosing class's name comes first). List every codec once, in a **protocol**:

```java
// Inside Messages:
public static FlixelNetProtocol protocol() {
  return new FlixelNetProtocol("squares", 1)
    .add(MessagesPositionNetCodec.INSTANCE)
    .add(MessagesChatLineNetCodec.INSTANCE)
    .add(MessagesPlayerLeftNetCodec.INSTANCE);
}
```

The protocol is the phrase book both sides agree to speak:

- The **name** stops two different games from connecting to each other by accident.
- The **version** is yours to raise whenever a release should not play with older ones.
- When a client joins, both sides compare a fingerprint of their whole list of messages. If anything differs
  (a field added, a message missing, a different order), the client is turned away with `VERSION_MISMATCH`
  instead of reading garbage.

> [!TIP]
> If your editor says `MessagesPositionNetCodec` does not exist, build the project once
> (`./gradlew :core:compileJava`). The codecs are written during the build.

## Step 5: the game state

Here is the whole arena. It is long, but every part is explained below it.

```java
public class ArenaState extends FlixelState {

  private static final int SIZE = 32;
  private static final float SPEED = 300f;
  private static final float SEND_INTERVAL = 1f / 20f;   // Send our position 20 times a second.

  private final String start;
  private final FlixelIntMap<FlixelSprite> squares = new FlixelIntMap<>();
  private FlixelNetSession session;
  private FlixelSprite me;
  private float sendTimer;

  public ArenaState(String start) {
    this.start = start;
  }

  @Override
  public void create() {
    super.create();
    me = square(new FlixelColor(240, 120, 90, 1f));
    me.setPosition(100, 100);

    session = new FlixelNetSession(Messages.protocol());

    // 1. Things that happen to connections.
    session.onConnect.add(peer -> Flixel.info("Player " + peer.getId() + " connected over " + peer.getRoute()));
    session.onDisconnect.add(this::onLeft);
    session.onJoinFailed.add(reason -> Flixel.info("Could not join: " + reason));

    // 2. What to do with each kind of message.
    session.on(Messages.Position.class, this::onPosition);
    session.on(Messages.ChatLine.class, this::onChat);
    session.on(Messages.PlayerLeft.class, (peer, left) -> removeSquare(left.id));

    // 3. Host or join.
    if ("host".equals(start)) {
      FlixelNet.host(session, 7777);
      Flixel.info("Hosting on port 7777");
    } else if (start != null && start.startsWith("join:")) {
      FlixelNet.join(session, start.substring(5));
    }
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);

    // Move our own square. This never waits for the network.
    if (Flixel.keys.pressed(FlixelKey.LEFT)) {
      me.setX(me.getX() - SPEED * elapsed);
    }
    if (Flixel.keys.pressed(FlixelKey.RIGHT)) {
      me.setX(me.getX() + SPEED * elapsed);
    }
    if (Flixel.keys.pressed(FlixelKey.UP)) {
      me.setY(me.getY() - SPEED * elapsed);
    }
    if (Flixel.keys.pressed(FlixelKey.DOWN)) {
      me.setY(me.getY() + SPEED * elapsed);
    }

    // Tell the others where we are, a few times a second.
    sendTimer += elapsed;
    if (sendTimer >= SEND_INTERVAL) {
      sendTimer = 0f;
      Messages.Position position = session.obtain(Messages.Position.class);
      position.id = session.getLocalId();
      position.x = me.getX();
      position.y = me.getY();
      session.send(position, FlixelNetChannel.UNRELIABLE);
    }

    // Press C to say hello.
    if (Flixel.keys.justPressed(FlixelKey.C)) {
      Messages.ChatLine line = session.obtain(Messages.ChatLine.class);
      line.from = session.getLocalId();
      line.text.set("Hello!");
      session.send(line, FlixelNetChannel.RELIABLE);
    }

    // Receive, run the handlers above, and send everything queued this frame.
    session.update(elapsed);
  }

  @Override
  public void destroy() {
    session.close();   // Says goodbye, so the others do not have to wait for a timeout.
    super.destroy();
  }

  private void onPosition(FlixelNetPeer peer, Messages.Position position) {
    if (session.isHost()) {
      // A client told the host where it is. The host fills in who sent it (so nobody can pretend to be
      // someone else) and passes it on to everyone else.
      position.id = peer.getId();
      Messages.Position copy = session.obtain(Messages.Position.class);
      copy.id = position.id;
      copy.x = position.x;
      copy.y = position.y;
      session.sendExcept(peer, copy, FlixelNetChannel.UNRELIABLE);
    }
    FlixelSprite square = squares.get(position.id);
    if (square == null) {
      square = square(new FlixelColor(90, 190, 240, 1f));
      squares.put(position.id, square);
    }
    square.setPosition(position.x, position.y);
  }

  private void onChat(FlixelNetPeer peer, Messages.ChatLine line) {
    if (session.isHost()) {
      line.from = peer.getId();
      Messages.ChatLine copy = session.obtain(Messages.ChatLine.class);
      copy.from = line.from;
      copy.text.set(line.text);
      session.sendExcept(peer, copy, FlixelNetChannel.RELIABLE);
    }
    Flixel.info("Player " + line.from + ": " + line.text);
  }

  private void onLeft(FlixelNetPeer peer) {
    Flixel.info("Player " + peer.getId() + " left (" + peer.getDisconnectReason() + ")");
    if (session.isHost()) {
      removeSquare(peer.getId());
      Messages.PlayerLeft left = session.obtain(Messages.PlayerLeft.class);
      left.id = peer.getId();
      session.send(left, FlixelNetChannel.RELIABLE);
    }
  }

  private void removeSquare(int id) {
    FlixelSprite square = squares.remove(id);
    if (square != null) {
      remove(square);
    }
  }

  private FlixelSprite square(FlixelColor color) {
    FlixelSprite sprite = new FlixelSprite();
    sprite.makeGraphic(SIZE, SIZE, color);
    add(sprite);
    return sprite;
  }
}
```

### What is going on

**Everything happens inside `session.update(elapsed)`.** That one call reads what arrived, runs your
`onConnect`, `onDisconnect` and message handlers, and sends what you queued. Nothing ever calls your code from
another thread, so you never need locks, and handlers can touch sprites freely. Call it once per frame.

**Sending borrows a message.** `session.obtain(...)` hands you a recycled message instead of creating a new
one, so sending 20 positions a second leaves no garbage behind. Fill it in and pass it to `send`. After that it
belongs to the extension again: do not touch it, and do not keep it.

**Receiving lends you a message.** The message your handler gets is recycled as soon as the handler returns. Copy
out what you need. That is why the host *obtains a new message* to pass a position on instead of re-sending the
one it received.

**The host passes things on.** Clients never talk to each other. When a client's position or chat arrives, the
host fills in who sent it and forwards it with `sendExcept`, which sends to everyone except the original sender.

**Positions go unreliable, chat goes reliable.** If one position is lost, the next one arrives 50 milliseconds
later anyway. A lost chat line would simply be missing, so chat uses the reliable channel.

### Who receives what

| Call | Who receives it |
| --- | --- |
| `session.send(message, channel)` | On a client: the host. On the host: every client. |
| `session.sendTo(peer, message, channel)` | One peer. |
| `session.sendExcept(peer, message, channel)` | On the host: every client except one. |

### Who is who

- Every peer has a number, `peer.getId()`. The host is always `FlixelNetSession.HOST_ID`, which is zero.
  Clients are numbered from one, in the order they joined.
- `session.getLocalId()` is your own number. `session.isHost()` tells you whether you are the host.
- `peer.getPing()` is the round trip to that peer in milliseconds, and `peer.getRoute()` says how it is
  connected: `"UDP"`, `"WebSocket"`, `"WebRTC"` or `"Loopback"`.
- `peer.setData(...)` and `peer.getData()` let you hang your own object, such as the player's sprite, on a peer.

## Step 6: try it

Open two terminals in your game's folder.

```bash
./gradlew :desktop:run --args=host
```

```bash
./gradlew :desktop:run --args=join:127.0.0.1:7777
```

Two windows open. Move each square with the arrow keys and watch it move in the other window. Press C to send a
chat line; it appears in the other window's log. Close the second window and the host logs that the player left.

## A host that also plays, in one window

The arena above has one quirk: the host's code and a client's code differ (the host passes things on). Many
games avoid that by making the host's own player a *client too*, joined to its own host through a connection
that never leaves the program:

```java
FlixelNetSession hostSession = new FlixelNetSession(Messages.protocol());
FlixelNetSession mySession = new FlixelNetSession(Messages.protocol());

FlixelNet.host(hostSession, 7777);                                     // Others join this.
FlixelNetLoopback loopback = FlixelNet.joinLocal(hostSession, mySession);   // So does our own player.

// Every frame:
loopback.advance(elapsed);
hostSession.update(elapsed);
mySession.update(elapsed);
```

Now the host's player is an ordinary client, and there is only one player code path to write and test. The
host session only runs the host's rules. [Replication](replication.md) works this way.

## Testing on a bad connection

Your home network is far kinder than your players' connections will be. The loopback can pretend to be a bad one:

```java
loopback.setLatency(0.08f);  // Every packet arrives 80 ms late.
loopback.setLoss(0.05f);     // 5% of unreliable packets vanish.
```

Call `loopback.advance(elapsed)` every frame while a latency is set, so delayed packets are released on time.
Play your game like this early. Problems that only show up with lag are much cheaper to fix before the game is
built around them.

## Safety

Bytes from the network can be anything, including an attack. Every read is checked: a broken packet is dropped
inside the extension, and a peer that keeps sending them is disconnected. Your handlers only ever see
well-formed messages.

What the extension cannot check is whether a message is *honest*. So the host decides what is true:

- A client says "I pressed jump", never "I am now at the finish line".
- The host fills in who sent something (`peer.getId()`), never trusting an id the client wrote.
- The host checks that requests make sense before acting on them ("can this player afford that item?").

The arena above trusts positions from clients, which is fine for learning and wrong for a competitive game.
[Replication](replication.md) fixes that by sending controls instead of positions.

## Common mistakes

| Symptom | Cause |
| --- | --- |
| Nothing ever connects, no errors | `session.update(elapsed)` is never called. |
| Everyone freezes, then drops, when the host's window loses focus | `Flixel.autoPause` was left on. |
| Joining fails with `VERSION_MISMATCH` | The two builds have different message lists. Rebuild both. |
| A received message changes by itself later | You kept the message object after the handler returned. Copy its values instead. |
| `FlixelNet` says no network platform is installed | The launcher never called `FlixelJvmNet.install()` (or the Android or web version). |
| Joining times out between two computers | Usually a firewall on the host. See [Hosting and joining](hosting-and-joining.md). |

Next: [Hosting and joining](hosting-and-joining.md)
