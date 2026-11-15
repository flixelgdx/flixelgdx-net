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
package org.flixelgdx.net;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when network data cannot be read or written safely.
 *
 * <p>The most common cause is a packet that is shorter than the code reading it expects, which
 * happens when a packet is corrupted, comes from a different version of the game, or was crafted
 * by someone trying to break the game. Code that reads packets from the network should catch this
 * exception and drop the packet (or the connection) instead of letting it end the game.
 */
public class FlixelNetException extends RuntimeException {

  /**
   * Creates an exception with a message describing what went wrong.
   *
   * @param message A description of the problem.
   */
  public FlixelNetException(@NotNull String message) {
    super(message);
  }
}
