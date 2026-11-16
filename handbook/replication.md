# 3. Replication

## Why

With messages alone, most of a real-time game's network code is the same sentence over and over: "this thing
is here now". You write a message for it, send it for every object many times a second, smooth the result on
the other side so it does not stutter, and handle players who join late. **Replication** does all of that for
you.

Think of the host as a stage and the clients as an audience watching a live broadcast. The host puts actors on
the stage and moves them. A camera films the stage a few times a second. Each client rebuilds the same actors
and moves them to match the pictures, sliding smoothly from one picture to the next.

In code, the stage is a `FlixelNetWorld`, an actor is a `FlixelNetEntity`, and a picture of one actor is its
**state**: a small `@NetMessage` class.

## An entity

An entity answers two questions.

- `capture`: "what does the picture look like right now?" Asked on the host.
- `apply`: "make the game look like this picture." Asked on the clients.

```java
@NetMessage
public class BallState {
  @NetRange(min = 0, max = 1280) public float x;
  @NetRange(min = 0, max = 720)  public float y;
}

public class Ball extends FlixelNetEntity<BallState> {
  public final FlixelSprite sprite = new FlixelSprite();
  public float vx = 200, vy = 150;

  @Override
  protected void capture(BallState out) {
    out.x = sprite.getX();
    out.y = sprite.getY();
  }

  @Override
  protected void apply(BallState state) {
    sprite.setPosition(state.x, state.y);
  }

  @Override
  protected void step(float dt) {               // Host only: the ball's own movement.
    sprite.setPosition(sprite.getX() + vx * dt, sprite.getY() + vy * dt);
  }
}
```

Only the host runs `step`. Clients never move the ball themselves; they are shown where it was.

## Setting up the world

The same setup runs on the host and on every client:

```java
FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1)
    .add(BallStateNetCodec.INSTANCE)
    .add(PlayerStateNetCodec.INSTANCE)
    .add(PlayerInputNetCodec.INSTANCE);
FlixelNetWorld.addMessages(protocol);            // The world's own messages.
FlixelNetSession session = new FlixelNetSession(protocol);

FlixelNetWorld world = new FlixelNetWorld(session);
world.register(BALL, BallState.class, Ball::new);                          // BALL = 1
world.register(PLAYER, PlayerState.class, PlayerInput.class, Player::new); // PLAYER = 2

world.onSpawn.add(entity -> { if (entity instanceof Ball ball) add(ball.sprite); });
world.onDespawn.add(entity -> { if (entity instanceof Ball ball) remove(ball.sprite); });
```

Each **kind** gets a number of your choosing and a way to make a new one. When the host spawns a kind, every
client uses its own factory to build the matching object, and `onSpawn` is your chance to put its sprite on
screen.

Every frame, update the world right after the session:

```java
session.update(elapsed);
world.update(elapsed);
```

## Spawning

Only the host decides what exists.

```java
Ball ball = world.spawn(BALL, FlixelNetSession.HOST_ID);
ball.sprite.setPosition(640, 360);     // Clients hear about it on the next update, already in place.

world.despawn(ball);
```

A player who joins late is automatically told about everything that already exists.

A typical host gives each player something to control, and cleans up when they leave:

```java
session.onConnect.add(peer -> world.spawn(PLAYER, peer.getId()));
session.onDisconnect.add(peer -> world.despawnOwnedBy(peer.getId()));
```

The second argument of `spawn` is the entity's **owner**: the peer it belongs to.

## Pawns: things a player controls

If a player's character only moved when the host said so, every key press would travel to the host and back
before anything happened on screen. With 100 milliseconds of lag the game would feel like walking through mud.

A `FlixelNetPawn` fixes that the way a chess player thinks ahead. The player's computer makes the move right
away and remembers it. The host makes the same move for real. When the host's answer comes back, the computer
checks its guess; if the host disagrees, the pawn is put where the host says and the moves the host has not
seen yet are played again on top. This is called *prediction*, and when it works the player feels no lag at all.

A pawn answers two more questions:

- `sample`: "what are the controls doing right now?" Asked on the owner's computer.
- `simulate`: "move one tick using these controls." Asked on the owner's computer **and** on the host, so it
  must do exactly the same thing on both.

```java
@NetMessage
public class PlayerInput {
  public boolean left, right, up, down;
}

public class Player extends FlixelNetPawn<PlayerState, PlayerInput> {
  public final FlixelSprite sprite = new FlixelSprite();
  public float x, y;

  @Override
  protected void sample(PlayerInput out) {
    out.left = leftIsHeld();        // Read your keys, gamepad or touch controls here.
    out.right = rightIsHeld();
    out.up = upIsHeld();
    out.down = downIsHeld();
  }

  @Override
  protected void simulate(PlayerInput input, float dt) {
    if (input.left) { x -= 240 * dt; }
    if (input.right) { x += 240 * dt; }
    if (input.up) { y -= 240 * dt; }
    if (input.down) { y += 240 * dt; }
  }

  @Override
  protected void capture(PlayerState out) { out.x = x; out.y = y; }

  @Override
  protected void apply(PlayerState state) { x = state.x; y = state.y; }
}
```

Draw the sprite at `x, y` in your state's `update`.

Notice that the client never sends its position, only its controls. The host works out where the pawn ends up,
so a tampered client cannot teleport. The host also refuses to handle more inputs per second than there are
ticks, so sending extra inputs does not make a pawn faster.

**Rules for a pawn that predicts well:**

- `simulate` should depend only on the input and on values that are part of the state. If it reads anything
  else (a timer, another entity, a random number), the client's guess can differ from the host's, and the pawn
  gets corrected, which looks like a small jump.
- Everything `simulate` changes must be in the state, so `apply` can put it back.

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

- An entity that is not changing is not sent, apart from an occasional reminder. A level with hundreds of
  resting crates costs almost nothing.
- Only `float` and `double` fields are smoothed. Other fields (a health number, an animation name) switch over
  when the newer picture is reached.
- One state must fit in a single packet, about a thousand bytes. A state is normally a few dozen.
- Angles are smoothed like any other number, so a turn from 350 to 10 degrees goes the long way round. If that
  matters, send the direction as two numbers (`cos` and `sin`) instead of one angle.

## Mixing with messages

The world shares the session with your own messages. One-off events fit messages better than states: send a
reliable `Explosion` message and play the sound in its handler, and leave the world to say where things are.

Next: [Dedicated servers](dedicated-server.md)
