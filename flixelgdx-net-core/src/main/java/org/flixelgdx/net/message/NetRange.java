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
package org.flixelgdx.net.message;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tells the network code which values a number field of a {@link NetMessage} can hold, so it can
 * be sent in fewer bits.
 *
 * <p>Without this, a {@code float} always takes four bytes because it could be anything. Most
 * game values are not anything: a position is somewhere on the level, a health value is from 0 to
 * 100, a stick direction is from -1 to 1. Saying so lets the value fit in far less space.
 *
 * <pre>{@code
 * @NetMessage
 * public class PlayerState {
 *   @NetRange(min = 0, max = 1280) // A float in 16 bits, accurate to about 1/50 of a pixel.
 *   public float x;
 *   @NetRange(min = 0, max = 100) // An int in 7 bits instead of up to 5 bytes.
 *   public int health;
 * }
 * }</pre>
 *
 * <p>On a {@code float} field, the range is cut into equal steps and the nearest step is sent;
 * {@link #bits()} decides how many steps there are. On a {@code byte}, {@code short}, {@code char}
 * or {@code int} field, the value is sent exactly, using just enough bits for the range, and
 * {@link #bits()} is ignored.
 *
 * <p>A value outside the range is moved to the nearest end of it before it is sent, so pick a
 * range the value can never leave.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface NetRange {

  /**
   * The smallest value the field can hold.
   *
   * @return The lower end of the range.
   */
  double min();

  /**
   * The largest value the field can hold. Must be greater than {@link #min()}.
   *
   * @return The upper end of the range.
   */
  double max();

  /**
   * For a {@code float} field, how many bits to use, from 1 to 30. More bits means a more exact
   * value.
   *
   * @return The number of bits, 16 unless set.
   */
  int bits() default 16;
}
