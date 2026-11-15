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

/**
 * A source of random numbers that gives every player the same numbers in the same order, and
 * that can be wound back together with the game.
 *
 * <p>An ordinary random number generator would hand different numbers to every computer, and the
 * players' games would drift apart. This one starts from a seed the host shares when the match
 * begins, and its position is saved and restored with the game state, so a tick that is played
 * again draws the same numbers as the first time.
 *
 * <p>Get it from {@link FlixelRollbackSession#getRandom()} and use it only from inside
 * {@link FlixelRollbackGame#step}.
 */
public final class FlixelRollbackRandom {

  private long state = 1L;

  FlixelRollbackRandom() {}

  /**
   * Returns the next random number, any {@code long} value.
   *
   * @return A random {@code long}.
   */
  public long nextLong() {
    // The SplitMix64 generator: fast, tiny, and exactly the same on every platform.
    state += 0x9E3779B97F4A7C15L;
    long z = state;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * Returns a random whole number from zero up to, but not including, a bound.
   *
   * @param bound One more than the largest number wanted. Must be greater than zero.
   * @return A random number in that range.
   */
  public int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("Bound must be greater than zero, got " + bound + ".");
    }
    return (int) ((nextLong() >>> 1) % bound);
  }

  /**
   * Returns a random number from 0 up to, but not including, 1.
   *
   * @return A random {@code float}.
   */
  public float nextFloat() {
    return (nextLong() >>> 40) * (1f / (1 << 24));
  }

  /**
   * Returns a random number in a range.
   *
   * @param min The smallest number wanted.
   * @param max The upper end of the range, which is never quite reached.
   * @return A random {@code float} from {@code min} up to {@code max}.
   */
  public float nextFloat(float min, float max) {
    return min + (max - min) * nextFloat();
  }

  /**
   * Returns {@code true} or {@code false} with equal chance.
   *
   * @return A random {@code boolean}.
   */
  public boolean nextBool() {
    return nextLong() < 0L;
  }

  long getState() {
    return state;
  }

  void setState(long state) {
    this.state = state;
  }
}
