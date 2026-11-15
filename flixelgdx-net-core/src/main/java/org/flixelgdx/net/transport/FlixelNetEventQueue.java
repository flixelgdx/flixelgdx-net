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
 * A waiting line of transport events (opens, closes and packets) that is filled as things happen
 * and emptied when the game polls.
 *
 * <p>This is a building block for writing a {@link FlixelNetTransport}. A transport pushes events
 * in as they occur and calls {@link #drain(FlixelNetTransportListener)} from its
 * {@code poll(...)} method. Packet bytes are copied into one shared array that is reused, so a
 * busy connection does not create garbage once the queue has grown to its working size.
 *
 * <p>Events always come out in the order they went in. Each event can carry a time before which
 * it must not be delivered, which is how a transport can imitate a slow connection.
 *
 * <p>The queue is not safe to use from several threads at once. A transport that receives on
 * another thread must guard it with its own lock.
 */
public final class FlixelNetEventQueue {

  private static final int OPEN = 0;
  private static final int CLOSE = 1;
  private static final int DATA = 2;
  private static final int INITIAL_EVENTS = 16;
  private static final int INITIAL_BYTES = 1024;
  private static final FlixelNetChannel[] CHANNELS = FlixelNetChannel.values();
  private static final FlixelNetCloseReason[] REASONS = FlixelNetCloseReason.values();

  private double lastTime = Double.NEGATIVE_INFINITY;

  private double[] times = new double[INITIAL_EVENTS];
  private int[] types = new int[INITIAL_EVENTS];
  private int[] connections = new int[INITIAL_EVENTS];
  private int[] details = new int[INITIAL_EVENTS];
  private int[] offsets = new int[INITIAL_EVENTS];
  private int[] lengths = new int[INITIAL_EVENTS];
  private byte[] bytes = new byte[INITIAL_BYTES];
  private int head;
  private int tail;
  private int used;

  private boolean draining;

  /** Creates an empty queue. */
  public FlixelNetEventQueue() {}

  /**
   * Adds an event saying a connection opened, deliverable right away.
   *
   * @param connection The id of the connection.
   */
  public void pushOpen(int connection) {
    pushOpen(connection, Double.NEGATIVE_INFINITY);
  }

  /**
   * Adds an event saying a connection opened.
   *
   * @param connection The id of the connection.
   * @param time The earliest time the event may be delivered.
   */
  public void pushOpen(int connection, double time) {
    push(OPEN, connection, 0, 0, time);
  }

  /**
   * Adds an event saying a connection closed, deliverable right away.
   *
   * @param connection The id of the connection.
   * @param reason Why the connection closed.
   */
  public void pushClose(int connection, @NotNull FlixelNetCloseReason reason) {
    pushClose(connection, reason, Double.NEGATIVE_INFINITY);
  }

  /**
   * Adds an event saying a connection closed.
   *
   * @param connection The id of the connection.
   * @param reason Why the connection closed.
   * @param time The earliest time the event may be delivered.
   */
  public void pushClose(int connection, @NotNull FlixelNetCloseReason reason, double time) {
    push(CLOSE, connection, reason.ordinal(), 0, time);
  }

  /**
   * Adds a packet that arrived, deliverable right away. The bytes are copied.
   *
   * @param connection The id of the connection the packet came from.
   * @param channel The channel the packet was sent on.
   * @param data The array holding the packet.
   * @param offset The index of the first byte of the packet in {@code data}.
   * @param length How many bytes the packet has.
   */
  public void pushData(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
      int length) {
    pushData(connection, channel, data, offset, length, Double.NEGATIVE_INFINITY);
  }

  /**
   * Adds a packet that arrived. The bytes are copied.
   *
   * @param connection The id of the connection the packet came from.
   * @param channel The channel the packet was sent on.
   * @param data The array holding the packet.
   * @param offset The index of the first byte of the packet in {@code data}.
   * @param length How many bytes the packet has.
   * @param time The earliest time the packet may be delivered.
   * @throws IndexOutOfBoundsException If the range does not fit inside {@code data}.
   */
  public void pushData(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
      int length, double time) {
    if (offset < 0 || length < 0 || offset > data.length - length) {
      throw new IndexOutOfBoundsException(
          "Range of " + length + " bytes at " + offset + " does not fit in an array of " + data.length + ".");
    }
    if (used + length > bytes.length) {
      byte[] bigger = new byte[Math.max(bytes.length << 1, used + length)];
      System.arraycopy(bytes, 0, bigger, 0, used);
      bytes = bigger;
    }
    System.arraycopy(data, offset, bytes, used, length);
    push(DATA, connection, channel.ordinal(), length, time);
    used += length;
  }

  /**
   * Delivers every waiting event, in order, and removes it from the queue.
   *
   * @param listener What to deliver the events to.
   * @return How many events were delivered.
   * @throws IllegalStateException If called from inside a listener that this queue is already
   *     delivering to.
   */
  public int drain(@NotNull FlixelNetTransportListener listener) {
    return drain(listener, Double.POSITIVE_INFINITY);
  }

  /**
   * Delivers the waiting events whose time has come, in order, and removes them from the queue.
   *
   * <p>Delivery stops at the first event that is not due yet, so later events never overtake it.
   * Events that the listener causes to be added while this runs are delivered in the same call if
   * they are due.
   *
   * @param listener What to deliver the events to.
   * @param now The current time, on the same clock used when the events were pushed.
   * @return How many events were delivered.
   * @throws IllegalStateException If called from inside a listener that this queue is already
   *     delivering to.
   */
  public int drain(@NotNull FlixelNetTransportListener listener, double now) {
    if (draining) {
      throw new IllegalStateException("A transport cannot be polled from inside its own listener.");
    }
    draining = true;
    int delivered = 0;
    try {
      while (head < tail && times[head] <= now) {
        int i = head++;
        switch (types[i]) {
          case OPEN -> listener.onOpen(connections[i]);
          case CLOSE -> listener.onClose(connections[i], REASONS[details[i]]);
          default -> listener.onData(connections[i], CHANNELS[details[i]], bytes, offsets[i], lengths[i]);
        }
        delivered++;
      }
    } finally {
      draining = false;
      compact();
    }
    return delivered;
  }

  /** Throws away every waiting event. */
  public void clear() {
    head = 0;
    tail = 0;
    used = 0;
    lastTime = Double.NEGATIVE_INFINITY;
  }

  /**
   * Returns how many events are waiting, whether or not they are due yet.
   *
   * @return The number of waiting events.
   */
  public int getSize() {
    return tail - head;
  }

  private void push(int type, int connection, int detail, int length, double time) {
    if (tail == times.length) {
      grow();
    }
    // Times never go backward, so an event can never be delivered before one that was pushed
    // ahead of it, even if the delay changes between the two.
    lastTime = Math.max(lastTime, time);
    times[tail] = lastTime;
    types[tail] = type;
    connections[tail] = connection;
    details[tail] = detail;
    offsets[tail] = used;
    lengths[tail] = length;
    tail++;
  }

  private void grow() {
    int size = times.length << 1;
    double[] newTimes = new double[size];
    System.arraycopy(times, 0, newTimes, 0, tail);
    times = newTimes;
    types = grow(types, size);
    connections = grow(connections, size);
    details = grow(details, size);
    offsets = grow(offsets, size);
    lengths = grow(lengths, size);
  }

  private int[] grow(int[] old, int size) {
    int[] bigger = new int[size];
    System.arraycopy(old, 0, bigger, 0, tail);
    return bigger;
  }

  /** Moves the events that are still waiting to the front so the space behind them is reused. */
  private void compact() {
    if (head == tail) {
      head = 0;
      tail = 0;
      used = 0;
      return;
    }
    if (head == 0) {
      return;
    }
    int count = tail - head;
    int base = offsets[head];
    System.arraycopy(bytes, base, bytes, 0, used - base);
    System.arraycopy(times, head, times, 0, count);
    System.arraycopy(types, head, types, 0, count);
    System.arraycopy(connections, head, connections, 0, count);
    System.arraycopy(details, head, details, 0, count);
    System.arraycopy(lengths, head, lengths, 0, count);
    for (int i = 0; i < count; i++) {
      offsets[i] = offsets[head + i] - base;
    }
    used -= base;
    head = 0;
    tail = count;
  }
}
