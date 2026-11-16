# 5. Rollback

On this page you build a tiny two-player race, using the other way to do real-time multiplayer: every computer
runs the whole game, and only the players' controls travel.

## The idea

Imagine two people playing chess by mail, each with their own board. Nobody mails the board. They only mail their
moves, and because both follow the same rules, both boards stay identical.

Rollback multiplayer does that sixty times a second. Every computer runs the **whole game**, and only the
players' **controls** go over the network.

Mail is never instant, though, and a game cannot freeze while it waits. So each computer *guesses* that the other
players are still pressing what they pressed a moment ago, and carries on. Most of the time the guess is right,
because people hold a direction for many frames in a row. When the real controls arrive and the guess was wrong,
the game quietly **rewinds** to that moment, plays those few ticks again with the right controls, and is back in
the present before the next picture is drawn. That rewind is the rollback.

The payoff: your own controls respond instantly, always, however far away the other player is. That is why
fighting games use it.

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

The part of your game that decides what happens (positions, scores, timers) is the **simulation**. Drawing,
sound and menus are not part of it; they only look at the simulation after each update. The simulation is a
class implementing `FlixelRollbackGame`, which has four methods:

| Method | Question it answers | Called |
| --- | --- | --- |
| `sample` | "What are this computer's controls doing right now?" | Once per tick, for the local player only. |
| `step` | "Move the whole game one tick, using everyone's controls." | Once per tick, and again for every tick replayed by a rewind. |
| `save` | "Write down everything `step` can change." | Before ticks that might be rewound. |
| `load` | "Put everything back the way `save` wrote it." | When rewinding. |

## Step 1: the input message

Controls are a `@NetMessage` like any other, and must be in the protocol along with the rollback session's own
message:

```java
public final class Messages {

  private Messages() {}

  public static FlixelNetProtocol protocol() {
    FlixelNetProtocol protocol = new FlixelNetProtocol("coin-race", 1)
      .add(MessagesPadNetCodec.INSTANCE);
    FlixelRollbackSession.addMessages(protocol);
    return protocol;
  }

  /** One player's controls for one tick. */
  @NetMessage
  public static class Pad {
    public boolean left;
    public boolean right;
    public boolean up;
    public boolean down;
  }
}
```

Keep inputs small: they are sent every tick, and a few booleans pack into a single byte.

## Step 2: the simulation

Two squares race to touch a coin. Whoever touches it scores, and the coin jumps somewhere else.

```java
public class CoinRace implements FlixelRollbackGame<Messages.Pad> {

  public static final int SIZE = 32;
  public static final int COIN = 16;
  private static final int SPEED = 5;   // Pixels per tick. Never "speed * elapsed".

  public final int[] x = { 200, 1000 };
  public final int[] y = { 360, 360 };
  public final int[] score = new int[2];
  public int coinX = 632;
  public int coinY = 352;

  private FlixelRollbackRandom random;

  /** Gives the simulation the match's random numbers. Call once, before the match starts. */
  public void setRandom(FlixelRollbackRandom random) {
    this.random = random;
  }

  @Override
  public void sample(Messages.Pad out) {
    out.left = Flixel.keys.pressed(FlixelKey.LEFT);
    out.right = Flixel.keys.pressed(FlixelKey.RIGHT);
    out.up = Flixel.keys.pressed(FlixelKey.UP);
    out.down = Flixel.keys.pressed(FlixelKey.DOWN);
  }

  @Override
  public void step(FlixelRollbackInputs<Messages.Pad> inputs) {
    for (int p = 0; p < inputs.getCount(); p++) {
      Messages.Pad pad = inputs.get(p);
      if (pad.left) {
        x[p] -= SPEED;
      }
      if (pad.right) {
        x[p] += SPEED;
      }
      if (pad.up) {
        y[p] -= SPEED;
      }
      if (pad.down) {
        y[p] += SPEED;
      }
      x[p] = Math.max(0, Math.min(1280 - SIZE, x[p]));
      y[p] = Math.max(0, Math.min(720 - SIZE, y[p]));
    }
    // Players are checked in a fixed order, so a tie goes the same way on every computer.
    for (int p = 0; p < inputs.getCount(); p++) {
      boolean touching = x[p] < coinX + COIN && x[p] + SIZE > coinX && y[p] < coinY + COIN && y[p] + SIZE > coinY;
      if (touching) {
        score[p]++;
        coinX = random.nextInt(1280 - COIN);
        coinY = random.nextInt(720 - COIN);
      }
    }
  }

  @Override
  public void save(FlixelNetBuffer out) {
    for (int p = 0; p < 2; p++) {
      out.writeVarInt(x[p]);
      out.writeVarInt(y[p]);
      out.writeVarInt(score[p]);
    }
    out.writeVarInt(coinX);
    out.writeVarInt(coinY);
  }

  @Override
  public void load(FlixelNetBuffer in) {
    for (int p = 0; p < 2; p++) {
      x[p] = in.readVarInt();
      y[p] = in.readVarInt();
      score[p] = in.readVarInt();
    }
    coinX = in.readVarInt();
    coinY = in.readVarInt();
  }
}
```

Things to notice:

- **Whole numbers and fixed steps.** Positions are `int`s moved by 5 pixels per tick, never by `elapsed`. Every
  computer gets exactly the same numbers.
- **The match's own random numbers.** `random` is handed over by the rollback session (step 3). It gives every player
  the same numbers, and the session saves and restores it with each rewind for you, so it never appears in `save`.
- **`save` and `load` mirror each other.** Same values, same order.
- **No drawing and no sound.** The game state draws after each update, in step 4.

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

## Step 3: the match

A rollback session runs on top of an ordinary network session:

```java
FlixelNetSession session = new FlixelNetSession(Messages.protocol());
CoinRace race = new CoinRace();
FlixelRollbackSession<Messages.Pad> match = new FlixelRollbackSession<>(session, Messages.Pad.class, race);
race.setRandom(match.getRandom());   // The simulation's random numbers come from the match.
```

Then host or join exactly as on the earlier pages. The host is player 0, and the others are numbered in the order
they joined. `match.getLocalPlayer()` tells each computer which one it is.

## Step 4: the game state

```java
public class RaceState extends FlixelState {

  private final boolean hosting;
  private final String address;
  private final FlixelSprite[] squares = new FlixelSprite[2];
  private FlixelSprite coin;
  private FlixelNetSession session;
  private FlixelRollbackSession<Messages.Pad> match;
  private CoinRace race;

  public RaceState(boolean hosting, String address) {
    this.hosting = hosting;
    this.address = address;
  }

  @Override
  public void create() {
    super.create();
    squares[0] = box(CoinRace.SIZE, new FlixelColor(240, 120, 90, 1f));
    squares[1] = box(CoinRace.SIZE, new FlixelColor(90, 190, 240, 1f));
    coin = box(CoinRace.COIN, new FlixelColor(250, 215, 70, 1f));

    session = new FlixelNetSession(Messages.protocol());
    race = new CoinRace();
    match = new FlixelRollbackSession<>(session, Messages.Pad.class, race);
    race.setRandom(match.getRandom());

    match.onStart.add(_ -> Flixel.info("Go! You are player " + match.getLocalPlayer()));
    match.onDesync.add(tick -> Flixel.info("The computers disagree at tick " + tick));
    match.onPlayerLeft.add(player -> Flixel.info("Player " + player + " left"));
    match.onEnd.add(_ -> Flixel.info("The host left; the match is over"));

    if (hosting) {
      // Start the match as soon as the second player is in. Anyone joining later is not part of it.
      session.onConnect.add(peer -> {
        if (!match.isRunning()) {
          match.start();
        }
      });
      FlixelNet.host(session, 7777);
    } else {
      FlixelNet.join(session, address);
    }
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);
    session.update(elapsed);
    match.update(elapsed);    // Runs as many ticks as are due, rewinding when needed.

    // Drawing only looks at the simulation; it never changes it.
    boolean running = match.isRunning();
    for (int p = 0; p < 2; p++) {
      squares[p].setVisible(running);
      squares[p].setPosition(race.x[p], race.y[p]);
    }
    coin.setVisible(running);
    coin.setPosition(race.coinX, race.coinY);
  }

  @Override
  public void destroy() {
    match.destroy();
    session.close();
    super.destroy();
  }

  private FlixelSprite box(int size, FlixelColor color) {
    FlixelSprite sprite = new FlixelSprite();
    sprite.makeGraphic(size, size, color);
    sprite.setVisible(false);
    add(sprite);
    return sprite;
  }
}
```

Update the match right after the session, every frame, and draw from the simulation afterwards. Run it like the
arena: one window hosts, another joins `127.0.0.1:7777`, and the race starts the moment the second player arrives.

### Starting a match

Only the host calls `match.start()`, and only once everyone is in: anyone who joins later is not part of that
match. Here it starts as soon as one player joins; a real game would wait for a "Start" button. `start()` sends
the tick rate, input delay, prediction limit and a shared random seed to every player, so they only need to be
set on the host.

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

## Common mistakes

| Symptom | Cause |
| --- | --- |
| `onDesync` fires, and the screens slowly disagree | `step` broke the golden rule: it used `elapsed`, another random source, or changed a value `save` does not write. |
| `onDesync` right after a coin is picked up | The random numbers did not come from `match.getRandom()`. |
| Nothing moves | `match.update(elapsed)` is not called, or the host never called `match.start()`. |
| `IllegalStateException` from `start()` | It was called on a client, before anyone joined, or twice. |
| Joining fails with `VERSION_MISMATCH` | `FlixelRollbackSession.addMessages(protocol)` was called on one side only. |
| The game slows down when one player lags badly | That is a stall: a computer that gets too far ahead waits for the slowest one, on purpose. Raise the input delay, or accept it. |

Next: [Native libraries and licenses](natives-and-licenses.md)
