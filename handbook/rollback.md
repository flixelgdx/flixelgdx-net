# 5. Rollback

## The idea

Imagine two people playing chess by mail who each keep their own board. Nobody mails the board. They only mail
moves, and because both follow the same rules, both boards stay identical.

Rollback multiplayer does that sixty times a second. Every computer runs the **whole game**, and only the
players' **controls** travel over the network.

Mail is never instant, though, and a game cannot freeze while it waits. So each computer *guesses* that the
other players are still doing what they did a moment ago, and carries on. Most of the time the guess is right,
because people hold a direction for many frames in a row. When the real controls arrive and the guess was wrong,
the game quietly **rewinds** to that moment, plays those few ticks again with the right controls, and is back in
the present before the next picture is drawn. That rewind is the rollback.

The payoff: your own controls respond instantly, always, no matter the lag. That is why fighting games use it.

## Is it right for my game?

| | Replication | Rollback |
| --- | --- | --- |
| Players | Many | A few (up to 8 here) |
| Who decides what is true | The host | Everyone, identically |
| What travels | Pictures of objects | Controls |
| What it asks of your code | Little | The simulation must be deterministic and able to save and load itself |
| Late joiners | Easy | Not supported |

Choose rollback for fast games with few players where every frame of response matters. Choose
[replication](replication.md) for nearly everything else.

## What your game must provide

The part of your game that decides what happens (positions, health, timers) is the **simulation**. Drawing,
sound and menus are not part of it; they only look at the simulation after each update. The simulation
implements four methods:

```java
@NetMessage
public class Pad {
  public boolean left, right, jump;
}

public class Duel implements FlixelRollbackGame<Pad> {

  public final float[] x = new float[2];
  public final int[] health = { 100, 100 };

  @Override
  public void sample(Pad out) {                    // Read this player's controls.
    out.left = leftIsHeld();
    out.right = rightIsHeld();
    out.jump = jumpIsHeld();
  }

  @Override
  public void step(FlixelRollbackInputs<Pad> inputs) {   // One tick, for everyone.
    for (int p = 0; p < inputs.getCount(); p++) {
      Pad pad = inputs.get(p);
      if (pad.left) { x[p] -= 4; }
      if (pad.right) { x[p] += 4; }
    }
  }

  @Override
  public void save(FlixelNetBuffer out) {          // Everything step() can change...
    out.writeFloat(x[0]);
    out.writeFloat(x[1]);
    out.writeVarInt(health[0]);
    out.writeVarInt(health[1]);
  }

  @Override
  public void load(FlixelNetBuffer in) {           // ...read back in the same order.
    x[0] = in.readFloat();
    x[1] = in.readFloat();
    health[0] = in.readVarInt();
    health[1] = in.readVarInt();
  }
}
```

## The golden rule: determinism

`step` must produce **exactly** the same result on every computer, every time, given the same state and the
same inputs. If one board ends up with a pawn one square off, the players are no longer playing the same game.
In practice:

- **No clocks and no frame time.** Every tick is the same fixed length. Use constants like "4 pixels per tick",
  not `speed * elapsed`.
- **Random numbers only from `match.getRandom()`.** It gives every player the same numbers and rewinds with the
  game. Any other random source will pull the players apart.
- **No sounds or effects inside `step`.** A tick can be played more than once. Instead, look at the state after
  the update and react to what changed (the health went down, so play the hit sound).
- **Save everything.** Anything `step` changes must be written by `save` and restored by `load`. A forgotten
  field is the most common bug.
- **Mind your math.** Whole numbers behave identically everywhere. Simple `float` arithmetic (add, subtract,
  multiply, divide) does too. Functions such as `Math.sin` or `Math.pow` are not guaranteed to give the same
  last digit on every device; prefer whole numbers or lookup tables for those in a cross-platform game.
- **Loop in a fixed order.** Go through players and objects by index, never through a collection whose order
  can differ between computers.

## Running a match

```java
FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1).add(PadNetCodec.INSTANCE);
FlixelRollbackSession.addMessages(protocol);
FlixelNetSession session = new FlixelNetSession(protocol);

Duel duel = new Duel();
FlixelRollbackSession<Pad> match = new FlixelRollbackSession<>(session, Pad.class, duel);

// Host or join exactly as before. Then, on the host, once everyone is in:
match.start();

// Every frame, on every computer:
session.update(elapsed);
match.update(elapsed);
drawFrom(duel);     // Show the simulation as it is now.
```

The host is player 0 and the others are numbered in the order they joined. `match.getLocalPlayer()` tells each
computer which one it is, and `match.onStart` fires when the match begins.

## Tuning

| Setting (on the host, before `start()`) | Default | What it does |
| --- | --- | --- |
| `setInputDelay(ticks)` | 2 | Holds the local controls back a few ticks, giving them time to reach the others before they are needed. Two ticks is about 33 ms, which few players feel, and it removes most rewinds on a good connection. |
| `setMaxPrediction(ticks)` | 8 | How far the game may run on guesses. A computer that gets further ahead than this waits for the slowest connection instead. It is also the longest possible rewind. |
| `setTickRate(perSecond)` | 60 | Ticks per second. |

Clients receive these from the host, so set them in one place.

## Knowing how it is going

| Call | Meaning |
| --- | --- |
| `match.getRollbacks()` | How many rewinds so far. |
| `match.getRollbackDepth()` | How many ticks the last rewind replayed. |
| `match.getStalls()` | Ticks skipped while waiting for a slower player. |
| `match.getChecksum()`, `getChecksumFrame()` | A fingerprint of the game state at a tick. Two players in step show the same number for the same tick. |

Twice a second the computers compare fingerprints. If two differ, `match.onDesync` fires with the tick number.
A desync always means the golden rule was broken somewhere; show the two fingerprints on screen while
developing, and when they part ways, look for a value `step` changes that `save` forgot, or a random number
that did not come from `match.getRandom()`.

## When a player leaves

If a player disconnects, `match.onPlayerLeft` fires with their number and their controls go idle from the same
tick on every computer, so the match stays in step and can continue. If the host leaves, the match is over and
`match.onEnd` fires.

## Keeping `step` cheap

A rewind replays several ticks inside a single frame, so `step`, `save` and `load` are called far more often
than once per frame. Keep them small and free of allocations: plain fields, arrays, and loops.

## How the controls travel

The controls are relayed through the host, so players never need to reach each other directly, and the match
works over every kind of connection the extension has, the web included.

Next: [Native libraries and licenses](natives-and-licenses.md)
