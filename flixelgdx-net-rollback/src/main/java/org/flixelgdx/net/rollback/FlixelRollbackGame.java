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

import org.flixelgdx.net.FlixelNetBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * The four things a game must be able to do so a {@link FlixelRollbackSession} can keep it in
 * step on every player's computer.
 *
 * <p>The part of the game that implements this is the <i>simulation</i>: the positions, health,
 * timers and everything else that decides what happens. Drawing, sound and menus are not part of
 * it; they only look at the simulation after each update.
 *
 * <p>The golden rule is that {@link #step} must be <b>deterministic</b>: given the same starting
 * state and the same inputs it must produce exactly the same result on every computer, every
 * time. That means:
 *
 * <ul>
 *   <li>No clocks and no frame time. Every step is one tick of the same fixed length.</li>
 *   <li>Random numbers only from {@link FlixelRollbackSession#getRandom()}.</li>
 *   <li>No sounds, particles or other one-off effects from inside {@link #step}, because a step
 *       can be played again. Decide them from the state after the update instead.</li>
 *   <li>Everything {@link #step} changes must be written by {@link #save} and restored by
 *       {@link #load}.</li>
 * </ul>
 *
 * @param <I> The input class: what one player's controls are doing during one tick.
 */
public interface FlixelRollbackGame<I> {

  /**
   * Reads the local player's controls for one tick.
   *
   * @param out The input to fill. Every field starts at its default value.
   */
  void sample(@NotNull I out);

  /**
   * Moves the simulation forward by exactly one tick.
   *
   * @param inputs What every player's controls are doing this tick. Some may be guesses that
   *     are corrected later; the game never has to care.
   */
  void step(@NotNull FlixelRollbackInputs<I> inputs);

  /**
   * Writes the whole simulation into a buffer, like a save file.
   *
   * @param out The buffer to write to.
   */
  void save(@NotNull FlixelNetBuffer out);

  /**
   * Restores the whole simulation from a buffer that {@link #save} wrote.
   *
   * @param in The buffer to read from, in the same order it was written.
   */
  void load(@NotNull FlixelNetBuffer in);
}
