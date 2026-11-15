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
package org.flixelgdx.net.jvm;

import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.collections.FlixelIntMap;
import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetCloseReason;
import org.flixelgdx.net.transport.FlixelNetIceConfig;
import org.flixelgdx.net.transport.FlixelNetTransportListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * The transport browsers join through: a WebSocket that upgrades itself to WebRTC when it can.
 *
 * <p>Every connection starts as a WebSocket, because that is the one thing every browser can
 * always open. A WebSocket is like registered mail: nothing is lost, but one late packet holds
 * up everything behind it, which is exactly wrong for positions that are replaced many times a
 * second. So the two sides use the WebSocket to arrange a second, better route: a WebRTC
 * connection, which is allowed to drop a late packet and move on.
 *
 * <p>The game never sees any of this. Packets leave over whichever route is ready, the
 * connection keeps its number, and reliable packets stay in order across the switch. If WebRTC
 * cannot be set up (its native library is missing, or a firewall blocks it), the connection just
 * stays on the WebSocket. {@link #getRoute(int)} says which route a connection is on.
 *
 * <h2>What a host needs</h2>
 *
 * <ul>
 *   <li>The native WebRTC library for the host's platform on the classpath (one of the
 *       {@code flixelgdx-net-natives-*} dependencies), or the Android module on Android.</li>
 *   <li>Its WebSocket port reachable over TCP, and for WebRTC a UDP port reachable as well. By
 *       default the system picks the UDP port; {@link #setWebRtcPort(int)} fixes it to one
 *       number, which is what a router or firewall rule needs.</li>
 *   <li>On the open internet, usually a STUN server so the host can learn its public address;
 *       see {@link FlixelNetIceConfig}.</li>
 * </ul>
 *
 * <p>This transport can also connect, which lets a desktop or Android player join exactly the
 * way a browser would.
 */
public class FlixelJvmWebTransport extends FlixelJvmWebSocketTransport {

  private static final int SIGNAL_OFFER = 1;
  private static final int SIGNAL_ANSWER = 2;
  private static final int SIGNAL_CANDIDATE = 3;
  private static final int SIGNAL_SWITCH = 4;
  private static final int SIGNAL_KEEPALIVE = 5;
  private static final int CONGESTED = 1 << 16;
  private static final long KEEPALIVE_NANOS = 20_000_000_000L;
  private static final byte[] EMPTY = new byte[0];

  /** Guards the two fields below, which every transport in the program shares. */
  private static final Object HUB = new Object();

  /** Which transport each native connection belongs to, by handle. */
  @SuppressWarnings("checkstyle:ConstantName")
  private static final FlixelIntMap<FlixelJvmWebTransport> owners = new FlixelIntMap<>();
  private static byte[] hubBuffer = new byte[1 << 16];

  private long lastKeepalive = System.nanoTime();

  private final FlixelIntMap<Route> byConnection = new FlixelIntMap<>();
  private final FlixelIntMap<Route> byHandle = new FlixelIntMap<>();
  private final FlixelArray<Route> routes = new FlixelArray<>();

  /** Native events for this transport that are waiting for its next poll. Guarded by the hub. */
  private final FlixelNetBuffer inbox = new FlixelNetBuffer(0);
  private final FlixelNetBuffer working = new FlixelNetBuffer(0);
  private final FlixelNetBuffer signal = new FlixelNetBuffer(256);
  private FlixelNetTransportListener current;
  private FlixelNetIceConfig ice;
  private int webRtcPort;
  private boolean webRtc = true;

  /** Creates a transport that is not listening or connected yet. */
  public FlixelJvmWebTransport() {}

  @Override
  public void poll(@NotNull FlixelNetTransportListener listener) {
    current = listener;
    try {
      super.poll(listener);
      if (routes.getSize() > 0) {
        pump(listener);
        long now = System.nanoTime();
        if (now - lastKeepalive > KEEPALIVE_NANOS) {
          lastKeepalive = now;
          // A connection that moved to WebRTC leaves its WebSocket silent, and anything in
          // between may cut off a silent WebSocket, so say something now and then.
          for (int i = 0, n = routes.getSize(); i < n; i++) {
            sendSignal(routes.get(i).connection, SIGNAL_KEEPALIVE, EMPTY, 0, 0);
          }
        }
      }
    } finally {
      current = null;
    }
  }

  @Override
  public boolean send(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
      int length) {
    Route route = byConnection.get(connection);
    if (route == null || !route.sending) {
      return super.send(connection, channel, data, offset, length);
    }
    if (length > getMaxPacketSize(channel)) {
      throw new IllegalArgumentException("Packet of " + length + " bytes is larger than the "
          + getMaxPacketSize(channel) + " bytes allowed on the " + channel + " channel.");
    }
    int nativeChannel =
        channel == FlixelNetChannel.RELIABLE ? FlixelJvmRtc.CHANNEL_RELIABLE : FlixelJvmRtc.CHANNEL_UNRELIABLE;
    if (channel == FlixelNetChannel.UNRELIABLE && FlixelJvmRtc.buffered(route.handle, nativeChannel) > CONGESTED) {
      // Earlier packets are still waiting to leave, so this one would be stale on arrival.
      return true;
    }
    return FlixelJvmRtc.send(route.handle, nativeChannel, data, offset, length) >= 0;
  }

  @Override
  public void disconnect(int connection) {
    release(byConnection.get(connection));
    super.disconnect(connection);
  }

  @Override
  public void close() {
    while (!routes.isEmpty()) {
      release(routes.peek());
    }
    super.close();
  }

  @Override
  public @NotNull String getRoute(int connection) {
    Route route = byConnection.get(connection);
    return route != null && route.sending ? "WebRTC" : "WebSocket";
  }

  /**
   * Returns the helper servers WebRTC may use to find a path between the two sides.
   *
   * @return The servers, or {@code null} for none.
   */
  @Nullable
  public FlixelNetIceConfig getIce() {
    return ice;
  }

  /**
   * Sets the helper servers WebRTC may use to find a path between the two sides. Applies to
   * connections made after the call.
   *
   * @param ice The servers, or {@code null} for none.
   */
  public void setIce(@Nullable FlixelNetIceConfig ice) {
    this.ice = ice;
  }

  public int getWebRtcPort() {
    return webRtcPort;
  }

  /**
   * Sets the one UDP port every WebRTC connection of this transport shares.
   *
   * <p>A host behind a router or firewall needs this, because a rule can only be written for a
   * port number that is known in advance. Applies to connections made after the call.
   *
   * @param port The UDP port, or 0 to let the system pick ports.
   * @throws IllegalArgumentException If {@code port} is not from 0 to 65535.
   */
  public void setWebRtcPort(int port) {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("Port must be from 0 to 65535, got " + port + ".");
    }
    webRtcPort = port;
  }

  public boolean isWebRtc() {
    return webRtc;
  }

  /**
   * Sets whether connections may upgrade to WebRTC. Applies to connections made after the call.
   *
   * @param webRtc {@code false} to keep every connection on its WebSocket.
   */
  public void setWebRtc(boolean webRtc) {
    this.webRtc = webRtc;
  }

  @Override
  protected void onConnectionOpened(int connection) {
    // The side that joined makes the offer, the same way a browser does.
    if (connection == HOST) {
      open(connection, true);
    }
  }

  @Override
  protected void onConnectionClosed(int connection) {
    release(byConnection.get(connection));
  }

  @Override
  protected void onSignal(int connection, byte[] data, int offset, int length) {
    if (length < 1) {
      return;
    }
    int kind = data[offset];
    Route route = byConnection.get(connection);
    if (kind == SIGNAL_SWITCH) {
      if (route != null && !route.peerSwitched) {
        // Everything the other side sent over the WebSocket has been handed over by now, so
        // the reliable packets that came early over WebRTC can follow in the right order.
        route.peerSwitched = true;
        FlixelNetBuffer held = route.held;
        held.rewind();
        while (held.remaining() > 0 && byConnection.get(connection) == route && current != null) {
          int size = held.readVarUInt();
          int at = held.getPosition();
          held.skip(size);
          current.onData(connection, FlixelNetChannel.RELIABLE, held.getData(), at, size);
        }
        held.clear();
      }
      return;
    }
    if (kind == SIGNAL_KEEPALIVE) {
      return;
    }
    String text = new String(data, offset + 1, length - 1, StandardCharsets.UTF_8);
    if (kind == SIGNAL_OFFER) {
      if (route == null && connection != HOST) {
        route = open(connection, false);
      }
      if (route != null) {
        FlixelJvmRtc.setRemoteDescription(route.handle, text, "offer");
      }
    } else if (kind == SIGNAL_ANSWER && route != null) {
      FlixelJvmRtc.setRemoteDescription(route.handle, text, "answer");
    } else if (kind == SIGNAL_CANDIDATE && route != null) {
      int split = text.indexOf('\n');
      if (split > 0) {
        FlixelJvmRtc.addRemoteCandidate(route.handle, text.substring(split + 1), text.substring(0, split));
      }
    }
  }

  /** Starts a WebRTC connection alongside a WebSocket connection, if WebRTC can be used. */
  private Route open(int connection, boolean offer) {
    if (!webRtc || !FlixelJvmRtc.isAvailable()) {
      return null;
    }
    int count = ice == null ? 0 : ice.getCount();
    String[] servers = new String[count];
    for (int i = 0; i < count; i++) {
      servers[i] = ice.getUrlWithLogin(i);
    }
    Route route;
    synchronized (HUB) {
      int handle = FlixelJvmRtc.create(servers, webRtcPort, offer);
      if (handle < 0) {
        return null;
      }
      route = new Route(connection, handle);
      owners.put(handle, this);
    }
    byConnection.put(connection, route);
    byHandle.put(route.handle, route);
    routes.add(route);
    return route;
  }

  private void release(Route route) {
    if (route == null) {
      return;
    }
    byConnection.remove(route.connection);
    byHandle.remove(route.handle);
    routes.removeValue(route, true);
    synchronized (HUB) {
      owners.remove(route.handle);
    }
    FlixelJvmRtc.close(route.handle);
  }

  /** Takes this transport's share of what the native library has to report, and acts on it. */
  private void pump(FlixelNetTransportListener listener) {
    synchronized (HUB) {
      // The native library has one queue for the whole program. Whoever polls first sorts
      // everything in it into the inbox of the transport it belongs to.
      while (true) {
        long event = FlixelJvmRtc.poll(hubBuffer);
        if (event == 0) {
          break;
        }
        if (event < 0) {
          hubBuffer = new byte[(int) Math.max(-event, hubBuffer.length * 2L)];
          continue;
        }
        FlixelJvmWebTransport owner = owners.get(FlixelJvmRtc.eventHandle(event));
        if (owner != null) {
          int size = FlixelJvmRtc.eventLength(event);
          owner.inbox.writeByte(FlixelJvmRtc.eventType(event));
          owner.inbox.writeInt(FlixelJvmRtc.eventHandle(event));
          owner.inbox.writeVarUInt(size);
          owner.inbox.writeBytes(hubBuffer, 0, size);
        }
      }
      working.clear();
      working.writeBuffer(inbox);
      inbox.clear();
    }
    while (working.remaining() > 0) {
      int type = working.readUByte();
      int handle = working.readInt();
      int size = working.readVarUInt();
      int at = working.getPosition();
      working.skip(size);
      Route route = byHandle.get(handle);
      if (route != null) {
        handle(route, type, working.getData(), at, size, listener);
      }
    }
  }

  private void handle(Route route, int type, byte[] data, int offset, int length,
      FlixelNetTransportListener listener) {
    switch (type) {
      case FlixelJvmRtc.EVENT_DESCRIPTION -> {
        // The text is "type", a line break, then the description itself.
        int split = indexOf(data, offset, length, '\n');
        boolean offer = split == 5 && data[offset] == 'o';
        sendSignal(route.connection, offer ? SIGNAL_OFFER : SIGNAL_ANSWER, data, offset + split + 1,
            length - split - 1);
      }
      case FlixelJvmRtc.EVENT_CANDIDATE -> sendSignal(route.connection, SIGNAL_CANDIDATE, data, offset, length);
      case FlixelJvmRtc.EVENT_OPEN -> {
        if (length > 0 && data[offset] == FlixelJvmRtc.CHANNEL_RELIABLE) {
          route.reliableOpen = true;
        } else {
          route.unreliableOpen = true;
        }
        if (route.reliableOpen && route.unreliableOpen && !route.sending) {
          // Tell the other side that reliable packets come over WebRTC from here on, so it can
          // keep them in order with the ones still on their way over the WebSocket.
          sendSignal(route.connection, SIGNAL_SWITCH, EMPTY, 0, 0);
          route.sending = true;
        }
      }
      case FlixelJvmRtc.EVENT_RELIABLE -> {
        if (route.peerSwitched) {
          listener.onData(route.connection, FlixelNetChannel.RELIABLE, data, offset, length);
        } else {
          route.held.writeVarUInt(length);
          route.held.writeBytes(data, offset, length);
        }
      }
      case FlixelJvmRtc.EVENT_UNRELIABLE ->
        listener.onData(route.connection, FlixelNetChannel.UNRELIABLE, data, offset, length);
      case FlixelJvmRtc.EVENT_STATE, FlixelJvmRtc.EVENT_CLOSED -> {
        boolean broken = type == FlixelJvmRtc.EVENT_CLOSED
            || length > 0 && (data[offset] == FlixelJvmRtc.STATE_FAILED || data[offset] == FlixelJvmRtc.STATE_CLOSED);
        if (!broken) {
          return;
        }
        if (route.sending) {
          // Packets were already traveling this way, so the connection as a whole is broken.
          drop(route.connection, FlixelNetCloseReason.ERROR);
        }
        // Otherwise WebRTC simply never got going, and the WebSocket carries on by itself.
        release(route);
      }
      default -> {
        // Nothing else needs handling.
      }
    }
  }

  /** Sends a signal made of a kind byte followed by some bytes. */
  private void sendSignal(int connection, int kind, byte[] data, int offset, int length) {
    signal.clear();
    signal.writeByte(kind);
    signal.writeBytes(data, offset, length);
    sendSignal(connection, signal.getData(), 0, signal.getSize());
  }

  private static int indexOf(byte[] data, int offset, int length, char wanted) {
    for (int i = 0; i < length; i++) {
      if (data[offset + i] == wanted) {
        return i;
      }
    }
    return -1;
  }

  /** The WebRTC side of one connection. */
  private static final class Route {

    /** Reliable packets that arrived over WebRTC before the other side said it had switched. */
    final FlixelNetBuffer held = new FlixelNetBuffer(0);
    final int connection;
    final int handle;
    boolean reliableOpen;
    boolean unreliableOpen;

    /** Whether this side now sends over WebRTC. */
    boolean sending;

    /** Whether the other side has said that its reliable packets now come over WebRTC. */
    boolean peerSwitched;

    Route(int connection, int handle) {
      this.connection = connection;
      this.handle = handle;
    }
  }
}
