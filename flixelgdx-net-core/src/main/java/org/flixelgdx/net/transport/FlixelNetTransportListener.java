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
 * Receives what happened on a {@link FlixelNetTransport} since the last time it was polled.
 *
 * <p>Every method here is called from inside {@link FlixelNetTransport#poll(FlixelNetTransportListener)},
 * on the thread that called it. A transport never calls a listener from any other thread, so the
 * code here can touch game objects freely.
 *
 * <p>Each connection is reported in a fixed order: {@link #onOpen(int)} once, then
 * {@link #onData(int, FlixelNetChannel, byte[], int, int)} any number of times, then
 * {@link #onClose(int, FlixelNetCloseReason)} once. A connection attempt that fails reports only
 * {@link #onClose(int, FlixelNetCloseReason)}.
 */
public interface FlixelNetTransportListener {

  /**
   * Called when a connection is ready to use.
   *
   * @param connection The id of the connection. On a host this identifies the client that joined.
   *     On a client it is always {@link FlixelNetTransport#HOST}.
   */
  void onOpen(int connection);

  /**
   * Called when a connection has ended, or when an attempt to connect has failed.
   *
   * @param connection The id of the connection that ended.
   * @param reason Why it ended.
   */
  void onClose(int connection, FlixelNetCloseReason reason);

  /**
   * Called when a packet has arrived.
   *
   * <p>The array is only borrowed. It belongs to the transport and is reused as soon as this
   * method returns, so read what is needed right away (for example with
   * {@code FlixelNetBuffer.wrap(...)}) and copy anything that must be kept.
   *
   * @param connection The id of the connection the packet came from.
   * @param channel The channel the packet was sent on.
   * @param data The array holding the packet.
   * @param offset The index of the first byte of the packet in {@code data}.
   * @param length How many bytes the packet has.
   */
  void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length);
}
