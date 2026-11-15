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

import org.jetbrains.annotations.NotNull;

/**
 * The lowest layer of networking: it moves packets of bytes between a host and its clients.
 *
 * <p>Think of a transport as a delivery truck. It does not know or care what is in the boxes, it
 * only gets them from one place to another. Different trucks suit different roads: one transport
 * talks to browsers, another is fastest between desktop players, and a loopback transport
 * delivers within a single program for testing. Because they all look the same from the outside,
 * the rest of the networking code works with any of them.
 *
 * <p>Most games never use a transport directly. It is here for the layers built on top of it and
 * for anyone who wants full control over the bytes.
 *
 * <h2>Host or client</h2>
 *
 * <p>A transport plays one role at a time. Calling {@link #listen(int)} makes it a host that many
 * clients can join. Calling {@link #connect(String)} makes it a client of one host. Each
 * connection has a number: a host gets a new one for every client that joins, and a client always
 * uses {@link #HOST} for its one connection.
 *
 * <h2>Polling</h2>
 *
 * <p>Nothing is ever reported on its own. The game asks for news by calling
 * {@link #poll(FlixelNetTransportListener)} once per frame, and everything that happened since the
 * last call is reported right then, on the calling thread:
 *
 * <pre>{@code
 * transport.listen(7777);
 *
 * // Once per frame:
 * transport.poll(listener);
 *
 * // Whenever there is something to say:
 * transport.send(connection, FlixelNetChannel.RELIABLE, buffer.getData(), 0, buffer.getSize());
 * }</pre>
 */
public interface FlixelNetTransport {

  /** The connection id a client uses for its host. */
  int HOST = 0;

  /**
   * Starts acting as a host and accepting clients.
   *
   * @param port The port to accept clients on. Transports that have no ports ignore this.
   * @throws IllegalStateException If the transport is not {@link FlixelNetTransportState#CLOSED}.
   */
  void listen(int port);

  /**
   * Starts connecting to a host as a client.
   *
   * <p>This returns right away. The result arrives later through
   * {@link #poll(FlixelNetTransportListener)}: either
   * {@link FlixelNetTransportListener#onOpen(int)} or
   * {@link FlixelNetTransportListener#onClose(int, FlixelNetCloseReason)}.
   *
   * @param address Where the host is. The format depends on the transport, for example
   *     {@code "192.168.1.20:7777"} or a web address.
   * @throws IllegalStateException If the transport is not {@link FlixelNetTransportState#CLOSED}.
   */
  void connect(@NotNull String address);

  /**
   * Reports everything that happened since the last call: connections that opened, packets that
   * arrived, and connections that closed.
   *
   * <p>Call this once per frame from the game thread.
   *
   * @param listener What to report to.
   */
  void poll(@NotNull FlixelNetTransportListener listener);

  /**
   * Sends a packet over a connection.
   *
   * <p>The bytes are copied before this returns, so the array can be reused right away.
   *
   * @param connection The id of the connection to send on.
   * @param channel How carefully the packet should be delivered.
   * @param data The array holding the packet.
   * @param offset The index of the first byte of the packet in {@code data}.
   * @param length How many bytes the packet has.
   * @return {@code true} if the packet was accepted for sending, {@code false} if the connection
   *     is not open. A packet that was accepted on {@link FlixelNetChannel#UNRELIABLE} can still
   *     be lost on the way.
   * @throws IllegalArgumentException If the packet is larger than
   *     {@link #getMaxPacketSize(FlixelNetChannel)} allows for the channel.
   */
  boolean send(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset, int length);

  /**
   * Ends one connection.
   *
   * <p>Both sides are told through {@link FlixelNetTransportListener#onClose(int, FlixelNetCloseReason)}.
   * Asking to end a connection that is not open does nothing.
   *
   * @param connection The id of the connection to end.
   */
  void disconnect(int connection);

  /**
   * Ends every connection and stops listening or connecting.
   *
   * <p>The transport goes back to {@link FlixelNetTransportState#CLOSED} right away and can be
   * used again. Anything that had not been reported yet is thrown away, so the listener hears
   * nothing more about the connections that were open. The other side of each connection is still
   * told that it closed.
   */
  void close();

  /**
   * Returns the largest packet, in bytes, that can be sent on a channel.
   *
   * @param channel The channel to ask about.
   * @return The largest number of bytes one packet on that channel may have.
   */
  int getMaxPacketSize(@NotNull FlixelNetChannel channel);

  /**
   * Returns what the transport is doing right now.
   *
   * @return The current state.
   */
  @NotNull
  FlixelNetTransportState getState();
}
