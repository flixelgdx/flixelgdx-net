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
package org.flixelgdx.net.transport;

/**
 * The delivery promise a packet is sent with.
 *
 * <p>Think of two ways to pass something to a friend across a busy room. You can walk it over
 * yourself, which always works but takes a moment and makes everything behind it wait
 * ({@link #RELIABLE}). Or you can toss it, which is instant but sometimes misses
 * ({@link #UNRELIABLE}). Pick per packet: toss things that will be out of date in a moment anyway,
 * and walk over the things that must not be lost.
 */
public enum FlixelNetChannel {

  /**
   * Every packet arrives, exactly once, in the order it was sent.
   *
   * <p>Use this for things that must not be lost: chat, a player joining, a door opening, a score.
   * A lost packet is sent again, and packets behind it wait for it, so one slow packet can delay
   * the rest.
   */
  RELIABLE,

  /**
   * A packet may be lost, and a packet that arrives late is thrown away, but nothing ever waits.
   *
   * <p>Use this for things that are replaced many times a second, such as positions. If one is
   * lost, the next one makes up for it. Packets on this channel must fit in
   * {@link FlixelNetTransport#getMaxPacketSize(FlixelNetChannel)} bytes.
   */
  UNRELIABLE
}
