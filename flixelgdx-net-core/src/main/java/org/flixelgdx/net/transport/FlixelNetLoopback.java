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

import org.flixelgdx.collections.FlixelArray;
import org.jetbrains.annotations.NotNull;

/**
 * A pretend network that lives entirely inside one program.
 *
 * <p>It hands out one host transport and any number of client transports that talk to each other
 * directly in memory, with no sockets and no internet. That makes it useful for three things:
 *
 * <ul>
 *   <li>Unit tests, because nothing depends on a real network.</li>
 *   <li>A host that is also a player. The host's own player joins through a loopback client, so
 *       the game code is the same for the host and for everyone else.</li>
 *   <li>Trying a game under bad conditions. {@link #setLatency(float)} makes every packet arrive
 *       late and {@link #setLoss(float)} makes unreliable packets go missing, the way they do on a
 *       real connection.</li>
 * </ul>
 *
 * <pre>{@code
 * FlixelNetLoopback loopback = new FlixelNetLoopback();
 * FlixelNetTransport host = loopback.getHost();
 * FlixelNetTransport client = loopback.newClient();
 *
 * host.listen(0);
 * client.connect("");
 *
 * // Once per frame:
 * loopback.advance(elapsed);
 * host.poll(hostListener);
 * client.poll(clientListener);
 * }</pre>
 *
 * <p>The port and address are ignored, since there is only one host to reach. Everything here
 * must be used from a single thread.
 */
public final class FlixelNetLoopback {

  /** The largest packet, in bytes, the loopback accepts on {@link FlixelNetChannel#UNRELIABLE}. */
  public static final int MAX_UNRELIABLE_SIZE = 1200;

  /** The largest packet, in bytes, the loopback accepts on {@link FlixelNetChannel#RELIABLE}. */
  public static final int MAX_RELIABLE_SIZE = 65535;

  private static final int DEFAULT_SEED = 0x9E3779B9;

  private double clock;

  private final Endpoint host = new Endpoint(true);

  /** Client ends by host-side connection id minus one. A slot is null once its client is gone. */
  private final FlixelArray<Endpoint> clients = new FlixelArray<>();
  private int nextConnection = 1;
  private int connected;
  private int seed = DEFAULT_SEED;
  private float latency;
  private float loss;

  /** Creates a loopback with no delay and no packet loss. */
  public FlixelNetLoopback() {}

  /**
   * Creates a new client end that can connect to this loopback's host.
   *
   * @return A transport that acts as a client.
   */
  @NotNull
  public FlixelNetTransport newClient() {
    return new Endpoint(false);
  }

  /**
   * Moves the loopback's clock forward.
   *
   * <p>Call this once per frame when {@link #setLatency(float)} is in use; delayed packets become
   * deliverable as the clock passes their arrival time. Without latency there is no need to call
   * it.
   *
   * @param elapsed How many seconds have passed since the last call.
   */
  public void advance(float elapsed) {
    if (elapsed > 0f) {
      clock += elapsed;
    }
  }

  /**
   * Returns the host end of the loopback.
   *
   * @return A transport that acts as the host. It is always the same object.
   */
  @NotNull
  public FlixelNetTransport getHost() {
    return host;
  }

  /**
   * Returns how many clients are connected to the host right now.
   *
   * @return The number of connected clients.
   */
  public int getClientCount() {
    return connected;
  }

  public float getLatency() {
    return latency;
  }

  /**
   * Sets how late everything arrives.
   *
   * <p>The delay applies to each direction on its own, so the time for a question to go out and
   * its answer to come back (the "ping") is twice this value. {@link #advance(float)} must be
   * called every frame for delayed packets to arrive.
   *
   * @param seconds The one-way delay in seconds. Zero turns the delay off.
   * @throws IllegalArgumentException If {@code seconds} is negative or not a number.
   */
  public void setLatency(float seconds) {
    if (!(seconds >= 0f)) {
      throw new IllegalArgumentException("Latency must be zero or more, got " + seconds + ".");
    }
    latency = seconds;
  }

  public float getLoss() {
    return loss;
  }

  /**
   * Sets how often packets on {@link FlixelNetChannel#UNRELIABLE} go missing.
   *
   * <p>Packets on {@link FlixelNetChannel#RELIABLE} are never lost.
   *
   * @param fraction The share of packets to drop, from 0 (none) to 1 (all).
   * @throws IllegalArgumentException If {@code fraction} is not from 0 to 1.
   */
  public void setLoss(float fraction) {
    if (!(fraction >= 0f && fraction <= 1f)) {
      throw new IllegalArgumentException("Loss must be from 0 to 1, got " + fraction + ".");
    }
    loss = fraction;
  }

  /**
   * Sets the starting point for the random choices of which packets get lost.
   *
   * <p>The same seed always loses the same packets, which keeps tests repeatable.
   *
   * @param seed Any number.
   */
  public void setSeed(int seed) {
    this.seed = seed == 0 ? DEFAULT_SEED : seed;
  }

  /**
   * Returns whether the next unreliable packet should be lost, using a small repeatable random
   * number generator.
   */
  private boolean dropNext() {
    if (loss <= 0f) {
      return false;
    }
    seed ^= seed << 13;
    seed ^= seed >>> 17;
    seed ^= seed << 5;
    return (seed >>> 8) / (float) (1 << 24) < loss;
  }

  /** One end of the loopback: either the host or a single client. */
  private final class Endpoint implements FlixelNetTransport, FlixelNetTransportListener {

    private final FlixelNetEventQueue queue = new FlixelNetEventQueue();
    private FlixelNetTransportListener target;
    private FlixelNetTransportState state = FlixelNetTransportState.CLOSED;

    /** For a client end, the id the host knows it by. */
    private int id;

    private final boolean hosting;

    /** For a client end, whether it is currently joined to the host. */
    private boolean linked;

    Endpoint(boolean hosting) {
      this.hosting = hosting;
    }

    @Override
    public void listen(int port) {
      if (!hosting) {
        throw new IllegalStateException("This is a client end of the loopback; use getHost() to listen.");
      }
      requireClosed();
      state = FlixelNetTransportState.LISTENING;
    }

    @Override
    public void connect(@NotNull String address) {
      if (hosting) {
        throw new IllegalStateException("This is the host end of the loopback; use newClient() to connect.");
      }
      requireClosed();
      state = FlixelNetTransportState.CONNECTING;
      double arrival = clock + latency;
      if (host.state != FlixelNetTransportState.LISTENING) {
        queue.pushClose(HOST, FlixelNetCloseReason.REFUSED, arrival);
        return;
      }
      id = nextConnection++;
      linked = true;
      connected++;
      clients.add(this);
      host.queue.pushOpen(id, arrival);
      queue.pushOpen(HOST, arrival);
    }

    @Override
    public void poll(@NotNull FlixelNetTransportListener listener) {
      target = listener;
      try {
        queue.drain(this, clock);
      } finally {
        target = null;
      }
    }

    @Override
    public boolean send(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
        int length) {
      if (length > getMaxPacketSize(channel)) {
        throw new IllegalArgumentException("Packet of " + length + " bytes is larger than the "
            + getMaxPacketSize(channel) + " bytes allowed on the " + channel + " channel.");
      }
      Endpoint receiver;
      int sender;
      if (hosting) {
        receiver = client(connection);
        sender = HOST;
        if (receiver == null) {
          return false;
        }
      } else {
        if (connection != HOST || !linked || state != FlixelNetTransportState.CONNECTED) {
          return false;
        }
        receiver = host;
        sender = id;
      }
      if (channel == FlixelNetChannel.UNRELIABLE && dropNext()) {
        return true;
      }
      receiver.queue.pushData(sender, channel, data, offset, length, clock + latency);
      return true;
    }

    @Override
    public void disconnect(int connection) {
      double arrival = clock + latency;
      if (hosting) {
        Endpoint client = client(connection);
        if (client == null) {
          return;
        }
        unlink(client);
        queue.pushClose(connection, FlixelNetCloseReason.LOCAL, clock);
        client.queue.pushClose(HOST, FlixelNetCloseReason.REMOTE, arrival);
      } else if (connection == HOST && linked) {
        unlink(this);
        queue.pushClose(HOST, FlixelNetCloseReason.LOCAL, clock);
        host.queue.pushClose(id, FlixelNetCloseReason.REMOTE, arrival);
      }
    }

    @Override
    public void close() {
      double arrival = clock + latency;
      if (hosting) {
        for (int i = 0, n = clients.getSize(); i < n; i++) {
          Endpoint client = clients.get(i);
          if (client != null) {
            unlink(client);
            client.queue.pushClose(HOST, FlixelNetCloseReason.REMOTE, arrival);
          }
        }
      } else if (linked) {
        unlink(this);
        host.queue.pushClose(id, FlixelNetCloseReason.REMOTE, arrival);
      }
      queue.clear();
      state = FlixelNetTransportState.CLOSED;
    }

    @Override
    public int getMaxPacketSize(@NotNull FlixelNetChannel channel) {
      return channel == FlixelNetChannel.UNRELIABLE ? MAX_UNRELIABLE_SIZE : MAX_RELIABLE_SIZE;
    }

    @Override
    public @NotNull FlixelNetTransportState getState() {
      return state;
    }

    @Override
    public void onOpen(int connection) {
      if (!hosting) {
        state = FlixelNetTransportState.CONNECTED;
      }
      target.onOpen(connection);
    }

    @Override
    public void onClose(int connection, FlixelNetCloseReason reason) {
      if (!hosting) {
        state = FlixelNetTransportState.CLOSED;
      }
      target.onClose(connection, reason);
    }

    @Override
    public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
      target.onData(connection, channel, data, offset, length);
    }

    /** Returns the joined client the host knows by the given id, or null if there is none. */
    private Endpoint client(int connection) {
      if (connection < 1 || connection > clients.getSize()) {
        return null;
      }
      return clients.get(connection - 1);
    }

    private void unlink(Endpoint client) {
      client.linked = false;
      clients.set(client.id - 1, null);
      connected--;
    }

    private void requireClosed() {
      if (state != FlixelNetTransportState.CLOSED) {
        throw new IllegalStateException("The transport is already " + state + "; close() it first.");
      }
    }
  }
}
