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
import org.jetbrains.annotations.NotNull;

/**
 * Every player's input for one tick, handed to {@link FlixelRollbackGame#step}.
 *
 * @param <I> The input class.
 */
public final class FlixelRollbackInputs<I> {

  FlixelArray<FlixelArray<I>> rings;
  int frame;
  int ringSize;

  FlixelRollbackInputs() {}

  /**
   * Returns one player's input for this tick.
   *
   * @param player The player's number, from zero up to, but not including, {@link #getCount()}.
   * @return The input. Do not keep or change it; it is reused.
   */
  @NotNull
  public I get(int player) {
    return rings.get(player).get(frame % ringSize);
  }

  /**
   * Returns how many players are in the match.
   *
   * @return The number of players.
   */
  public int getCount() {
    return rings.getSize();
  }

  /**
   * Returns the number of the tick being simulated, counted from zero at the start of the match.
   *
   * @return The tick number.
   */
  public int getFrame() {
    return frame;
  }
}
