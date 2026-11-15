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
 * The smallest possible view of a network socket: it can throw a small packet toward an address
 * and catch packets thrown at it, with no promise that any of them arrive.
 *
 * <p>This is what {@link FlixelNetDatagramTransport} is built on. The transport adds everything
 * that turns loose packets into connections (joining, resending, ordering), and this interface
 * hides how a platform actually moves the bytes. Platform modules supply the real one; tests
 * supply a fake one that loses and shuffles packets on purpose.
 *
 * <p>Every method is called from the game thread. An implementation that receives on another
 * thread must hand the packets over safely inside {@link #receive(byte[])}.
 *
 * @param <A> The type that identifies who a packet came from or goes to. Two addresses for the
 *     same remote end must be {@code equals} and have the same {@code hashCode}.
 */
public interface FlixelNetDatagramSocket<A> {

  /**
   * Starts the socket.
   *
   * @param port The port to receive on, or 0 to let the system pick one.
   * @throws IllegalStateException If the socket cannot be opened, for example because the port
   *     is in use.
   */
  void open(int port);

  /** Stops the socket and throws away anything that was waiting to be received. */
  void close();

  /**
   * Turns text such as {@code "192.168.1.20:7777"} into an address.
   *
   * @param address The text to read.
   * @return The address.
   * @throws IllegalArgumentException If the text is not a usable address.
   */
  @NotNull
  A resolve(@NotNull String address);

  /**
   * Sends one packet. It may never arrive, and nothing reports whether it did.
   *
   * @param to Where to send it.
   * @param data The array holding the packet.
   * @param offset The index of the first byte of the packet in {@code data}.
   * @param length How many bytes the packet has.
   */
  void send(@NotNull A to, byte @NotNull [] data, int offset, int length);

  /**
   * Takes the next packet that has arrived, if there is one. Never waits.
   *
   * @param into The array to copy the packet into. A packet longer than the array is cut short.
   * @return How many bytes were copied, or -1 if no packet is waiting.
   */
  int receive(byte @NotNull [] into);

  /**
   * Returns who sent the packet most recently returned by {@link #receive(byte[])}.
   *
   * <p>The transport may keep the returned object for as long as it stays connected to that
   * sender, so it must not be changed afterward.
   *
   * @return The sender's address.
   */
  @NotNull
  A getSender();
}
