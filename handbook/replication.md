# 3. Replication

On this page you let the extension keep game objects the same on every screen. You rebuild the arena from
[Getting started](getting-started.md) so that the host owns the truth, every square moves instantly for its own
player, other squares glide smoothly, and a ball bounces around that everyone sees in the same place.

## Why

With plain messages, most of a real-time game's network code is the same sentence over and over: "this thing is
here now". You write a message for it, send it for every object many times a second, smooth the result on the
other side so it does not stutter, tell late joiners about everything that already exists, and remove things when
their owner leaves. **Replication** does all of that for you.

It also fixes the arena's big flaw: there, clients sent their own positions, so a tampered client could teleport.
With replication, clients only send their **controls**, and the host works out where everything is.

## The idea

Think of the host as a stage and the clients as an audience watching a live broadcast of it.

- The host puts actors on the stage and moves them.
- A camera films the stage a few times a second and sends the pictures out.
- Each client builds the same actors and moves them to match the pictures, sliding smoothly from one picture to
  the next.

In code:

| Idea | Class |
| --- | --- |
| The stage | `FlixelNetWorld` |
| An actor, such as a ball | `FlixelNetEntity` |
| An actor a player steers | `FlixelNetPawn`, a kind of entity |
| A picture of one actor | Its **state**: a small `@NetMessage` class |
| What a player pressed | Its **input**: another small `@NetMessage` class |

## Step 1: the messages

Each kind of entity needs a state, and each kind of pawn also needs an input:

```java
public final class Messages {

  private Messages() {}

  public static FlixelNetProtocol protocol() {
    FlixelNetProtocol protocol = new FlixelNetProtocol("squares", 2)
      .add(MessagesSpotNetCodec.INSTANCE)
      .add(MessagesArrowsNetCodec.INSTANCE);
    // The world sends one message of its own, which must be in the protocol too.
    FlixelNetWorld.addMessages(protocol);
    return protocol;
  }

  /** Where something is: the "picture" of a square or the ball. */
  @NetMessage
  public static class Spot {
    @NetRange(min = 0, max = 1280)
    public float x;
    @NetRange(min = 0, max = 720)
    public float y;
  }

  /** Which arrow keys a player is holding. */
  @NetMessage
  public static class Arrows {
    public boolean left;
    public boolean right;
    public boolean up;
    public boolean down;
  }
}
```

Two kinds can share one state class, as the squares and the ball do here.

## Step 2: an entity

An entity is a plain object holding the values that matter, plus answers to two questions:

- `capture`: "what does the picture look like right now?" Asked on the host.
- `apply`: "make this object match this picture." Asked on the clients.

The ball also moves by itself, so it answers a third:

- `step`: "move one tick." Asked **only on the host**. Clients never move the ball themselves; they are shown
  where it was.

```java
public class Ball extends FlixelNetEntity<Messages.Spot> {

  public float x = 640;
  public float y = 360;
  private float vx = 260;
  private float vy = 190;

  @Override
  protected void step(float dt) {
    x += vx * dt;
    y += vy * dt;
    if (x < 0 || x > 1280 - 24) {
      vx = -vx;
    }
    if (y < 0 || y > 720 - 24) {
      vy = -vy;
    }
  }

  @Override
  protected void capture(Messages.Spot out) {
    out.x = x;
    out.y = y;
  }

  @Override
  protected void apply(Messages.Spot state) {
    x = state.x;
    y = state.y;
  }
}
```

Notice that the entity has no sprite. Keeping drawing out of entities means a dedicated server, which draws
nothing, can use the exact same classes. The game state draws them, in step 5.

## Step 3: a pawn

If a player's square only moved when the host said so, every key press would travel to the host and back before
anything happened on screen. With 100 milliseconds of lag, the game would feel like walking through mud.

A `FlixelNetPawn` fixes that the way a chess player thinks ahead:

1. The player's computer makes the move right away and remembers it. This is **prediction**.
2. The controls go to the host, which makes the same move for real.
3. When the host's answer comes back, the computer checks its guess. If the host disagrees (the player ran into
   something only the host knew about), the pawn is put where the host says, and the moves the host has not seen
   yet are played again on top. This is **reconciliation**.

When it works, the player feels no lag at all. A pawn answers two more questions:

- `sample`: "what are the controls doing right now?" Asked on the owner's computer.
- `simulate`: "move one tick using these controls." Asked on the owner's computer **and** on the host, so it must
  do exactly the same thing on both.

```java
public class Square extends FlixelNetPawn<Messages.Spot, Messages.Arrows> {

  private static final float SPEED = 300;

  public float x;
  public float y;

  @Override
  protected void sample(Messages.Arrows out) {
    out.left = Flixel.keys.pressed(FlixelKey.LEFT);
    out.right = Flixel.keys.pressed(FlixelKey.RIGHT);
    out.up = Flixel.keys.pressed(FlixelKey.UP);
    out.down = Flixel.keys.pressed(FlixelKey.DOWN);
  }

  @Override
  protected void simulate(Messages.Arrows input, float dt) {
    if (input.left) {
      x -= SPEED * dt;
    }
    if (input.right) {
      x += SPEED * dt;
    }
    if (input.up) {
      y -= SPEED * dt;
    }
    if (input.down) {
      y += SPEED * dt;
    }
    // Keep it inside the arena. The host does the same, so the guess stays right.
    x = Math.max(0, Math.min(1280 - 32, x));
    y = Math.max(0, Math.min(720 - 32, y));
  }

  @Override
  protected void capture(Messages.Spot out) {
    out.x = x;
    out.y = y;
  }

  @Override
  protected void apply(Messages.Spot state) {
    x = state.x;
    y = state.y;
  }
}
```

The client never sends its position, only its controls. The host works out where the pawn ends up, so a tampered
client cannot teleport. The host also refuses to handle more inputs per second than there are ticks, so sending
extra inputs does not make a pawn faster.

**Rules for a pawn that predicts well:**

- `simulate` should depend only on the input and on values that are part of the state. If it reads anything else
  (a timer, another entity, a random number), the client's guess can differ from the host's, the pawn gets
  corrected, and that looks like a small jump.
- Everything `simulate` changes must be in the state, so `apply` can put it back.

## Step 4: the world

The same setup runs on the host and on every client: register each **kind** with a number of your choosing, its
state (and input, for pawns), and how to make a new one.

```java
// Inside the ArenaState class shown in step 5:
static final int SQUARE = 1;
static final int BALL = 2;

static FlixelNetWorld newWorld(FlixelNetSession session) {
  FlixelNetWorld world = new FlixelNetWorld(session);
  world.register(SQUARE, Messages.Spot.class, Messages.Arrows.class, Square::new);
  world.register(BALL, Messages.Spot.class, Ball::new);
  return world;
}
```

When the host spawns a kind, every client uses its own registration to build the matching object.

Only the host decides what exists. It spawns with `world.spawn(kind, owner)`, where the **owner** is the peer
the entity belongs to. Things nobody steers, like the ball, belong to the host (`FlixelNetSession.HOST_ID`). A
player who joins late is automatically told about everything that already exists.

## Step 5: the game state

This state is the host, a player, or both at once, using the "host that also plays" idea from
[Getting started](getting-started.md#a-host-that-also-plays-in-one-window): the host runs a host-only world, and
the local player joins it through a loopback like any other client. Only the player's world is ever drawn.

```java
public class ArenaState extends FlixelState {

  private final boolean hosting;
  private final String address;
  private final FlixelIntMap<FlixelSprite> sprites = new FlixelIntMap<>();

  // The host's side: decides what is true. Null when only joining.
  private FlixelNetSession hostSession;
  private FlixelNetWorld hostWorld;
  private FlixelNetLoopback loopback;

  // The local player's side: what this screen shows.
  private FlixelNetSession session;
  private FlixelNetWorld world;

  /**
   * @param hosting Whether this window hosts. When it does, its own player joins through a loopback.
   * @param address Where to join when not hosting, such as "192.168.1.20:7777".
   */
  public ArenaState(boolean hosting, String address) {
    this.hosting = hosting;
    this.address = address;
  }

  @Override
  public void create() {
    super.create();

    if (hosting) {
      hostSession = new FlixelNetSession(Messages.protocol());
      hostWorld = newWorld(hostSession);
      // Every player that joins gets a square, and loses it when they leave.
      hostSession.onConnect.add(peer -> {
        Square square = hostWorld.spawn(SQUARE, peer.getId());
        square.x = 100 + peer.getId() * 60;
        square.y = 100;
      });
      hostSession.onDisconnect.add(peer -> hostWorld.despawnOwnedBy(peer.getId()));
      FlixelNet.host(hostSession, 7777);
    }

    session = new FlixelNetSession(Messages.protocol());
    world = newWorld(session);
    world.onSpawn.add(this::showEntity);
    world.onDespawn.add(this::hideEntity);

    if (hosting) {
      loopback = FlixelNet.joinLocal(hostSession, session);
      // Spawning needs a session that is already hosting, so the ball comes after host(...).
      hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    } else {
      FlixelNet.join(session, address);
    }
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);

    if (hostSession != null) {
      loopback.advance(elapsed);
      hostSession.update(elapsed);
      hostWorld.update(elapsed);
    }
    session.update(elapsed);
    world.update(elapsed);

    // Draw every entity where the player's world says it is.
    for (int i = 0, n = world.getEntityCount(); i < n; i++) {
      FlixelNetEntity<?> entity = world.getEntityAt(i);
      FlixelSprite sprite = sprites.get(entity.getId());
      if (entity instanceof Square square) {
        sprite.setPosition(square.x, square.y);
      } else if (entity instanceof Ball ball) {
        sprite.setPosition(ball.x, ball.y);
      }
    }
  }

  @Override
  public void destroy() {
    world.destroy();
    session.close();
    if (hostSession != null) {
      hostWorld.destroy();
      hostSession.close();
    }
    super.destroy();
  }

  private void showEntity(FlixelNetEntity<?> entity) {
    FlixelSprite sprite = new FlixelSprite();
    if (entity instanceof Ball) {
      sprite.makeGraphic(24, 24, new FlixelColor(245, 245, 245, 1f));
    } else if (entity.isOwned()) {
      sprite.makeGraphic(32, 32, new FlixelColor(240, 120, 90, 1f));   // Our own square.
    } else {
      sprite.makeGraphic(32, 32, new FlixelColor(90, 190, 240, 1f));   // Everyone else's.
    }
    sprites.put(entity.getId(), sprite);
    add(sprite);
  }

  private void hideEntity(FlixelNetEntity<?> entity) {
    FlixelSprite sprite = sprites.remove(entity.getId());
    if (sprite != null) {
      remove(sprite);
    }
  }
}
```

### What is going on

- **Update the world right after its session**, every frame. The session delivers what arrived; the world then
  moves things, runs ticks and sends pictures.
- **`onSpawn` and `onDespawn`** are where the screen catches up with the world: make a sprite when an entity
  appears, remove it when it goes.
- **`entity.isOwned()`** is true for entities this computer controls. Here it picks the color of your own square.
- **`entity.getId()`** is the same number on every computer, which makes it a good key for the sprite map.
- **The host's own square** arrives like everyone else's: joining through the loopback fires `onConnect` on the
  host session, which spawns it.

Try it the same way as before: one window hosts, another joins `127.0.0.1:7777`. Your own square responds
instantly in both windows, the other square and the ball glide smoothly, and closing a window removes its square
everywhere.

## Two clocks

| Setting | Default | What it is |
| --- | --- | --- |
| `world.setTickRate(60)` | 60 | How often the simulation moves. `step` and `simulate` run this many times a second with a fixed step length. Must be the same on every computer. |
| `world.setSnapshotRate(20)` | 20 | How often the host sends pictures. Higher is smoother and uses more bandwidth. |
| `world.setInterpolationDelay(0.1f)` | 0.1 s | How far in the past clients show entities they do not control. |

Why show the past? To slide between two pictures, the client needs to already have the *next* one. Showing
entities a tenth of a second late means there are always two pictures to slide between, even if one is lost on
the way. Players do not notice the delay on things they do not control, and their own pawn is not delayed at
all.

For experimenting, `world.setInterpolation(false)` and `world.setPrediction(false)` switch the two tricks off so
you can see what each one does.

## What it costs

- An entity that is not changing is not sent, apart from an occasional reminder. A level with hundreds of resting
  crates costs almost nothing.
- Only `float` and `double` fields are smoothed. Other fields (a health number, an animation name) switch over
  when the newer picture is reached.
- One state must fit in a single packet, about a thousand bytes. A state is normally a few dozen.
- Angles are smoothed like any other number, so a turn from 350 to 10 degrees goes the long way round. If that
  matters, send the direction as two numbers (`cos` and `sin`) instead of one angle.

## Mixing with messages

The world shares the session with your own messages. One-off events fit messages better than states: send a
reliable `Explosion` message and play the sound in its handler, and leave the world to say where things are.

## Common mistakes

| Symptom | Cause |
| --- | --- |
| `Only the host can spawn entities` | `spawn` was called before `FlixelNet.host(...)`, or on a client. |
| Your own square stutters or jumps back | `simulate` reads something that is not in the state or input, so the host disagrees with the guess. |
| Entities never appear on a client | The client's world was not registered the same way, or `world.update(elapsed)` is never called. |
| Joining fails with `VERSION_MISMATCH` | `FlixelNetWorld.addMessages(protocol)` was called on one side only, or the message lists differ. |
| Other things jump instead of gliding | Smoothing is off, or the field is not a `float` or `double`. |
| Everything is a little behind | That is the interpolation delay, on purpose; your own pawn is never delayed. |

Next: [Dedicated servers](dedicated-server.md)
