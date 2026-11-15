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
package org.flixelgdx.net.replication;

import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.collections.FlixelIntMap;
import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetException;
import org.flixelgdx.net.FlixelNetHandler;
import org.flixelgdx.net.FlixelNetPeer;
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.FlixelNetSessionState;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.signal.FlixelSignal;
import org.flixelgdx.signal.FlixelSignal.SignalHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps a set of entities the same on every computer in a session.
 *
 * <p>Think of the host as a stage and the clients as people watching a live broadcast of it. The
 * host puts actors on the stage ({@link #spawn}) and takes them off ({@link #despawn}); the world
 * films the stage a few times a second and sends the pictures out; and on each client the world
 * builds the same actors and moves them to match the pictures, smoothly.
 *
 * <h2>Setting up</h2>
 *
 * <pre>{@code
 * // The same on the host and on every client:
 * FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1)
 *     .add(BallStateNetCodec.INSTANCE)
 *     .add(PlayerStateNetCodec.INSTANCE)
 *     .add(PlayerInputNetCodec.INSTANCE);
 * FlixelNetWorld.addMessages(protocol);
 * FlixelNetSession session = new FlixelNetSession(protocol);
 *
 * FlixelNetWorld world = new FlixelNetWorld(session);
 * world.register(BALL, BallState.class, Ball::new);
 * world.register(PLAYER, PlayerState.class, PlayerInput.class, Player::new);
 * world.onSpawn.add(entity -> add(spriteOf(entity)));
 * world.onDespawn.add(entity -> remove(spriteOf(entity)));
 *
 * // Every frame, right after the session:
 * session.update(elapsed);
 * world.update(elapsed);
 *
 * // On the host only:
 * session.onConnect.add(peer -> world.spawn(PLAYER, peer.getId()));
 * session.onDisconnect.add(peer -> world.despawnOwnedBy(peer.getId()));
 * }</pre>
 *
 * <h2>Two clocks</h2>
 *
 * <ul>
 *   <li>The <b>tick rate</b> ({@link #setTickRate}) is how often the simulation moves:
 *       {@link FlixelNetEntity#step} and {@link FlixelNetPawn#simulate} run this many times a
 *       second, always with the same step length, on the host and on clients alike.</li>
 *   <li>The <b>snapshot rate</b> ({@link #setSnapshotRate}) is how often the host sends pictures.
 *       It is lower, because every picture costs bandwidth and clients fill the gaps by
 *       smoothing.</li>
 * </ul>
 *
 * <h2>What clients see</h2>
 *
 * <p>Clients show other entities a moment in the past ({@link #setInterpolationDelay}), so there
 * are always two pictures to slide between even when one is late or lost. A pawn the local
 * player owns is the exception: it is shown in the present, see {@link FlixelNetPawn}.
 *
 * <p>An entity that does not change is not sent again, apart from an occasional reminder, so a
 * level full of resting crates costs almost nothing. Keep each state small: one state has to fit
 * in a single packet of about a thousand bytes.
 */
public final class FlixelNetWorld {

  /** How many simulation ticks run per second unless {@link #setTickRate} says otherwise. */
  public static final int DEFAULT_TICK_RATE = 60;

  /** How many snapshots the host sends per second unless {@link #setSnapshotRate} says otherwise. */
  public static final int DEFAULT_SNAPSHOT_RATE = 20;

  private static final int SPAWN = 1;
  private static final int DESPAWN = 2;
  private static final int SNAPSHOT = 3;
  private static final int INPUT = 4;

  /** The most bytes of entity states put in one snapshot packet, safely under a transport's limit. */
  private static final int MAX_BODY = 900;

  /** How many times an entity that stopped changing is still sent, in case a packet was lost. */
  private static final int QUIET_REPEATS = 3;

  /** The most ticks run in one frame, so a long freeze is not followed by a longer one. */
  private static final int MAX_STEPS = 8;

  /** Dispatched when an entity becomes part of the world, on the host and on clients. */
  public final FlixelSignal<FlixelNetEntity<?>> onSpawn = new FlixelSignal<>();

  /** Dispatched when an entity leaves the world, on the host and on clients. */
  public final FlixelSignal<FlixelNetEntity<?>> onDespawn = new FlixelSignal<>();

  /** Seconds this world has been updated for. */
  private double clock;

  /** On a client, how far the host's clock is ahead of this one, in milliseconds. */
  private double offset;

  private final FlixelNetSession session;
  private final FlixelIntMap<Kind> kinds = new FlixelIntMap<>();
  private final FlixelArray<FlixelNetEntity<?>> entities = new FlixelArray<>();
  private final FlixelIntMap<FlixelNetEntity<?>> byId = new FlixelIntMap<>();
  private final FlixelNetBuffer scratch = new FlixelNetBuffer();
  private final FlixelNetHandler<FlixelNetWorldPacket> packetHandler = this::onPacket;
  private final SignalHandler<FlixelNetPeer> connectHandler = this::onConnect;
  private final SignalHandler<FlixelNetPeer> disconnectHandler = this::onDisconnect;
  private int nextId = 1;
  private int tickRate = DEFAULT_TICK_RATE;
  private int snapshotRate = DEFAULT_SNAPSHOT_RATE;
  private int snapshots;
  private float tickLength = 1f / DEFAULT_TICK_RATE;
  private float tickTimer;
  private float snapshotTimer;
  private float delay = 0.1f;
  private boolean interpolation = true;
  private boolean prediction = true;
  private boolean synced;

  /**
   * Creates a world that travels over a session.
   *
   * @param session The session to use. Its protocol must contain the world's own messages, see
   *     {@link #addMessages}.
   * @throws IllegalArgumentException If {@link #addMessages} was not called on the protocol.
   */
  public FlixelNetWorld(@NotNull FlixelNetSession session) {
    this.session = session;
    session.getProtocol().entry(FlixelNetWorldPacket.class);
    session.on(FlixelNetWorldPacket.class, packetHandler);
    session.onConnect.add(connectHandler);
    session.onDisconnect.add(disconnectHandler);
  }

  /**
   * Adds the messages a world needs to a protocol. Call this once, before the protocol is given
   * to a session.
   *
   * @param protocol The protocol the session will use.
   */
  public static void addMessages(@NotNull FlixelNetProtocol protocol) {
    protocol.add(FlixelNetWorldPacket.CODEC);
  }

  /**
   * Teaches the world one kind of entity.
   *
   * <p>Every computer in the session must register the same kinds with the same numbers.
   *
   * @param kind A number of your choosing that names this kind, zero or greater.
   * @param state The entity's state class. It must be part of the protocol.
   * @param factory Makes a new entity of this kind, usually a constructor such as
   *     {@code Ball::new}.
   * @throws IllegalArgumentException If the kind is taken or the state is not in the protocol.
   */
  public void register(int kind, @NotNull Class<?> state, @NotNull FlixelNetEntityFactory factory) {
    register(kind, state, null, factory);
  }

  /**
   * Teaches the world one kind of player-controlled entity.
   *
   * @param kind A number of your choosing that names this kind, zero or greater.
   * @param state The pawn's state class. It must be part of the protocol.
   * @param input The pawn's input class, or {@code null} for an entity nobody controls. It must
   *     be part of the protocol too.
   * @param factory Makes a new entity of this kind. With an input class, it must make a
   *     {@link FlixelNetPawn}.
   * @throws IllegalArgumentException If the kind is taken or a class is not in the protocol.
   */
  public void register(int kind, @NotNull Class<?> state, @Nullable Class<?> input,
      @NotNull FlixelNetEntityFactory factory) {
    if (kind < 0) {
      throw new IllegalArgumentException("An entity kind must be zero or greater, got " + kind + ".");
    }
    if (kinds.containsKey(kind)) {
      throw new IllegalArgumentException("Entity kind " + kind + " is already registered.");
    }
    FlixelNetProtocol protocol = session.getProtocol();
    kinds.put(kind, new Kind(kind, protocol.entry(state), input == null ? null : protocol.entry(input), factory));
  }

  /**
   * Puts a new entity into the world. Only the host can do this.
   *
   * <p>Clients are told about the entity on the next {@link #update}, so there is time to place
   * it first:
   *
   * <pre>{@code
   * Ball ball = world.spawn(BALL, FlixelNetSession.HOST_ID);
   * ball.sprite.setPosition(640, 360);
   * }</pre>
   *
   * @param kind The kind of entity, as registered.
   * @param owner The id of the peer the entity belongs to, or {@link FlixelNetSession#HOST_ID}.
   *     For a {@link FlixelNetPawn} this is the player who controls it.
   * @param <E> The entity class the kind's factory makes.
   * @return The new entity.
   * @throws IllegalStateException If this computer is not the host.
   * @throws IllegalArgumentException If the kind was never registered.
   */
  @NotNull
  @SuppressWarnings("unchecked")
  public <E extends FlixelNetEntity<?>> E spawn(int kind, int owner) {
    if (!session.isHost()) {
      throw new IllegalStateException("Only the host can spawn entities.");
    }
    FlixelNetEntity<?> entity = create(kind, nextId++, owner);
    entity.onSpawn();
    onSpawn.dispatch(entity);
    return (E) entity;
  }

  /**
   * Takes an entity out of the world, here and on every client. Only the host can do this.
   *
   * @param entity The entity to remove. Removing one that is already gone does nothing.
   * @throws IllegalStateException If this computer is not the host.
   */
  public void despawn(@NotNull FlixelNetEntity<?> entity) {
    if (!session.isHost()) {
      throw new IllegalStateException("Only the host can despawn entities.");
    }
    if (!entity.alive || entity.world != this) {
      return;
    }
    if (entity.announced) {
      FlixelNetWorldPacket packet = session.obtain(FlixelNetWorldPacket.class);
      packet.body.writeByte(DESPAWN);
      packet.body.writeVarUInt(entity.id);
      session.send(packet, FlixelNetChannel.RELIABLE);
    }
    remove(entity);
  }

  /**
   * Takes every entity that belongs to one peer out of the world. Only the host can do this.
   *
   * <p>Handy when a player leaves:
   * {@code session.onDisconnect.add(peer -> world.despawnOwnedBy(peer.getId()));}
   *
   * @param owner The peer id whose entities should go.
   */
  public void despawnOwnedBy(int owner) {
    for (int i = entities.getSize() - 1; i >= 0; i--) {
      FlixelNetEntity<?> entity = entities.get(i);
      if (entity.owner == owner) {
        despawn(entity);
      }
    }
  }

  /**
   * Runs the world for one frame. Call it every frame, right after the session's own update.
   *
   * @param elapsed Seconds since the last frame.
   */
  public void update(float elapsed) {
    boolean host = session.isHost();
    if (!host && session.getState() != FlixelNetSessionState.JOINED) {
      return;
    }
    clock += elapsed;

    if (host) {
      float refill = elapsed * tickRate * 1.25f;
      for (int i = 0, n = entities.getSize(); i < n; i++) {
        if (entities.get(i) instanceof FlixelNetPawn<?, ?> pawn) {
          pawn.budget = Math.min(tickRate * 0.5f, pawn.budget + refill);
        }
      }
    }

    tickTimer += elapsed;
    int steps = 0;
    while (tickTimer >= tickLength && steps < MAX_STEPS) {
      tickTimer -= tickLength;
      steps++;
      tick(host);
    }
    if (tickTimer >= tickLength) {
      tickTimer = 0f;
    }

    if (host) {
      announce();
      snapshotTimer += elapsed;
      float interval = 1f / snapshotRate;
      if (snapshotTimer >= interval) {
        snapshotTimer = Math.min(snapshotTimer - interval, interval);
        sendSnapshot();
      }
      return;
    }

    double shown = clock * 1000.0 + offset - delay * 1000.0;
    int local = session.getLocalId();
    for (int i = 0; i < entities.getSize(); i++) {
      FlixelNetEntity<?> entity = entities.get(i);
      if (prediction && entity.owner == local && entity instanceof FlixelNetPawn) {
        continue;
      }
      entity.present(shown, interpolation);
    }
  }

  /**
   * Forgets every entity without telling anyone, calling {@link FlixelNetEntity#onDespawn} on
   * each. Use it when leaving the game screen.
   */
  public void clear() {
    while (entities.getSize() > 0) {
      remove(entities.get(entities.getSize() - 1));
    }
    synced = false;
  }

  /** Clears the world and disconnects it from its session. The world cannot be used afterwards. */
  public void destroy() {
    clear();
    session.off(FlixelNetWorldPacket.class, packetHandler);
    session.onConnect.remove(connectHandler);
    session.onDisconnect.remove(disconnectHandler);
  }

  private void tick(boolean host) {
    int local = session.getLocalId();
    for (int i = 0; i < entities.getSize(); i++) {
      FlixelNetEntity<?> entity = entities.get(i);
      if (host) {
        if (entity.owner == local && entity instanceof FlixelNetPawn<?, ?> pawn) {
          pawn.tickHost(tickLength);
        }
        entity.step(tickLength);
      } else if (entity.owner == local && entity instanceof FlixelNetPawn<?, ?> pawn) {
        FlixelNetWorldPacket packet = session.obtain(FlixelNetWorldPacket.class);
        packet.body.writeByte(INPUT);
        pawn.tickOwner(tickLength, prediction, packet.body);
        session.send(packet, FlixelNetChannel.UNRELIABLE);
      }
    }
  }

  /** Tells every client about the entities spawned since the last update. */
  private void announce() {
    for (int i = 0, n = entities.getSize(); i < n; i++) {
      FlixelNetEntity<?> entity = entities.get(i);
      if (!entity.announced) {
        entity.announced = true;
        sendSpawn(entity, null);
      }
    }
  }

  private void sendSpawn(FlixelNetEntity<?> entity, @Nullable FlixelNetPeer only) {
    scratch.clear();
    entity.encode(scratch);
    FlixelNetWorldPacket packet = session.obtain(FlixelNetWorldPacket.class);
    FlixelNetBuffer body = packet.body;
    body.writeByte(SPAWN);
    body.writeVarUInt(entity.id);
    body.writeVarUInt(entity.kind.id);
    body.writeVarInt(entity.owner);
    body.writeVarUInt(scratch.getSize());
    body.writeBuffer(scratch);
    if (only == null) {
      session.send(packet, FlixelNetChannel.RELIABLE);
    } else {
      session.sendTo(only, packet, FlixelNetChannel.RELIABLE);
    }
  }

  private void sendSnapshot() {
    snapshots++;
    if (session.getPeerCount() == 0) {
      return;
    }
    FlixelNetWorldPacket packet = null;
    for (int i = 0, n = entities.getSize(); i < n; i++) {
      FlixelNetEntity<?> entity = entities.get(i);
      scratch.clear();
      entity.encode(scratch);
      int size = scratch.getSize();
      if (same(scratch, entity.sent)) {
        entity.quiet++;
        // An entity at rest is still sent a few more times and then once a second, so a client
        // that lost its last update catches up without the host spending much on it.
        if (entity.quiet > QUIET_REPEATS && (snapshots + entity.id) % snapshotRate != 0) {
          continue;
        }
      } else {
        entity.quiet = 0;
        entity.sent.set(scratch.getData(), scratch.getOffset(), size);
      }
      if (packet != null && packet.body.getSize() + size + 10 > MAX_BODY) {
        session.send(packet, FlixelNetChannel.UNRELIABLE);
        packet = null;
      }
      if (packet == null) {
        packet = session.obtain(FlixelNetWorldPacket.class);
        packet.body.writeByte(SNAPSHOT);
        packet.body.writeVarULong((long) (clock * 1000.0));
      }
      packet.body.writeVarUInt(entity.id);
      packet.body.writeVarUInt(size);
      packet.body.writeBuffer(scratch);
    }
    if (packet != null) {
      session.send(packet, FlixelNetChannel.UNRELIABLE);
    }
  }

  private static boolean same(FlixelNetBuffer a, FlixelNetBuffer b) {
    int size = a.getSize();
    if (size != b.getSize()) {
      return false;
    }
    byte[] left = a.getData();
    byte[] right = b.getData();
    int leftStart = a.getOffset();
    int rightStart = b.getOffset();
    for (int i = 0; i < size; i++) {
      if (left[leftStart + i] != right[rightStart + i]) {
        return false;
      }
    }
    return true;
  }

  private void onConnect(FlixelNetPeer peer) {
    if (!session.isHost()) {
      return;
    }
    for (int i = 0, n = entities.getSize(); i < n; i++) {
      FlixelNetEntity<?> entity = entities.get(i);
      // Entities that were not announced yet reach this peer with everyone else's announcement.
      if (entity.announced) {
        sendSpawn(entity, peer);
      }
    }
  }

  private void onDisconnect(FlixelNetPeer peer) {
    // On a client the only peer is the host, so losing it means the broadcast is over.
    if (!session.isHost()) {
      clear();
    }
  }

  private void onPacket(FlixelNetPeer from, FlixelNetWorldPacket packet) {
    FlixelNetBuffer in = packet.body;
    int op = in.readUByte();
    if (session.isHost()) {
      if (op == INPUT) {
        readInput(from, in);
      }
      return;
    }
    switch (op) {
      case SPAWN -> readSpawn(in);
      case DESPAWN -> {
        FlixelNetEntity<?> entity = byId.get(in.readVarUInt());
        if (entity != null) {
          remove(entity);
        }
      }
      case SNAPSHOT -> readSnapshot(in);
      default -> {
        // Only the host handles inputs, and anything else is from a newer build.
      }
    }
  }

  private void readSpawn(FlixelNetBuffer in) {
    int id = in.readVarUInt();
    int kind = in.readVarUInt();
    int owner = in.readVarInt();
    in.readVarUInt();
    if (byId.containsKey(id)) {
      return;
    }
    if (!kinds.containsKey(kind)) {
      throw new FlixelNetException("The host spawned an entity of kind " + kind + ", which is not registered here.");
    }
    FlixelNetEntity<?> entity = create(kind, id, owner);
    entity.announced = true;
    if (entity instanceof FlixelNetPawn) {
      in.readVarUInt();
    }
    entity.push(0.0, in);
    entity.present(0.0, false);
    entity.onSpawn();
    onSpawn.dispatch(entity);
  }

  private void readSnapshot(FlixelNetBuffer in) {
    double time = in.readVarULong();
    // The host's clock is guessed from the snapshots that got here fastest; slower ones only
    // nudge the guess, so one late packet does not make everything stutter.
    double sample = time - clock * 1000.0;
    if (!synced || sample > offset) {
      offset = sample;
      synced = true;
    } else {
      offset += (sample - offset) * 0.05;
    }
    int local = session.getLocalId();
    while (in.remaining() > 0) {
      int id = in.readVarUInt();
      int size = in.readVarUInt();
      int next = in.getPosition() + size;
      if (size < 0 || next > in.getSize()) {
        throw new FlixelNetException("A snapshot entry is longer than the snapshot it came in.");
      }
      FlixelNetEntity<?> entity = byId.get(id);
      if (entity != null && time > entity.latest) {
        if (entity instanceof FlixelNetPawn<?, ?> pawn) {
          int ack = in.readVarUInt();
          pawn.push(time, in);
          if (prediction && pawn.owner == local) {
            pawn.reconcile(ack, tickLength);
          }
        } else {
          entity.push(time, in);
        }
      }
      in.setPosition(next);
    }
  }

  private void readInput(FlixelNetPeer from, FlixelNetBuffer in) {
    FlixelNetEntity<?> entity = byId.get(in.readVarUInt());
    // Inputs for a pawn that is gone, or that belongs to someone else, are quietly dropped.
    if (entity instanceof FlixelNetPawn<?, ?> pawn && pawn.owner == from.getId()) {
      pawn.receive(in, tickLength);
    }
  }

  private FlixelNetEntity<?> create(int kindId, int id, int owner) {
    Kind kind = kinds.get(kindId);
    if (kind == null) {
      throw new IllegalArgumentException("Entity kind " + kindId + " is not registered.");
    }
    FlixelNetEntity<?> entity = kind.factory.create();
    if (kind.input != null && !(entity instanceof FlixelNetPawn)) {
      throw new IllegalStateException(
          "Entity kind " + kindId + " was registered with an input class, so its factory must make a FlixelNetPawn.");
    }
    if (kind.input == null && entity instanceof FlixelNetPawn) {
      throw new IllegalStateException(
          "Entity kind " + kindId + " makes a FlixelNetPawn, so it must be registered with an input class.");
    }
    entity.init(this, kind, id, owner);
    entities.add(entity);
    byId.put(id, entity);
    return entity;
  }

  private void remove(FlixelNetEntity<?> entity) {
    entity.alive = false;
    entities.removeValue(entity, true);
    byId.remove(entity.id);
    entity.onDespawn();
    onDespawn.dispatch(entity);
  }

  /**
   * Returns the entity with the given id.
   *
   * @param id An entity id.
   * @return The entity, or {@code null} if there is none with that id.
   */
  @Nullable
  public FlixelNetEntity<?> getEntity(int id) {
    return byId.get(id);
  }

  /**
   * Returns one of the world's entities by position, for looping over all of them.
   *
   * @param index A number from zero up to, but not including, {@link #getEntityCount()}.
   * @return The entity at that position.
   */
  @NotNull
  public FlixelNetEntity<?> getEntityAt(int index) {
    return entities.get(index);
  }

  public int getEntityCount() {
    return entities.getSize();
  }

  public FlixelNetSession getSession() {
    return session;
  }

  public int getTickRate() {
    return tickRate;
  }

  /**
   * Sets how many times a second the simulation moves. Every computer in the session must use
   * the same number.
   *
   * @param perSecond Ticks per second, from 1 to 240.
   */
  public void setTickRate(int perSecond) {
    if (perSecond < 1 || perSecond > 240) {
      throw new IllegalArgumentException("Tick rate must be from 1 to 240, got " + perSecond + ".");
    }
    tickRate = perSecond;
    tickLength = 1f / perSecond;
  }

  public int getSnapshotRate() {
    return snapshotRate;
  }

  /**
   * Sets how many times a second the host sends the state of the world. Higher is smoother and
   * costs more bandwidth. Only matters on the host.
   *
   * @param perSecond Snapshots per second, from 1 to 120.
   */
  public void setSnapshotRate(int perSecond) {
    if (perSecond < 1 || perSecond > 120) {
      throw new IllegalArgumentException("Snapshot rate must be from 1 to 120, got " + perSecond + ".");
    }
    snapshotRate = perSecond;
  }

  public float getInterpolationDelay() {
    return delay;
  }

  /**
   * Sets how far in the past clients show entities they do not control, in seconds.
   *
   * <p>A good value is two snapshot intervals (0.1 at the default 20 snapshots a second), which
   * hides one lost snapshot. Lower feels more current and stutters more on a bad connection.
   *
   * @param seconds The delay, zero or greater.
   */
  public void setInterpolationDelay(float seconds) {
    if (!(seconds >= 0f)) {
      throw new IllegalArgumentException("Interpolation delay must be zero or greater, got " + seconds + ".");
    }
    delay = seconds;
  }

  public boolean isInterpolation() {
    return interpolation;
  }

  /**
   * Turns smoothing between snapshots on or off. Off, entities jump from one snapshot to the
   * next, which is mostly useful for seeing what smoothing does.
   *
   * @param interpolation Whether clients smooth entities.
   */
  public void setInterpolation(boolean interpolation) {
    this.interpolation = interpolation;
  }

  public boolean isPrediction() {
    return prediction;
  }

  /**
   * Turns instant response for the local player's pawns on or off. Off, a pawn only moves once
   * the host has answered, like any other entity.
   *
   * @param prediction Whether owned pawns act on input right away.
   */
  public void setPrediction(boolean prediction) {
    this.prediction = prediction;
  }

  /** One registered kind of entity. */
  static final class Kind {
    final int id;
    final FlixelNetProtocol.Entry<?> state;
    final FlixelNetProtocol.Entry<?> input;
    final FlixelNetEntityFactory factory;

    Kind(int id, FlixelNetProtocol.Entry<?> state, FlixelNetProtocol.Entry<?> input, FlixelNetEntityFactory factory) {
      this.id = id;
      this.state = state;
      this.input = input;
      this.factory = factory;
    }
  }
}
