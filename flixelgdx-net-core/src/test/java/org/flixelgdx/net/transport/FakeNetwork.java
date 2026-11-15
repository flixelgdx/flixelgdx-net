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

import java.util.ArrayList;
import java.util.List;

/**
 * A pretend internet for tests: sockets that reach each other in memory, through a network that
 * can lose, copy, delay and shuffle packets on purpose, with a clock the test controls.
 */
final class FakeNetwork implements FlixelNetDatagramTransport.Clock {

  double now;
  float loss;
  float duplicate;
  float delay;
  float jitter;
  int sent;

  private final List<Packet> flying = new ArrayList<>();
  private final List<FakeSocket> sockets = new ArrayList<>();
  private int seed = 12345;
  private int nextEphemeral = 50000;

  @Override
  public double now() {
    return now;
  }

  FakeSocket socket() {
    FakeSocket socket = new FakeSocket();
    sockets.add(socket);
    return socket;
  }

  /** Builds a transport on a new socket of this network, sharing its clock. */
  FlixelNetDatagramTransport<Integer> transport(long secret) {
    return new FlixelNetDatagramTransport<>(socket(), secret, this);
  }

  float random() {
    seed ^= seed << 13;
    seed ^= seed >>> 17;
    seed ^= seed << 5;
    return (seed >>> 8) / (float) (1 << 24);
  }

  private void route(int from, int to, byte[] data, int offset, int length) {
    sent++;
    if (random() < loss) {
      return;
    }
    int copies = random() < duplicate ? 2 : 1;
    for (int i = 0; i < copies; i++) {
      Packet packet = new Packet();
      packet.from = from;
      packet.to = to;
      packet.data = new byte[length];
      System.arraycopy(data, offset, packet.data, 0, length);
      packet.arrives = now + delay + random() * jitter;
      flying.add(packet);
    }
  }

  private static final class Packet {
    double arrives;
    int from;
    int to;
    byte[] data;
  }

  /** One end of the pretend internet. Its address is its port number. */
  final class FakeSocket implements FlixelNetDatagramSocket<Integer> {

    int port = -1;
    private Integer sender;

    @Override
    public void open(int requested) {
      port = requested == 0 ? nextEphemeral++ : requested;
      for (FakeSocket other : sockets) {
        if (other != this && other.port == port) {
          port = -1;
          throw new IllegalStateException("Port " + requested + " is in use.");
        }
      }
    }

    @Override
    public void close() {
      port = -1;
    }

    @Override
    public Integer resolve(String address) {
      try {
        return Integer.valueOf(address);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Not an address: " + address);
      }
    }

    @Override
    public void send(Integer to, byte[] data, int offset, int length) {
      if (port >= 0) {
        route(port, to, data, offset, length);
      }
    }

    /** Sends raw bytes as if they came from any address, for forging packets in tests. */
    void forge(int from, int to, byte... data) {
      route(from, to, data, 0, data.length);
    }

    @Override
    public int receive(byte[] into) {
      if (port < 0) {
        return -1;
      }
      Packet best = null;
      for (Packet packet : flying) {
        if (packet.to == port && packet.arrives <= now && (best == null || packet.arrives < best.arrives)) {
          best = packet;
        }
      }
      if (best == null) {
        return -1;
      }
      flying.remove(best);
      int length = Math.min(into.length, best.data.length);
      System.arraycopy(best.data, 0, into, 0, length);
      sender = best.from;
      return length;
    }

    @Override
    public Integer getSender() {
      return sender;
    }
  }
}
