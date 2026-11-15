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
package org.flixelgdx.net.html5;

import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetCloseReason;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportListener;
import org.flixelgdx.net.transport.FlixelNetTransportState;
import org.jetbrains.annotations.NotNull;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

import java.nio.ByteBuffer;

/**
 * The transport a game running in a browser uses to join a host.
 *
 * <p>A browser tab is not allowed to open ordinary network sockets, and it can never accept
 * connections, so a web game can join a session but never host one. What a browser can do is
 * open a WebSocket to a host, and that is what this transport starts with.
 *
 * <p>Addresses look like {@code "ws://192.168.1.20:7777"}. A plain {@code "host:port"} works too:
 * it becomes {@code ws://}, or {@code wss://} when the page itself was loaded over HTTPS, because
 * browsers refuse unencrypted connections from secure pages (except to {@code localhost}).
 *
 * <h2>How it talks to the browser</h2>
 *
 * <p>Everything crosses the boundary between Java and the browser through small functions that
 * pass only numbers, strings and typed arrays. No Java callback is ever handed to the browser:
 * the browser's own event handlers put what arrives into a queue on the page, and the game
 * empties that queue when it polls. Because a page runs the game and those handlers on one
 * thread, nothing needs a lock.
 */
public final class FlixelHtml5WebTransport implements FlixelNetTransport {

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#UNRELIABLE}. */
  public static final int MAX_UNRELIABLE_SIZE = 1200;

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#RELIABLE}. */
  public static final int MAX_RELIABLE_SIZE = 1 << 17;

  private static final int EVENT_NONE = 0;
  private static final int EVENT_OPEN = 1;
  private static final int EVENT_CLOSE = 2;
  private static final int EVENT_RELIABLE = 3;
  private static final int EVENT_UNRELIABLE = 4;
  private static final int CLOSE_REMOTE = 0;
  private static final int CLOSE_REFUSED = 1;
  private static final int CLOSE_LOCAL = 3;
  private static final int TAG_RELIABLE = 0;
  private static final int TAG_UNRELIABLE = 1;

  /** When this many bytes are still waiting in the browser, new unreliable packets are skipped. */
  private static final int CONGESTED = 1 << 16;
  private static final int MAX_WAITING = 1 << 22;
  private static final int SCRATCH_START = 4096;

  private ByteBuffer outgoing = ByteBuffer.allocateDirect(SCRATCH_START);
  private byte[] incoming = new byte[SCRATCH_START];
  private FlixelNetTransportState state = FlixelNetTransportState.CLOSED;

  /** The number of this transport's connection in the page's table, or zero when it has none. */
  private int handle;

  /** Creates a transport that is not connected yet. */
  public FlixelHtml5WebTransport() {}

  /**
   * Always fails: a browser cannot accept connections.
   *
   * @param port Ignored.
   * @throws IllegalStateException Always.
   */
  @Override
  public void listen(int port) {
    throw new IllegalStateException("A game running in a browser cannot host. Host on desktop, Android or a "
        + "server, and join from the browser.");
  }

  @Override
  public void connect(@NotNull String address) {
    if (state != FlixelNetTransportState.CLOSED) {
      throw new IllegalStateException("The transport is already " + state + "; close() it first.");
    }
    if (address.isEmpty()) {
      throw new IllegalArgumentException("The address to join must not be empty.");
    }
    handle = start(address);
    state = FlixelNetTransportState.CONNECTING;
  }

  @Override
  public void poll(@NotNull FlixelNetTransportListener listener) {
    // The handle is read again every round, because the listener may close this transport.
    while (handle != 0) {
      int event = nextEvent(handle);
      if (event == EVENT_NONE) {
        return;
      }
      if (event == EVENT_OPEN) {
        popEvent(handle);
        state = FlixelNetTransportState.CONNECTED;
        listener.onOpen(HOST);
      } else if (event == EVENT_CLOSE) {
        int code = eventDetail(handle);
        boolean wasOpen = state == FlixelNetTransportState.CONNECTED;
        stop(handle);
        handle = 0;
        state = FlixelNetTransportState.CLOSED;
        FlixelNetCloseReason reason;
        if (code == CLOSE_LOCAL) {
          reason = FlixelNetCloseReason.LOCAL;
        } else if (code == CLOSE_REMOTE) {
          reason = FlixelNetCloseReason.REMOTE;
        } else if (code == CLOSE_REFUSED || !wasOpen) {
          reason = FlixelNetCloseReason.REFUSED;
        } else {
          reason = FlixelNetCloseReason.ERROR;
        }
        listener.onClose(HOST, reason);
      } else {
        int length = eventDetail(handle);
        if (length > incoming.length) {
          incoming = new byte[Math.max(length, incoming.length * 2)];
        }
        Int8Array data = eventData(handle);
        for (int i = 0; i < length; i++) {
          incoming[i] = data.get(i);
        }
        popEvent(handle);
        listener.onData(HOST, event == EVENT_RELIABLE ? FlixelNetChannel.RELIABLE : FlixelNetChannel.UNRELIABLE,
            incoming, 0, length);
      }
    }
  }

  @Override
  public boolean send(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
      int length) {
    if (length > getMaxPacketSize(channel)) {
      throw new IllegalArgumentException("Packet of " + length + " bytes is larger than the "
          + getMaxPacketSize(channel) + " bytes allowed on the " + channel + " channel.");
    }
    if (connection != HOST || state != FlixelNetTransportState.CONNECTED) {
      return false;
    }
    double waiting = waiting(handle);
    if (channel == FlixelNetChannel.UNRELIABLE && waiting > CONGESTED) {
      // Earlier packets are still waiting to leave. This one would be stale by the time its
      // turn came, so it is skipped, which is what "unreliable" allows.
      return true;
    }
    if (waiting > MAX_WAITING) {
      return false;
    }
    if (outgoing.capacity() < length + 1) {
      outgoing = ByteBuffer.allocateDirect(Math.max(length + 1, outgoing.capacity() * 2));
    }
    // The browser can only look at a Java buffer that lives in memory it can see, which a direct
    // buffer does. The view is made again on every send because that memory can be replaced.
    outgoing.put(0, (byte) (channel == FlixelNetChannel.RELIABLE ? TAG_RELIABLE : TAG_UNRELIABLE));
    for (int i = 0; i < length; i++) {
      outgoing.put(i + 1, data[offset + i]);
    }
    sendBytes(handle, Int8Array.fromJavaBuffer(outgoing), length + 1);
    return true;
  }

  @Override
  public void disconnect(int connection) {
    if (connection != HOST || handle == 0) {
      return;
    }
    if (state == FlixelNetTransportState.CONNECTED) {
      // Reported on the next poll, like every other transport does.
      closeLocally(handle);
    } else {
      close();
    }
  }

  @Override
  public void close() {
    if (handle != 0) {
      stop(handle);
      handle = 0;
    }
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

  @JSBody(params = { "address" }, script = """
      var table = window.__flixelNet || (window.__flixelNet = { next: 1, all: {} });
      var id = table.next++;
      var s = table.all[id] = { ws: null, up: false, done: false, events: [] };
      var url = address;
      if (url.indexOf('://') < 0) {
        url = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + url;
      }
      function finish(code) {
        if (s.done) { return; }
        s.done = true;
        var wasUp = s.up;
        s.up = false;
        s.events.push({ kind: 2, detail: wasUp ? code : 1, data: null });
      }
      var ws;
      try { ws = new WebSocket(url); } catch (e) { finish(1); return id; }
      ws.binaryType = 'arraybuffer';
      s.ws = ws;
      ws.onopen = function() {
        if (s.done) { return; }
        s.up = true;
        s.events.push({ kind: 1, detail: 0, data: null });
      };
      ws.onmessage = function(ev) {
        if (s.done || typeof ev.data === 'string') { return; }
        var bytes = new Int8Array(ev.data);
        if (bytes.length < 1) { return; }
        var tag = bytes[0];
        if (tag === 0 || tag === 1) {
          s.events.push({ kind: tag === 0 ? 3 : 4, detail: bytes.length - 1, data: bytes.subarray(1) });
        }
      };
      ws.onclose = function(ev) { finish(ev.wasClean ? 0 : 2); };
      ws.onerror = function() { finish(2); };
      return id;
      """)
  private static native int start(String address);

  @JSBody(params = { "id" }, script = """
      var table = window.__flixelNet;
      var s = table && table.all[id];
      if (!s) { return; }
      s.done = true;
      s.up = false;
      s.events.length = 0;
      if (s.ws) { try { s.ws.close(1000); } catch (e) {} }
      delete table.all[id];
      """)
  private static native void stop(int id);

  @JSBody(params = { "id" }, script = """
      var s = window.__flixelNet.all[id];
      if (!s || s.done) { return; }
      s.done = true;
      s.up = false;
      if (s.ws) { try { s.ws.close(1000); } catch (e) {} }
      s.events.push({ kind: 2, detail: 3, data: null });
      """)
  private static native void closeLocally(int id);

  @JSBody(params = { "id" }, script = """
      var s = window.__flixelNet.all[id];
      return (s && s.events.length) ? s.events[0].kind : 0;
      """)
  private static native int nextEvent(int id);

  @JSBody(params = { "id" }, script = "return window.__flixelNet.all[id].events[0].detail;")
  private static native int eventDetail(int id);

  @JSBody(params = { "id" }, script = "return window.__flixelNet.all[id].events[0].data;")
  private static native Int8Array eventData(int id);

  @JSBody(params = { "id" }, script = """
      var s = window.__flixelNet.all[id];
      if (s && s.events.length) { s.events.shift(); }
      """)
  private static native void popEvent(int id);

  @JSBody(params = { "id" }, script = """
      var s = window.__flixelNet.all[id];
      return (s && s.ws) ? s.ws.bufferedAmount : 0;
      """)
  private static native double waiting(int id);

  @JSBody(params = { "id", "data", "length" }, script = """
      var s = window.__flixelNet.all[id];
      if (s && s.ws && s.up) { s.ws.send(data.subarray(0, length)); }
      """)
  private static native void sendBytes(int id, Int8Array data, int length);
}
