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

import org.flixelgdx.net.transport.FlixelNetTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Someone on the other end of a {@link FlixelNetSession}.
 *
 * <p>On a host there is one peer for every client that has joined. On a client there is exactly
 * one peer: the host. A peer is handed to the game when it connects, with every message it sends,
 * and when it disconnects, so it is the natural place to hang the game's own data for that
 * player with {@link #setData(Object)}:
 *
 * <pre>{@code
 * session.onConnect.add(peer -> peer.setData(spawnPlayer(peer.getId())));
 * session.on(MoveInput.class, (peer, input) -> ((Player) peer.getData()).apply(input));
 * session.onDisconnect.add(peer -> ((Player) peer.getData()).kill());
 * }</pre>
 */
public final class FlixelNetPeer {

  static final int HANDSHAKING = 0;
  static final int CONNECTED = 1;
  static final int CLOSED = 2;

  /** The session clock when anything was last heard from this peer. */
  double lastHeard;

  long bytesSent;
  long bytesReceived;

  final FlixelNetBuffer reliable = new FlixelNetBuffer();
  final FlixelNetBuffer unreliable = new FlixelNetBuffer();
  final FlixelNetTransport transport;
  final FlixelNetSession.Link link;
  final int connection;
  int id = -1;
  int state = HANDSHAKING;
  int badPackets;
  float rtt;
  FlixelNetDisconnectReason reason;
  private Object data;

  FlixelNetPeer(FlixelNetSession.Link link, int connection, double now) {
    this.link = link;
    this.transport = link.transport;
    this.connection = connection;
    this.lastHeard = now;
  }

  /**
   * Returns the peer's number in the session.
   *
   * <p>The host is always {@link FlixelNetSession#HOST_ID}. Clients are numbered from 1 in the
   * order they join, and a number is never reused while the session lasts.
   *
   * @return The peer's id.
   */
  public int getId() {
    return id;
  }

  /**
   * Returns how long a message takes to reach this peer and for the answer to come back, in
   * milliseconds.
   *
   * <p>This is the number players know as "ping". It is measured about once a second and
   * smoothed, and it is zero until the first measurement.
   *
   * @return The round-trip time in milliseconds.
   */
  public int getPing() {
    return Math.round(rtt * 1000f);
  }

  /**
   * Returns the same measurement as {@link #getPing()}, in seconds.
   *
   * @return The round-trip time in seconds.
   */
  public float getRtt() {
    return rtt;
  }

  /**
   * Returns whether the peer is part of the session right now.
   *
   * @return {@code true} from when the peer connects until it disconnects.
   */
  public boolean isConnected() {
    return state == CONNECTED;
  }

  /**
   * Returns why the peer is no longer part of the session.
   *
   * @return The reason, or {@code null} while the peer is still connected.
   */
  @Nullable
  public FlixelNetDisconnectReason getDisconnectReason() {
    return reason;
  }

  /**
   * Returns the game's own object for this peer, as set with {@link #setData(Object)}.
   *
   * @return The object, or {@code null} if none was set.
   */
  @Nullable
  public Object getData() {
    return data;
  }

  /**
   * Attaches the game's own object to this peer, such as the player it controls.
   *
   * @param data The object to attach, or {@code null} to remove it.
   */
  public void setData(@Nullable Object data) {
    this.data = data;
  }

  /**
   * Returns a short name for how this peer's packets are traveling right now, such as
   * {@code "UDP"}, {@code "WebRTC"}, {@code "WebSocket"} or {@code "Loopback"}.
   *
   * @return The name of the route, meant for showing to a person.
   */
  @NotNull
  public String getRoute() {
    return transport.getRoute(connection);
  }

  /**
   * Returns the transport this peer is connected through.
   *
   * @return The transport.
   */
  @NotNull
  public FlixelNetTransport getTransport() {
    return transport;
  }

  /**
   * Returns the peer's connection id on its transport.
   *
   * @return The connection id.
   */
  public int getConnection() {
    return connection;
  }

  /**
   * Returns how many bytes have been sent to this peer.
   *
   * @return The number of bytes sent.
   */
  public long getBytesSent() {
    return bytesSent;
  }

  /**
   * Returns how many bytes have arrived from this peer.
   *
   * @return The number of bytes received.
   */
  public long getBytesReceived() {
    return bytesReceived;
  }
}
