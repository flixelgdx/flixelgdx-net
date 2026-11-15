/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.flixelgdx.net.rollback;

import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetException;
import org.flixelgdx.net.FlixelNetHandler;
import org.flixelgdx.net.FlixelNetPeer;
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.message.FlixelNetCodec;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.signal.FlixelSignal;
import org.flixelgdx.signal.FlixelSignal.SignalHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps a game in step on every player's computer by sending only the players' controls.
 *
 * <h2>The idea</h2>
 *
 * <p>Imagine two people playing chess by mail who each keep their own board. Nobody mails the
 * board; they only mail moves, and because both follow the same rules, both boards stay the
 * same. A rollback session does that sixty times a second: every computer runs the whole game,
 * and only the controls travel.
 *
 * <p>The mail is never instant, though, and a game cannot freeze while it waits. So each
 * computer <b>guesses</b> that the other players are still doing what they did a moment ago,
 * and carries on. When the real controls arrive and the guess was wrong, the session quietly
 * rewinds the game to that moment ({@link FlixelRollbackGame#load}), plays the ticks again with
 * the right controls ({@link FlixelRollbackGame#step}), and is back in the present before the
 * next picture is drawn. That rewind is the <i>rollback</i>.
 *
 * <p>The result feels as if there were no delay on your own controls at all, which is why
 * fighting games and other twitchy games for a few players use it. It asks more of the game
 * than replication does: the simulation must be deterministic and able to save and load itself,
 * see {@link FlixelRollbackGame}.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1).add(PadNetCodec.INSTANCE);
 * FlixelRollbackSession.addMessages(protocol);
 * FlixelNetSession session = new FlixelNetSession(protocol);
 * FlixelRollbackSession<Pad> match = new FlixelRollbackSession<>(session, Pad.class, myGame);
 *
 * // On the host, once everyone has joined:
 * match.start();
 *
 * // Every frame, on every computer:
 * session.update(elapsed);
 * match.update(elapsed);
 * // ... then draw the game as it is now.
 * }</pre>
 *
 * <h2>Good to know</h2>
 *
 * <ul>
 *   <li>Players are numbered from zero; the host is player zero. {@link #getLocalPlayer()} says
 *       which one this computer is.</li>
 *   <li>{@link #setInputDelay} holds the local controls back by a few ticks. A little delay is
 *       hard to feel and gives the controls time to arrive before they are needed, which means
 *       fewer rewinds.</li>
 *   <li>A computer that gets too far ahead of the slowest connection waits for it instead of
 *       guessing ever further ({@link #setMaxPrediction}).</li>
 *   <li>Every half second the computers compare a fingerprint of their game state. If two
 *       differ, the simulation is not deterministic, and {@link #onDesync} fires.</li>
 *   <li>The controls are relayed through the host, so players do not need to reach each other
 *       directly.</li>
 * </ul>
 *
 * @param <I> The input class, a {@link NetMessage} that must be part of the session's protocol.
 */
public final class FlixelRollbackSession<I> {

  /** The most players one match can have. */
  public static final int MAX_PLAYERS = 8;

  /** How many ticks run per second unless {@link #setTickRate} says otherwise. */
  public static final int DEFAULT_TICK_RATE = 60;

  private static final int START = 1;
  private static final int INPUT = 2;
  private static final int CHECK = 3;
  private static final int DROP = 4;

  /** How many inputs are remembered per player. Far more than can ever be outstanding. */
  private static final int RING = 256;

  /** How many ticks apart the fingerprints of the game state are compared. */
  private static final int CHECK_EVERY = 30;

  /** How many fingerprints are remembered while waiting for the other players' versions. */
  private static final int CHECKS = 16;
  private static final int MAX_STEPS = 8;
  private static final int NEVER = Integer.MAX_VALUE;

  /** Dispatched when the match begins, on the host by {@link #start()} and on clients soon after. */
  public final FlixelSignal<Void> onStart = new FlixelSignal<>();

  /**
   * Dispatched with a tick number when this computer's game state at that tick differs from
   * another player's. It means the simulation is not deterministic; the match cannot recover.
   */
  public final FlixelSignal<Integer> onDesync = new FlixelSignal<>();

  /** Dispatched with a player number when that player leaves. Their controls go idle. */
  public final FlixelSignal<Integer> onPlayerLeft = new FlixelSignal<>();

  /** Dispatched when the match is over because the host is gone. */
  public final FlixelSignal<Void> onEnd = new FlixelSignal<>();

  private final FlixelNetSession session;
  private final FlixelRollbackGame<I> game;
  private final FlixelNetCodec<I> codec;
  private final FlixelRollbackRandom random = new FlixelRollbackRandom();
  private final FlixelRollbackInputs<I> view = new FlixelRollbackInputs<>();
  private final FlixelArray<FlixelArray<I>> rings = new FlixelArray<>();
  private final FlixelArray<FlixelNetBuffer> states = new FlixelArray<>();
  private final FlixelNetBuffer before = new FlixelNetBuffer(32);
  private final FlixelNetBuffer after = new FlixelNetBuffer(32);
  private final FlixelNetHandler<FlixelRollbackPacket> packetHandler = this::onPacket;
  private final SignalHandler<FlixelNetPeer> disconnectHandler = this::onDisconnect;

  /** For each state slot, the tick whose start it holds. */
  private int[] stateFrames = new int[0];

  /** For each player, the newest tick whose real input is known. */
  private final int[] received = new int[MAX_PLAYERS];

  /** For each player, the tick from which they are gone, or {@link #NEVER}. */
  private final int[] goneFrom = new int[MAX_PLAYERS];

  /** For each player, how far ahead of the others they last said they were, in ticks. */
  private final int[] lead = new int[MAX_PLAYERS];

  /** For each player, the peer id behind them. Only the host needs it. */
  private final int[] peers = new int[MAX_PLAYERS];
  private final int[] sums = new int[CHECKS];
  private final int[] sumFrames = new int[CHECKS];
  private final int[][] theirSums = new int[MAX_PLAYERS][CHECKS];
  private final int[][] theirSumFrames = new int[MAX_PLAYERS][CHECKS];
  private int players;
  private int local = -1;
  private int frame;

  /** The next tick the local controls will be read for. */
  private int nextLocal;

  /** The oldest tick that must be played again, or {@link #NEVER} when none must. */
  private int rollbackTo = NEVER;
  private int lastCheck;
  private int checksum;
  private int checksumFrame = -1;
  private int rollbacks;
  private int rollbackDepth;
  private int stalls;
  private int ticks;
  private int tickRate = DEFAULT_TICK_RATE;
  private int inputDelay = 2;
  private int maxPrediction = 8;
  private float tickTimer;
  private boolean running;
  private boolean desynced;

  /**
   * Creates a rollback session that travels over a network session.
   *
   * @param session The network session. Its protocol must contain the input class and the
   *     rollback messages, see {@link #addMessages}.
   * @param input The input class.
   * @param game The simulation to keep in step.
   * @throws IllegalArgumentException If the protocol is missing the input class or
   *     {@link #addMessages} was not called on it.
   */
  public FlixelRollbackSession(@NotNull FlixelNetSession session, @NotNull Class<I> input,
      @NotNull FlixelRollbackGame<I> game) {
    this.session = session;
    this.game = game;
    FlixelNetProtocol protocol = session.getProtocol();
    codec = protocol.entry(input).getCodec();
    protocol.entry(FlixelRollbackPacket.class);
    session.on(FlixelRollbackPacket.class, packetHandler);
    session.onDisconnect.add(disconnectHandler);
  }

  /**
   * Adds the messages a rollback session needs to a protocol. Call this once, before the
   * protocol is given to a network session.
   *
   * @param protocol The protocol the network session will use.
   */
  public static void addMessages(@NotNull FlixelNetProtocol protocol) {
    protocol.add(FlixelRollbackPacket.CODEC);
  }

  /**
   * Begins the match with everyone who has joined so far. Only the host can do this.
   *
   * <p>The host becomes player zero and the joined peers become players one and up, in the
   * order they joined. The tick rate, input delay and prediction limit set on the host are sent
   * along, so clients do not have to set them. Anyone who joins later is not part of the match.
   *
   * @throws IllegalStateException If this computer is not the host, nobody has joined, or the
   *     match is already running.
   */
  public void start() {
    if (!session.isHost()) {
      throw new IllegalStateException("Only the host can start the match.");
    }
    if (running) {
      throw new IllegalStateException("The match is already running.");
    }
    int count = Math.min(session.getPeerCount(), MAX_PLAYERS - 1);
    if (count == 0) {
      throw new IllegalStateException("A match needs at least one other player.");
    }
    long seed = System.nanoTime() * 0x9E3779B97F4A7C15L;
    peers[0] = FlixelNetSession.HOST_ID;
    for (int i = 0; i < count; i++) {
      FlixelNetPeer peer = session.getPeerAt(i);
      peers[i + 1] = peer.getId();
      FlixelRollbackPacket packet = session.obtain(FlixelRollbackPacket.class);
      FlixelNetBuffer body = packet.body;
      body.writeByte(START);
      body.writeByte(i + 1);
      body.writeByte(count + 1);
      body.writeByte(inputDelay);
      body.writeByte(maxPrediction);
      body.writeShort(tickRate);
      body.writeLong(seed);
      session.sendTo(peer, packet, FlixelNetChannel.RELIABLE);
    }
    begin(0, count + 1, seed);
  }

  /**
   * Runs the match for one frame. Call it every frame, right after the network session's own
   * update, and draw the game afterwards.
   *
   * @param elapsed Seconds since the last frame.
   */
  public void update(float elapsed) {
    if (!running) {
      return;
    }
    float length = 1f / tickRate;
    tickTimer += elapsed;
    int steps = 0;
    while (tickTimer >= length && steps < MAX_STEPS && running) {
      tickTimer -= length;
      steps++;
      tick();
    }
    if (tickTimer >= length) {
      tickTimer = 0f;
    }
  }

  /** Stops the match on this computer and disconnects from the network session. */
  public void destroy() {
    running = false;
    session.off(FlixelRollbackPacket.class, packetHandler);
    session.onDisconnect.remove(disconnectHandler);
  }

  private void begin(int slot, int count, long seed) {
    local = slot;
    players = count;
    frame = 0;
    rollbackTo = NEVER;
    lastCheck = 0;
    checksum = 0;
    checksumFrame = -1;
    rollbacks = 0;
    rollbackDepth = 0;
    stalls = 0;
    ticks = 0;
    tickTimer = 0f;
    desynced = false;
    random.setState(seed);
    rings.clear();
    for (int p = 0; p < count; p++) {
      FlixelArray<I> ring = new FlixelArray<>(RING);
      for (int i = 0; i < RING; i++) {
        ring.add(codec.create());
      }
      rings.add(ring);
      // The first few ticks happen before anyone's delayed controls can apply, so every player
      // is known to be idle for them.
      received[p] = inputDelay - 1;
      goneFrom[p] = NEVER;
      lead[p] = 0;
      for (int i = 0; i < CHECKS; i++) {
        theirSumFrames[p][i] = -1;
      }
    }
    for (int i = 0; i < CHECKS; i++) {
      sumFrames[i] = -1;
    }
    nextLocal = inputDelay;
    int slots = maxPrediction + 2;
    states.clear();
    stateFrames = new int[slots];
    for (int i = 0; i < slots; i++) {
      states.add(new FlixelNetBuffer());
      stateFrames[i] = -1;
    }
    view.rings = rings;
    view.ringSize = RING;
    running = true;
    onStart.dispatch(null);
  }

  private void tick() {
    ticks++;
    if (rollbackTo < frame) {
      replay(rollbackTo);
    }
    rollbackTo = NEVER;
    compare();

    // The slowest connection sets the pace: guessing further than this would make the eventual
    // rewind too long to hide.
    int slowest = NEVER;
    int ahead = 0;
    for (int p = 0; p < players; p++) {
      if (p == local || goneFrom[p] != NEVER) {
        continue;
      }
      slowest = Math.min(slowest, received[p]);
      // Their newest input was read "inputDelay" ticks before the tick it is for. Comparing how
      // far ahead each side thinks it is cancels out the travel time and leaves the real gap.
      int mine = frame - (received[p] - inputDelay + 1);
      ahead = Math.max(ahead, (mine - lead[p]) / 2);
    }
    if (slowest != NEVER && frame - (slowest + 1) >= maxPrediction) {
      stalls++;
      return;
    }
    // A computer that runs slightly fast would otherwise stay ahead for the whole match and
    // make everyone else rewind for it, so it lets a tick go by now and then.
    if (ahead >= 2 && ticks % (ahead >= 4 ? 2 : 8) == 0) {
      stalls++;
      return;
    }

    while (nextLocal <= frame + inputDelay) {
      I input = rings.get(local).get(nextLocal % RING);
      codec.reset(input);
      game.sample(input);
      received[local] = nextLocal;
      FlixelRollbackPacket packet = session.obtain(FlixelRollbackPacket.class);
      FlixelNetBuffer body = packet.body;
      body.writeByte(INPUT);
      body.writeByte(local);
      body.writeVarUInt(nextLocal);
      body.writeVarInt(slowest == NEVER ? 0 : frame - (slowest - inputDelay + 1));
      codec.write(input, body);
      session.send(packet, FlixelNetChannel.RELIABLE);
      nextLocal++;
    }

    advance();
  }

  /** Saves the state at the start of the current tick, then simulates it. */
  private void advance() {
    int slot = frame % stateFrames.length;
    FlixelNetBuffer state = states.get(slot);
    state.clear();
    state.writeLong(random.getState());
    game.save(state);
    stateFrames[slot] = frame;

    for (int p = 0; p < players; p++) {
      FlixelArray<I> ring = rings.get(p);
      if (frame >= goneFrom[p]) {
        codec.reset(ring.get(frame % RING));
      } else if (frame > received[p]) {
        // No real input yet: guess that the player keeps doing what they did last.
        if (received[p] >= 0) {
          codec.copy(ring.get(received[p] % RING), ring.get(frame % RING));
        } else {
          codec.reset(ring.get(frame % RING));
        }
      }
    }
    view.frame = frame;
    game.step(view);
    frame++;
  }

  /** Rewinds to the start of a tick and plays every tick since then again. */
  private void replay(int from) {
    int slot = from % stateFrames.length;
    if (stateFrames[slot] != from) {
      // The tick is older than anything remembered. It cannot happen while every player
      // respects the prediction limit, so treat it as the match having come apart.
      fail(from);
      return;
    }
    FlixelNetBuffer state = states.get(slot);
    state.rewind();
    random.setState(state.readLong());
    game.load(state);
    int target = frame;
    rollbacks++;
    rollbackDepth = target - from;
    frame = from;
    while (frame < target) {
      advance();
    }
  }

  /** Fingerprints the newest state every player's real inputs are known for, and shares it. */
  private void compare() {
    int confirmed = NEVER;
    for (int p = 0; p < players; p++) {
      if (goneFrom[p] == NEVER) {
        confirmed = Math.min(confirmed, received[p]);
      }
    }
    // The state at the start of tick c depends on the inputs of every tick before c.
    int limit = Math.min(confirmed == NEVER ? frame - 1 : confirmed + 1, frame - 1);
    int target = limit - limit % CHECK_EVERY;
    if (target <= lastCheck || target <= 0) {
      return;
    }
    int slot = target % stateFrames.length;
    if (stateFrames[slot] != target) {
      return;
    }
    lastCheck = target;
    FlixelNetBuffer state = states.get(slot);
    byte[] data = state.getData();
    int sum = 0x811C9DC5;
    for (int i = state.getOffset(), end = i + state.getSize(); i < end; i++) {
      sum = (sum ^ (data[i] & 0xFF)) * 0x01000193;
    }
    checksum = sum;
    checksumFrame = target;
    int index = target / CHECK_EVERY % CHECKS;
    sums[index] = sum;
    sumFrames[index] = target;
    FlixelRollbackPacket packet = session.obtain(FlixelRollbackPacket.class);
    packet.body.writeByte(CHECK);
    packet.body.writeByte(local);
    packet.body.writeVarUInt(target);
    packet.body.writeInt(sum);
    session.send(packet, FlixelNetChannel.RELIABLE);
    for (int p = 0; p < players; p++) {
      if (p != local && theirSumFrames[p][index] == target && theirSums[p][index] != sum) {
        fail(target);
      }
    }
  }

  private void fail(int at) {
    if (!desynced) {
      desynced = true;
      onDesync.dispatch(at);
    }
  }

  private void onPacket(FlixelNetPeer from, FlixelRollbackPacket packet) {
    FlixelNetBuffer in = packet.body;
    int op = in.readUByte();
    if (op == START) {
      if (session.isHost() || running) {
        return;
      }
      int slot = in.readUByte();
      int count = in.readUByte();
      int delay = in.readUByte();
      int prediction = in.readUByte();
      int rate = in.readUShort();
      long seed = in.readLong();
      if (count < 2 || count > MAX_PLAYERS || slot < 1 || slot >= count || delay > 30 || prediction < 1
          || prediction > 60 || rate < 1 || rate > 240) {
        throw new FlixelNetException("The host sent match settings that make no sense.");
      }
      inputDelay = delay;
      maxPrediction = prediction;
      tickRate = rate;
      begin(slot, count, seed);
      return;
    }
    if (!running) {
      return;
    }
    int player = in.readUByte();
    if (player >= players || player == local) {
      throw new FlixelNetException("A rollback packet names player " + player + ", who cannot have sent it.");
    }
    if (session.isHost()) {
      // A client may only speak for itself. What it says is passed on to the other clients.
      if (peers[player] != from.getId() || op == DROP) {
        throw new FlixelNetException("Peer " + from.getId() + " sent a rollback packet it is not allowed to send.");
      }
      if (players > 2) {
        FlixelRollbackPacket relay = session.obtain(FlixelRollbackPacket.class);
        relay.body.writeBuffer(in);
        session.sendExcept(from, relay, FlixelNetChannel.RELIABLE);
      }
    }
    switch (op) {
      case INPUT -> readInput(player, in);
      case CHECK -> {
        int at = in.readVarUInt();
        int sum = in.readInt();
        int index = at / CHECK_EVERY % CHECKS;
        theirSums[player][index] = sum;
        theirSumFrames[player][index] = at;
        if (sumFrames[index] == at && sums[index] != sum) {
          fail(at);
        }
      }
      case DROP -> drop(player, in.readVarUInt());
      default -> {
        // From a newer build; nothing to do with it.
      }
    }
  }

  private void readInput(int player, FlixelNetBuffer in) {
    int at = in.readVarUInt();
    int theirLead = in.readVarInt();
    if (goneFrom[player] != NEVER || at <= received[player]) {
      return;
    }
    if (at != received[player] + 1 || at - frame > RING / 2) {
      throw new FlixelNetException("Player " + player + " sent the input for tick " + at + " out of turn.");
    }
    lead[player] = theirLead;
    I slot = rings.get(player).get(at % RING);
    boolean used = at < frame;
    if (used) {
      before.clear();
      codec.write(slot, before);
    }
    codec.read(slot, in);
    received[player] = at;
    if (used) {
      after.clear();
      codec.write(slot, after);
      if (!same(before, after)) {
        rollbackTo = Math.min(rollbackTo, at);
      }
    }
  }

  /** Marks a player as gone from a tick on; their controls are idle from then. */
  private void drop(int player, int from) {
    if (goneFrom[player] != NEVER) {
      return;
    }
    goneFrom[player] = from;
    if (from < frame) {
      rollbackTo = Math.min(rollbackTo, from);
    }
    onPlayerLeft.dispatch(player);
  }

  private void onDisconnect(FlixelNetPeer peer) {
    if (!running) {
      return;
    }
    if (!session.isHost()) {
      running = false;
      onEnd.dispatch(null);
      return;
    }
    for (int p = 1; p < players; p++) {
      if (peers[p] == peer.getId() && goneFrom[p] == NEVER) {
        // Everyone must agree on the exact tick the player went idle, so the host decides it:
        // the tick after the last input it passed on.
        int from = received[p] + 1;
        FlixelRollbackPacket packet = session.obtain(FlixelRollbackPacket.class);
        packet.body.writeByte(DROP);
        packet.body.writeByte(p);
        packet.body.writeVarUInt(from);
        session.send(packet, FlixelNetChannel.RELIABLE);
        drop(p, from);
      }
    }
  }

  private static boolean same(FlixelNetBuffer a, FlixelNetBuffer b) {
    int size = a.getSize();
    if (size != b.getSize()) {
      return false;
    }
    byte[] left = a.getData();
    byte[] right = b.getData();
    for (int i = 0; i < size; i++) {
      if (left[i] != right[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the random numbers the simulation must use.
   *
   * @return The match's shared random number source.
   */
  @NotNull
  public FlixelRollbackRandom getRandom() {
    return random;
  }

  public boolean isRunning() {
    return running;
  }

  /**
   * Returns the number of the tick the game is about to simulate, counted from zero at the
   * start of the match.
   *
   * @return The current tick.
   */
  public int getFrame() {
    return frame;
  }

  /**
   * Returns which player this computer is.
   *
   * @return The local player's number, or -1 before the match starts.
   */
  public int getLocalPlayer() {
    return local;
  }

  public int getPlayerCount() {
    return players;
  }

  /**
   * Returns whether a player has left the match.
   *
   * @param player A player number.
   * @return {@code true} once that player is gone.
   */
  public boolean isGone(int player) {
    return player >= 0 && player < players && goneFrom[player] != NEVER;
  }

  /**
   * Returns how many times the game has been rewound and played again so far.
   *
   * @return The number of rollbacks.
   */
  public int getRollbacks() {
    return rollbacks;
  }

  /**
   * Returns how many ticks the most recent rollback played again.
   *
   * @return The depth of the last rollback, in ticks.
   */
  public int getRollbackDepth() {
    return rollbackDepth;
  }

  /**
   * Returns how many ticks this computer has skipped to let slower players catch up.
   *
   * @return The number of skipped ticks.
   */
  public int getStalls() {
    return stalls;
  }

  /**
   * Returns the newest fingerprint of the game state. Two players in step show the same number
   * for the same {@link #getChecksumFrame()}.
   *
   * @return The fingerprint.
   */
  public int getChecksum() {
    return checksum;
  }

  /**
   * Returns the tick {@link #getChecksum()} was taken at.
   *
   * @return A tick number, or -1 before the first fingerprint.
   */
  public int getChecksumFrame() {
    return checksumFrame;
  }

  /**
   * Returns whether the players' games have drifted apart.
   *
   * @return {@code true} once {@link #onDesync} has fired.
   */
  public boolean isDesynced() {
    return desynced;
  }

  public int getTickRate() {
    return tickRate;
  }

  /**
   * Sets how many ticks run per second. Set it on the host before {@link #start()}; clients
   * receive it.
   *
   * @param perSecond Ticks per second, from 1 to 240.
   */
  public void setTickRate(int perSecond) {
    requireStopped();
    if (perSecond < 1 || perSecond > 240) {
      throw new IllegalArgumentException("Tick rate must be from 1 to 240, got " + perSecond + ".");
    }
    tickRate = perSecond;
  }

  public int getInputDelay() {
    return inputDelay;
  }

  /**
   * Sets how many ticks the local controls are held back before they take effect. Set it on
   * the host before {@link #start()}; clients receive it.
   *
   * <p>Two (the default) is about 33 milliseconds at 60 ticks a second, which few players can
   * feel. Raise it for slow connections to trade a little response for fewer rewinds.
   *
   * @param ticks The delay in ticks, from 0 to 30.
   */
  public void setInputDelay(int ticks) {
    requireStopped();
    if (ticks < 0 || ticks > 30) {
      throw new IllegalArgumentException("Input delay must be from 0 to 30, got " + ticks + ".");
    }
    inputDelay = ticks;
  }

  public int getMaxPrediction() {
    return maxPrediction;
  }

  /**
   * Sets how many ticks the game may run on guesses before it waits for the real controls. Set
   * it on the host before {@link #start()}; clients receive it.
   *
   * <p>This is also the longest rewind that can ever happen, and the number of saved states
   * kept in memory. The default is 8.
   *
   * @param ticks The limit in ticks, from 1 to 60.
   */
  public void setMaxPrediction(int ticks) {
    requireStopped();
    if (ticks < 1 || ticks > 60) {
      throw new IllegalArgumentException("Prediction limit must be from 1 to 60, got " + ticks + ".");
    }
    maxPrediction = ticks;
  }

  private void requireStopped() {
    if (running) {
      throw new IllegalStateException("This can only be changed before the match starts.");
    }
  }
}
