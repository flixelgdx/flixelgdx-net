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
import org.flixelgdx.net.FlixelNetException;
import org.flixelgdx.net.message.FlixelNetCodec;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.jetbrains.annotations.NotNull;

/**
 * An entity that one player controls, and that answers that player's controls instantly even
 * though the host has the final say.
 *
 * <h2>Why this exists</h2>
 *
 * <p>If a player's character only moved when the host said so, every key press would take a trip
 * to the host and back before anything happened on screen, and the game would feel like walking
 * through mud. A pawn fixes that the way a chess player thinks ahead: the player's computer makes
 * the move right away and remembers it, the host makes the same move for real, and when the
 * host's answer comes back the computer checks its guess. If the host disagrees (the player ran
 * into something only the host knew about), the pawn is put where the host says and the moves
 * the host has not seen yet are played again on top.
 *
 * <h2>What to write</h2>
 *
 * <p>Besides {@link #capture} and {@link #apply} from {@link FlixelNetEntity}, a pawn answers:
 *
 * <ul>
 *   <li>{@link #sample}: "what are the controls doing right now?" Called on the owner's
 *       computer, once per tick.</li>
 *   <li>{@link #simulate}: "move one tick using these controls." Called on the owner's computer
 *       and on the host, so it must do exactly the same thing on both.</li>
 * </ul>
 *
 * <pre>{@code
 * @NetMessage
 * public class PlayerInput {
 *   public boolean left, right, up, down;
 * }
 *
 * public class Player extends FlixelNetPawn<PlayerState, PlayerInput> {
 *   public float x, y;
 *
 *   protected void sample(PlayerInput out) {
 *     out.left = leftKeyIsDown();
 *     out.right = rightKeyIsDown();
 *   }
 *
 *   protected void simulate(PlayerInput input, float dt) {
 *     if (input.left) x -= 240 * dt;
 *     if (input.right) x += 240 * dt;
 *   }
 *
 *   protected void capture(PlayerState out) { out.x = x; out.y = y; }
 *   protected void apply(PlayerState state) { x = state.x; y = state.y; }
 * }
 * }</pre>
 *
 * <p>For the guess to match the host, {@link #simulate} must depend only on the input and on
 * things that are part of the state. Anything else it reads (a timer, a random number, another
 * entity) can differ between the two computers; the pawn is then corrected by the host, which
 * shows up as a small jump.
 *
 * <p>The host only accepts as many inputs per second as there are ticks (plus a little slack),
 * so a tampered client cannot move faster by sending more of them.
 *
 * @param <S> The state class.
 * @param <I> The input class, a {@link NetMessage} that must be part of the session's protocol.
 */
public abstract class FlixelNetPawn<S, I> extends FlixelNetEntity<S> {

  /** How many inputs the owner remembers while waiting for the host to confirm them. */
  static final int PENDING = 128;

  /** How many of the latest inputs go in each packet, so a lost packet costs nothing. */
  static final int REDUNDANCY = 3;

  private FlixelNetCodec<I> inputCodec;
  private FlixelArray<I> inputs;

  /** On the owner, the number of the input sampled last. */
  private int seq;

  /** On the host, the number of the owner's input handled last. */
  int processed;

  /** On the host, how many more inputs the owner may have handled right now. */
  float budget;

  /**
   * Reads the player's controls. Called once per tick on the computer of the player who owns
   * this pawn.
   *
   * @param out The input to fill. Every field starts at its default value.
   */
  protected abstract void sample(@NotNull I out);

  /**
   * Moves the pawn forward by one tick using one input.
   *
   * @param input The controls for this tick. Do not keep it; it is reused.
   * @param dt The length of one tick, in seconds. It is always the same number.
   */
  protected abstract void simulate(@NotNull I input, float dt);

  @Override
  @SuppressWarnings("unchecked")
  void init(FlixelNetWorld world, FlixelNetWorld.Kind kind, int id, int owner) {
    super.init(world, kind, id, owner);
    inputCodec = ((FlixelNetProtocol.Entry<I>) kind.input).getCodec();
    inputs = new FlixelArray<>(PENDING);
    for (int i = 0; i < PENDING; i++) {
      inputs.add(inputCodec.create());
    }
  }

  @Override
  void encode(FlixelNetBuffer out) {
    out.writeVarUInt(processed);
    super.encode(out);
  }

  /** One tick on the host for a pawn the host itself controls. */
  void tickHost(float dt) {
    I input = inputs.get(0);
    inputCodec.reset(input);
    sample(input);
    simulate(input, dt);
  }

  /** One tick on the owner's computer: read the controls, act on them, and tell the host. */
  void tickOwner(float dt, boolean predict, FlixelNetBuffer out) {
    seq++;
    I input = inputs.get(seq % PENDING);
    inputCodec.reset(input);
    sample(input);
    if (predict) {
      simulate(input, dt);
    }
    int first = Math.max(1, seq - REDUNDANCY + 1);
    out.writeVarUInt(id);
    out.writeVarUInt(first);
    out.writeVarUInt(seq - first + 1);
    for (int q = first; q <= seq; q++) {
      inputCodec.write(inputs.get(q % PENDING), out);
    }
  }

  /** Handles a packet of inputs from the owner, on the host. */
  void receive(FlixelNetBuffer in, float dt) {
    int first = in.readVarUInt();
    int count = in.readVarUInt();
    if (first < 1 || count < 1 || count > REDUNDANCY) {
      throw new FlixelNetException("An input packet holds " + count + " inputs starting at " + first + ".");
    }
    I input = inputs.get(0);
    for (int i = 0; i < count; i++) {
      inputCodec.read(input, in);
      int number = first + i;
      if (number <= processed) {
        continue;
      }
      processed = number;
      if (budget >= 1f) {
        budget -= 1f;
        simulate(input, dt);
      }
    }
  }

  /**
   * Puts the pawn where the host says it was after input number {@code ack}, then plays the
   * inputs the host has not seen yet on top.
   */
  void reconcile(int ack, float dt) {
    apply(newest());
    for (int q = Math.max(ack + 1, seq - PENDING + 1); q <= seq; q++) {
      simulate(inputs.get(q % PENDING), dt);
    }
  }
}
