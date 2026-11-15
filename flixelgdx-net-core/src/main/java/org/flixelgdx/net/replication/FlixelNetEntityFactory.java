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

import org.jetbrains.annotations.NotNull;

/**
 * Makes a new, empty entity of one kind.
 *
 * <p>A {@link FlixelNetWorld} calls this on the host when the game spawns an entity, and on every
 * client when the host says a new entity exists. Usually it is just a constructor:
 *
 * <pre>{@code
 * world.register(BALL, BallState.class, Ball::new);
 * }</pre>
 */
@FunctionalInterface
public interface FlixelNetEntityFactory {

  /**
   * Creates one entity.
   *
   * @return A new entity that is not part of any world yet.
   */
  @NotNull
  FlixelNetEntity<?> create();
}
