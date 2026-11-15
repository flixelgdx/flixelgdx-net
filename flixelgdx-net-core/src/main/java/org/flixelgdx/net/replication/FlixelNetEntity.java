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
import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.message.FlixelNetCodec;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.jetbrains.annotations.NotNull;

/**
 * Something in the game that every player sees in the same place: a ball, a crate, an enemy.
 *
 * <p>The host owns the truth about an entity. A few times a second it takes a photo of the
 * entity (its <i>state</i>, a small {@link NetMessage} class) and sends it to everyone. Each
 * client shows the entity by sliding smoothly from one photo to the next.
 *
 * <p>An entity class only has to answer two questions:
 *
 * <ul>
 *   <li>{@link #capture}: "what does the photo look like right now?" Called on the host.</li>
 *   <li>{@link #apply}: "make the game look like this photo." Called on clients.</li>
 * </ul>
 *
 * <pre>{@code
 * @NetMessage
 * public class BallState {
 *   @NetRange(min = 0, max = 1280) public float x;
 *   @NetRange(min = 0, max = 720) public float y;
 * }
 *
 * public class Ball extends FlixelNetEntity<BallState> {
 *   public final FlixelSprite sprite = new FlixelSprite();
 *
 *   protected void capture(BallState out) {
 *     out.x = sprite.getX();
 *     out.y = sprite.getY();
 *   }
 *
 *   protected void apply(BallState state) {
 *     sprite.setPosition(state.x, state.y);
 *   }
 * }
 * }</pre>
 *
 * <p>The host moves the ball however it likes in its own code (or in {@link #step}); clients
 * never move it themselves. For something a player controls and that should respond instantly,
 * use {@link FlixelNetPawn}.
 *
 * @param <S> The state class, which must be part of the session's protocol.
 * @see FlixelNetWorld
 */
public abstract class FlixelNetEntity<S> {

  /** How many past states a client keeps to slide between. */
  static final int HISTORY = 8;

  /** The host time, in milliseconds, of the newest state that arrived. */
  double latest;

  private final double[] times = new double[HISTORY];

  /** The bytes most recently sent for this entity, so an unchanged entity is not sent again. */
  final FlixelNetBuffer sent = new FlixelNetBuffer(32);

  FlixelNetWorld world;
  FlixelNetWorld.Kind kind;
  FlixelNetCodec<S> codec;
  private FlixelArray<S> states;
  private S view;
  int id;
  int owner;

  /** How many snapshots in a row found this entity unchanged. */
  int quiet;
  private int head;
  private int count;
  boolean alive;

  /** Whether clients have been told this entity exists. */
  boolean announced;

  /**
   * Fills in the entity's current state so it can be sent to the clients.
   *
   * <p>Called on the host only. Set every field of {@code out}.
   *
   * @param out The state to fill.
   */
  protected abstract void capture(@NotNull S out);

  /**
   * Makes the entity match a state.
   *
   * <p>Called on clients every frame with a state that has already been smoothed between two
   * updates from the host, and once when the entity first appears. It is not called on the host.
   *
   * @param state The state to show. Do not keep it; it is reused.
   */
  protected abstract void apply(@NotNull S state);

  /**
   * Moves the entity forward by one tick of the simulation. Called on the host only.
   *
   * <p>Does nothing unless overridden. This is a good home for movement that no player controls,
   * such as a bouncing ball or a patrolling enemy.
   *
   * @param dt The length of one tick, in seconds.
   */
  protected void step(float dt) {}

  /** Called once when the entity becomes part of the world, on the host and on clients. */
  protected void onSpawn() {}

  /** Called once when the entity leaves the world, on the host and on clients. */
  protected void onDespawn() {}

  @SuppressWarnings("unchecked")
  void init(FlixelNetWorld world, FlixelNetWorld.Kind kind, int id, int owner) {
    this.world = world;
    this.kind = kind;
    this.id = id;
    this.owner = owner;
    FlixelNetProtocol.Entry<S> entry = (FlixelNetProtocol.Entry<S>) kind.state;
    codec = entry.getCodec();
    states = new FlixelArray<>(HISTORY);
    for (int i = 0; i < HISTORY; i++) {
      states.add(codec.create());
    }
    view = codec.create();
    head = HISTORY - 1;
    alive = true;
  }

  /** Writes what a snapshot holds for this entity: on the host, a fresh capture. */
  void encode(FlixelNetBuffer out) {
    capture(view);
    codec.write(view, out);
  }

  /** Reads one state from the host into the history. */
  void push(double time, FlixelNetBuffer in) {
    head = (head + 1) % HISTORY;
    if (count < HISTORY) {
      count++;
    }
    codec.read(states.get(head), in);
    times[head] = time;
    latest = time;
  }

  /** Returns the newest state that arrived from the host. */
  S newest() {
    return states.get(head);
  }

  /** Shows the entity as it was at the given host time, sliding between the states around it. */
  void present(double time, boolean smooth) {
    if (count == 0) {
      return;
    }
    if (!smooth || count == 1 || time >= times[head]) {
      apply(states.get(head));
      return;
    }
    int after = head;
    for (int i = 1; i < count; i++) {
      int before = (head - i + HISTORY) % HISTORY;
      if (times[before] <= time) {
        double span = times[after] - times[before];
        float t = span <= 0.0 ? 1f : (float) ((time - times[before]) / span);
        codec.lerp(states.get(before), states.get(after), t, view);
        apply(view);
        return;
      }
      after = before;
    }
    apply(states.get(after));
  }

  /**
   * Returns the number that names this entity on every computer in the session.
   *
   * @return The entity's id, unique within its world.
   */
  public int getId() {
    return id;
  }

  /**
   * Returns the kind this entity was registered and spawned as.
   *
   * @return The kind number given to {@link FlixelNetWorld#register}.
   */
  public int getKind() {
    return kind.id;
  }

  /**
   * Returns the id of the peer this entity belongs to.
   *
   * @return A peer id, or {@link FlixelNetSession#HOST_ID} for the host.
   */
  public int getOwner() {
    return owner;
  }

  /**
   * Returns whether this entity belongs to the player on this computer.
   *
   * @return {@code true} when {@link #getOwner()} is this session's own id.
   */
  public boolean isOwned() {
    return world != null && owner == world.getSession().getLocalId();
  }

  /**
   * Returns whether this computer decides what is true about the entity.
   *
   * @return {@code true} on the host.
   */
  public boolean isAuthority() {
    return world != null && world.getSession().isHost();
  }

  /**
   * Returns whether the entity is still part of its world.
   *
   * @return {@code false} once it has been despawned.
   */
  public boolean isAlive() {
    return alive;
  }

  /**
   * Returns the world this entity is in.
   *
   * @return The world, or {@code null} before the entity was spawned.
   */
  public FlixelNetWorld getWorld() {
    return world;
  }
}
