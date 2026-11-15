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
import org.flixelgdx.net.transport.FlixelNetEventQueue;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportListener;
import org.flixelgdx.net.transport.FlixelNetTransportState;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.net.ssl.SSLSocketFactory;

/**
 * A transport that speaks WebSocket, the one kind of connection every browser can open.
 *
 * <p>This is what lets a game in a browser tab join a host on desktop, Android or a server. It
 * can listen (so browsers and other programs can join it) and it can connect (so a desktop or
 * Android player can join a host the same way a browser would).
 *
 * <p>A WebSocket is like registered mail: everything arrives, in order, and a late packet holds
 * up the ones behind it. That means there is no truly unreliable channel here. Packets sent on
 * {@link FlixelNetChannel#UNRELIABLE} still arrive marked as unreliable, with one difference that
 * keeps a slow connection from falling ever further behind: when earlier packets are still
 * waiting to go out, new unreliable ones are skipped instead of joining the line.
 *
 * <p>Addresses look like {@code "ws://192.168.1.20:7777"}. A plain {@code "host:port"} is treated
 * the same way, and {@code "wss://"} connects securely. Listening is always plain {@code ws://};
 * to offer {@code wss://}, which browsers require from pages loaded over HTTPS, put the host
 * behind a web server that handles the encryption.
 *
 * <p>The network work happens on background threads, but the listener is only ever called from
 * {@link #poll(FlixelNetTransportListener)}.
 */
public class FlixelJvmWebSocketTransport implements FlixelNetTransport {

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#UNRELIABLE}. */
  public static final int MAX_UNRELIABLE_SIZE = 1200;

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#RELIABLE}. */
  public static final int MAX_RELIABLE_SIZE = 1 << 17;

  private static final int MAX_MESSAGE = 1 << 20;
  private static final int MAX_PENDING = 1 << 22;
  private static final int CONGESTED = 1 << 16;
  private static final int MAX_HANDSHAKE = 8192;
  private static final int HANDSHAKE_TIMEOUT = 10_000;
  private static final int TAG_RELIABLE = 0;
  private static final int TAG_UNRELIABLE = 1;
  private static final int TAG_SIGNAL = 2;
  private static final int OP_CONTINUE = 0x0;
  private static final int OP_TEXT = 0x1;
  private static final int OP_BINARY = 0x2;
  private static final int OP_CLOSE = 0x8;
  private static final int OP_PING = 0x9;
  private static final int OP_PONG = 0xA;
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  /** Guards everything below that both the game thread and the network threads touch. */
  private final Object lock = new Object();
  private final FlixelNetEventQueue queue = new FlixelNetEventQueue();
  private final FlixelIntMap<Connection> connections = new FlixelIntMap<>();
  private final FlixelArray<Connection> closing = new FlixelArray<>();
  private final Relay relay = new Relay();
  private volatile FlixelNetTransportState state = FlixelNetTransportState.CLOSED;
  private ServerSocket server;
  private int nextId = 1;
  private int maxConnections = 64;

  /** Raised by every close, so threads left over from an earlier use know to keep quiet. */
  private int generation;

  /** Creates a transport that is not listening or connected yet. */
  public FlixelJvmWebSocketTransport() {}

  @Override
  public void listen(int port) {
    synchronized (lock) {
      requireClosed();
      ServerSocket opened;
      try {
        opened = new ServerSocket();
        opened.setReuseAddress(true);
        opened.bind(new InetSocketAddress(port));
      } catch (IOException e) {
        throw new IllegalStateException("Could not listen for WebSockets on port " + port + ": " + e.getMessage(), e);
      }
      server = opened;
      state = FlixelNetTransportState.LISTENING;
      int started = generation;
      Thread thread = new Thread(() -> acceptLoop(opened, started), "FlixelNet WebSocket accept");
      thread.setDaemon(true);
      thread.start();
    }
  }

  @Override
  public void connect(@NotNull String address) {
    Target target = new Target(address);
    synchronized (lock) {
      requireClosed();
      state = FlixelNetTransportState.CONNECTING;
      Connection conn = new Connection(HOST, generation, true);
      connections.put(HOST, conn);
      Thread thread = new Thread(() -> run(conn, null, target), "FlixelNet WebSocket");
      thread.setDaemon(true);
      thread.start();
    }
  }

  @Override
  public void poll(@NotNull FlixelNetTransportListener listener) {
    synchronized (lock) {
      relay.target = listener;
      try {
        queue.drain(relay);
      } finally {
        relay.target = null;
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
    return write(connection, channel == FlixelNetChannel.RELIABLE ? TAG_RELIABLE : TAG_UNRELIABLE, data, offset,
        length);
  }

  @Override
  public void disconnect(int connection) {
    Connection conn;
    synchronized (lock) {
      conn = connections.get(connection);
      if (conn == null) {
        return;
      }
      forget(conn);
      if (conn.open) {
        queue.pushClose(conn.id, FlixelNetCloseReason.LOCAL);
      }
    }
    finish(conn);
  }

  @Override
  public void close() {
    ServerSocket closingServer;
    synchronized (lock) {
      if (state == FlixelNetTransportState.CLOSED) {
        return;
      }
      generation++;
      closingServer = server;
      server = null;
      closing.clear();
      for (Connection conn : connections.values()) {
        conn.closed = true;
        closing.add(conn);
      }
      connections.clear();
      queue.clear();
      state = FlixelNetTransportState.CLOSED;
      for (int i = 0, n = closing.getSize(); i < n; i++) {
        finish(closing.get(i));
      }
      closing.clear();
    }
    if (closingServer != null) {
      try {
        closingServer.close();
      } catch (IOException e) {
        // Nothing useful can be done about a socket that fails to close.
      }
    }
  }

  @Override
  public int getMaxPacketSize(@NotNull FlixelNetChannel channel) {
    return channel == FlixelNetChannel.UNRELIABLE ? MAX_UNRELIABLE_SIZE : MAX_RELIABLE_SIZE;
  }

  @Override
  public @NotNull String getRoute(int connection) {
    return "WebSocket";
  }

  @Override
  public @NotNull FlixelNetTransportState getState() {
    return state;
  }

  /**
   * Returns the port being listened on.
   *
   * @return The port, or -1 if the transport is not listening.
   */
  public int getPort() {
    synchronized (lock) {
      return server != null ? server.getLocalPort() : -1;
    }
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  /**
   * Sets how many clients a host accepts at once. Anyone past that is turned away.
   *
   * @param maxConnections The largest number of connections. Must be at least 1.
   * @throws IllegalArgumentException If {@code maxConnections} is less than 1.
   */
  public void setMaxConnections(int maxConnections) {
    if (maxConnections < 1) {
      throw new IllegalArgumentException("Max connections must be at least 1, got " + maxConnections + ".");
    }
    this.maxConnections = maxConnections;
  }

  /**
   * Called on the game thread, during {@link #poll(FlixelNetTransportListener)}, when the other
   * side sent a signaling message.
   *
   * <p>Signaling messages are not game data. They are a side channel that lets a richer transport
   * built on this one arrange a better connection. The array is only borrowed. This
   * implementation ignores them.
   *
   * @param connection The id of the connection the message came from.
   * @param data The array holding the message.
   * @param offset The index of the first byte of the message in {@code data}.
   * @param length How many bytes the message has.
   */
  protected void onSignal(int connection, byte[] data, int offset, int length) {}

  /**
   * Sends a signaling message, which arrives at the other side's
   * {@link #onSignal(int, byte[], int, int)} instead of at its listener.
   *
   * @param connection The id of the connection to send on.
   * @param data The array holding the message.
   * @param offset The index of the first byte of the message in {@code data}.
   * @param length How many bytes the message has.
   * @return {@code true} if the message was accepted for sending.
   */
  protected final boolean sendSignal(int connection, byte[] data, int offset, int length) {
    return write(connection, TAG_SIGNAL, data, offset, length);
  }

  /**
   * Called on the game thread, during {@link #poll(FlixelNetTransportListener)}, right before
   * the listener hears that a connection opened.
   *
   * @param connection The id of the connection.
   */
  protected void onConnectionOpened(int connection) {}

  /**
   * Called on the game thread, during {@link #poll(FlixelNetTransportListener)}, right before
   * the listener hears that a connection closed.
   *
   * @param connection The id of the connection.
   */
  protected void onConnectionClosed(int connection) {}

  /**
   * Ends a connection because something went wrong with it. The listener hears about it on the
   * next poll.
   *
   * @param connection The id of the connection to end.
   * @param reason Why it is being ended.
   */
  protected final void drop(int connection, FlixelNetCloseReason reason) {
    Connection conn;
    synchronized (lock) {
      conn = connections.get(connection);
    }
    if (conn != null) {
      fail(conn, reason);
    }
  }

  private boolean write(int connection, int tag, byte[] data, int offset, int length) {
    Connection conn;
    synchronized (lock) {
      conn = connections.get(connection);
    }
    if (conn == null || !conn.open) {
      return false;
    }
    boolean overflow = false;
    synchronized (conn) {
      int waiting = conn.pending.getSize();
      if (tag == TAG_UNRELIABLE && waiting > CONGESTED) {
        // Earlier packets are still waiting to leave. This one would be stale by the time its
        // turn came, so it is skipped, which is what "unreliable" allows.
        return true;
      }
      if (waiting + length > MAX_PENDING) {
        overflow = true;
      } else {
        frame(conn, OP_BINARY, tag, data, offset, length);
        conn.notifyAll();
      }
    }
    if (overflow) {
      // The other side has stopped taking in what is sent.
      fail(conn, FlixelNetCloseReason.ERROR);
      return false;
    }
    return true;
  }

  /** Runs on its own thread while listening: takes new connections as they come in. */
  private void acceptLoop(ServerSocket listening, int started) {
    while (!listening.isClosed()) {
      Socket socket;
      try {
        socket = listening.accept();
      } catch (IOException e) {
        continue;
      }
      Connection conn = null;
      synchronized (lock) {
        if (generation == started && connections.getSize() < maxConnections) {
          conn = new Connection(nextId++, generation, false);
          connections.put(conn.id, conn);
        }
      }
      if (conn == null) {
        closeQuietly(socket);
        continue;
      }
      Connection accepted = conn;
      Thread thread = new Thread(() -> run(accepted, socket, null), "FlixelNet WebSocket");
      thread.setDaemon(true);
      thread.start();
    }
  }

  /** Runs on one thread per connection: joins, then reads until the connection ends. */
  private void run(Connection conn, Socket accepted, Target target) {
    FlixelNetCloseReason reason = FlixelNetCloseReason.ERROR;
    Socket socket = accepted;
    try {
      if (socket == null) {
        socket = target.secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        synchronized (conn) {
          conn.socket = socket;
        }
        socket.connect(new InetSocketAddress(target.host, target.port), HANDSHAKE_TIMEOUT);
      } else {
        synchronized (conn) {
          conn.socket = socket;
        }
      }
      if (conn.closed) {
        return;
      }
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(HANDSHAKE_TIMEOUT);
      InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
      OutputStream out = socket.getOutputStream();
      if (target == null) {
        acceptHandshake(in, out);
      } else {
        requestHandshake(in, out, target);
      }
      socket.setSoTimeout(0);
      synchronized (lock) {
        if (conn.closed || conn.generation != generation) {
          return;
        }
        conn.open = true;
        if (conn.client) {
          state = FlixelNetTransportState.CONNECTED;
        }
        queue.pushOpen(conn.id);
      }
      Thread writer = new Thread(() -> writeLoop(conn, out), "FlixelNet WebSocket send");
      writer.setDaemon(true);
      writer.start();
      reason = readLoop(conn, in);
    } catch (IOException | RuntimeException e) {
      reason = FlixelNetCloseReason.ERROR;
    } finally {
      if (!conn.open) {
        // No sending thread exists yet to close the socket, so it has to happen here.
        closeQuietly(socket);
      }
      fail(conn, conn.open ? reason : FlixelNetCloseReason.REFUSED);
    }
  }

  /** Reads frames until the connection ends, and returns why it ended. */
  private FlixelNetCloseReason readLoop(Connection conn, InputStream in) throws IOException {
    byte[] message = new byte[4096];
    byte[] control = new byte[125];
    byte[] mask = new byte[4];
    int size = 0;
    boolean assembling = false;
    while (true) {
      int first = in.read();
      if (first < 0) {
        return FlixelNetCloseReason.REMOTE;
      }
      int second = readByte(in);
      boolean last = (first & 0x80) != 0;
      int opcode = first & 0x0F;
      boolean masked = (second & 0x80) != 0;
      long length = second & 0x7F;
      if (length == 126) {
        length = (readByte(in) << 8) | readByte(in);
      } else if (length == 127) {
        length = 0;
        for (int i = 0; i < 8; i++) {
          length = (length << 8) | readByte(in);
        }
      }
      // A browser must scramble what it sends and a server must not; anything else is not a
      // well-behaved WebSocket.
      if ((first & 0x70) != 0 || masked == conn.client || length < 0 || length > MAX_MESSAGE) {
        return FlixelNetCloseReason.ERROR;
      }
      if (masked) {
        readFully(in, mask, 0, 4);
      }
      if (opcode >= OP_CLOSE) {
        if (length > control.length || !last) {
          return FlixelNetCloseReason.ERROR;
        }
        readFully(in, control, 0, (int) length);
        unmask(control, 0, (int) length, mask, masked);
        if (opcode == OP_CLOSE) {
          return FlixelNetCloseReason.REMOTE;
        }
        if (opcode == OP_PING) {
          synchronized (conn) {
            frame(conn, OP_PONG, -1, control, 0, (int) length);
            conn.notifyAll();
          }
        }
        continue;
      }
      if (opcode == OP_CONTINUE ? !assembling : assembling) {
        return FlixelNetCloseReason.ERROR;
      }
      if (size + length > MAX_MESSAGE) {
        return FlixelNetCloseReason.ERROR;
      }
      if (size + length > message.length) {
        byte[] bigger = new byte[(int) Math.max(size + length, message.length * 2L)];
        System.arraycopy(message, 0, bigger, 0, size);
        message = bigger;
      }
      readFully(in, message, size, (int) length);
      unmask(message, size, (int) length, mask, masked);
      size += (int) length;
      assembling = !last;
      if (last) {
        deliver(conn, message, size);
        size = 0;
      }
    }
  }

  private void deliver(Connection conn, byte[] message, int size) {
    if (size < 1) {
      return;
    }
    int tag = message[0];
    synchronized (lock) {
      if (conn.closed || conn.generation != generation) {
        return;
      }
      if (tag == TAG_RELIABLE) {
        queue.pushData(conn.id, FlixelNetChannel.RELIABLE, message, 1, size - 1);
      } else if (tag == TAG_UNRELIABLE) {
        queue.pushData(conn.id, FlixelNetChannel.UNRELIABLE, message, 1, size - 1);
      } else if (tag == TAG_SIGNAL) {
        // Signals wait in the same line as game data, so the two keep the order they were sent
        // in. A negative connection number marks an entry as a signal.
        queue.pushData(-conn.id - 1, FlixelNetChannel.RELIABLE, message, 1, size - 1);
      }
    }
  }

  /** Runs on one thread per connection: sends whatever is waiting, as it appears. */
  private void writeLoop(Connection conn, OutputStream out) {
    byte[] chunk = new byte[8192];
    try {
      while (true) {
        int size;
        synchronized (conn) {
          while (conn.pending.getSize() == 0 && !conn.finished) {
            conn.wait();
          }
          size = conn.pending.getSize();
          if (size == 0) {
            break;
          }
          if (size > chunk.length) {
            chunk = new byte[Math.max(size, chunk.length * 2)];
          }
          System.arraycopy(conn.pending.getData(), 0, chunk, 0, size);
          conn.pending.clear();
        }
        out.write(chunk, 0, size);
        out.flush();
      }
    } catch (IOException e) {
      fail(conn, FlixelNetCloseReason.ERROR);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      Socket socket;
      synchronized (conn) {
        socket = conn.socket;
      }
      closeQuietly(socket);
    }
  }

  /** Ends a connection that broke or that the other side closed, and reports it once. */
  private void fail(Connection conn, FlixelNetCloseReason reason) {
    synchronized (lock) {
      if (conn.closed) {
        return;
      }
      forget(conn);
      if (conn.generation == generation && (conn.open || conn.client)) {
        queue.pushClose(conn.id, reason);
      }
    }
    finish(conn);
  }

  /** Marks a connection as over. The caller must hold the lock. */
  private void forget(Connection conn) {
    conn.closed = true;
    if (connections.get(conn.id) == conn) {
      connections.remove(conn.id);
    }
    if (conn.client && conn.generation == generation) {
      state = FlixelNetTransportState.CLOSED;
    }
  }

  /** Says goodbye and lets the sending thread close the socket once everything is out. */
  private void finish(Connection conn) {
    Socket socket;
    boolean sending;
    synchronized (conn) {
      sending = conn.open;
      if (sending && !conn.finished) {
        frame(conn, OP_CLOSE, -1, null, 0, 0);
      }
      conn.finished = true;
      conn.notifyAll();
      socket = conn.socket;
    }
    if (!sending) {
      closeQuietly(socket);
    }
  }

  /**
   * Adds one WebSocket frame to a connection's outgoing bytes. The caller must hold the
   * connection's monitor.
   *
   * @param tag The first byte of the payload, or -1 for none.
   */
  private static void frame(Connection conn, int opcode, int tag, byte[] data, int offset, int length) {
    FlixelNetBuffer out = conn.pending;
    int size = length + (tag >= 0 ? 1 : 0);
    int maskBit = conn.client ? 0x80 : 0;
    out.writeByte(0x80 | opcode);
    if (size < 126) {
      out.writeByte(maskBit | size);
    } else if (size < 65536) {
      out.writeByte(maskBit | 126);
      out.writeByte(size >> 8);
      out.writeByte(size);
    } else {
      out.writeByte(maskBit | 127);
      out.writeInt(0);
      out.writeByte(size >> 24);
      out.writeByte(size >> 16);
      out.writeByte(size >> 8);
      out.writeByte(size);
    }
    if (!conn.client) {
      if (tag >= 0) {
        out.writeByte(tag);
      }
      if (length > 0) {
        out.writeBytes(data, offset, length);
      }
      return;
    }
    // A client must scramble every frame with a fresh four-byte key. It is not secrecy; it
    // stops badly written proxies from mistaking game data for web requests.
    int key = conn.nextMask();
    out.writeInt(key);
    int at = 0;
    if (tag >= 0) {
      out.writeByte(tag ^ key);
      at = 1;
    }
    for (int i = 0; i < length; i++, at++) {
      out.writeByte(data[offset + i] ^ (key >>> ((at & 3) * 8)));
    }
  }

  private static void unmask(byte[] data, int offset, int length, byte[] mask, boolean masked) {
    if (masked) {
      for (int i = 0; i < length; i++) {
        data[offset + i] ^= mask[i & 3];
      }
    }
  }

  /** The host's half of the opening exchange: check the request and agree to switch protocols. */
  private static void acceptHandshake(InputStream in, OutputStream out) throws IOException {
    String request = readHead(in);
    String key = header(request, "sec-websocket-key");
    String upgrade = header(request, "upgrade");
    if (!request.startsWith("GET ") || key == null || upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
      out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
          .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      throw new IOException("Not a WebSocket request.");
    }
    String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
    out.write(response.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  /** The joining side's half of the opening exchange. */
  private static void requestHandshake(InputStream in, OutputStream out, Target target) throws IOException {
    byte[] random = new byte[16];
    long seed = System.nanoTime() ^ target.hashCode();
    for (int i = 0; i < random.length; i++) {
      seed = seed * 6364136223846793005L + 1442695040888963407L;
      random[i] = (byte) (seed >>> 56);
    }
    String key = Base64.getEncoder().encodeToString(random);
    String request = "GET " + target.path + " HTTP/1.1\r\nHost: " + target.host + ":" + target.port
        + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
        + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    String response = readHead(in);
    String accept = header(response, "sec-websocket-accept");
    if (!response.startsWith("HTTP/1.1 101") || accept == null || !accept.equals(acceptKey(key))) {
      throw new IOException("The host did not agree to a WebSocket.");
    }
  }

  /** Reads the text at the start of a web request or answer, up to its empty line. */
  private static String readHead(InputStream in) throws IOException {
    byte[] head = new byte[MAX_HANDSHAKE];
    int size = 0;
    while (size < head.length) {
      head[size++] = (byte) readByte(in);
      if (size >= 4 && head[size - 4] == '\r' && head[size - 3] == '\n' && head[size - 2] == '\r'
          && head[size - 1] == '\n') {
        return new String(head, 0, size, StandardCharsets.ISO_8859_1);
      }
    }
    throw new IOException("The opening request is too long.");
  }

  /** Finds one header line, whatever the capitalization of its name, and returns its value. */
  private static String header(String head, String lowerName) {
    int at = 0;
    while (at < head.length()) {
      int end = head.indexOf("\r\n", at);
      if (end < 0) {
        break;
      }
      int colon = head.indexOf(':', at);
      if (colon > at && colon < end && head.substring(at, colon).trim().equalsIgnoreCase(lowerName)) {
        return head.substring(colon + 1, end).trim();
      }
      at = end + 2;
    }
    return null;
  }

  private static String acceptKey(String key) throws IOException {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      return Base64.getEncoder().encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-1 is not available.", e);
    }
  }

  private static int readByte(InputStream in) throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new EOFException();
    }
    return value;
  }

  private static void readFully(InputStream in, byte[] into, int offset, int length) throws IOException {
    int done = 0;
    while (done < length) {
      int read = in.read(into, offset + done, length - done);
      if (read < 0) {
        throw new EOFException();
      }
      done += read;
    }
  }

  private static void closeQuietly(Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        // Nothing useful can be done about a socket that fails to close.
      }
    }
  }

  private void requireClosed() {
    if (state != FlixelNetTransportState.CLOSED) {
      throw new IllegalStateException("The transport is already " + state + "; close() it first.");
    }
  }

  /** Stands between the queue and the listener, to pick out signals and announce changes. */
  private final class Relay implements FlixelNetTransportListener {

    FlixelNetTransportListener target;

    @Override
    public void onOpen(int connection) {
      onConnectionOpened(connection);
      target.onOpen(connection);
    }

    @Override
    public void onClose(int connection, FlixelNetCloseReason reason) {
      onConnectionClosed(connection);
      target.onClose(connection, reason);
    }

    @Override
    public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
      if (connection < 0) {
        onSignal(-connection - 1, data, offset, length);
      } else {
        target.onData(connection, channel, data, offset, length);
      }
    }
  }

  /** Where a client wants to connect, read from an address such as {@code ws://host:port/path}. */
  private static final class Target {

    final String host;
    final String path;
    final int port;
    final boolean secure;

    Target(String address) {
      String rest = address;
      boolean tls = false;
      if (rest.startsWith("wss://")) {
        tls = true;
        rest = rest.substring(6);
      } else if (rest.startsWith("ws://")) {
        rest = rest.substring(5);
      }
      int slash = rest.indexOf('/');
      path = slash < 0 ? "/" : rest.substring(slash);
      String authority = slash < 0 ? rest : rest.substring(0, slash);
      int colon = authority.lastIndexOf(':');
      boolean hasPort = colon >= 0 && authority.indexOf(']') < colon;
      String name = hasPort ? authority.substring(0, colon) : authority;
      if (name.startsWith("[") && name.endsWith("]")) {
        name = name.substring(1, name.length() - 1);
      }
      int number = tls ? 443 : 80;
      if (hasPort) {
        try {
          number = Integer.parseInt(authority.substring(colon + 1));
        } catch (NumberFormatException e) {
          number = -1;
        }
      }
      if (name.isEmpty() || number < 1 || number > 65535) {
        throw new IllegalArgumentException("Address must look like ws://host:port, got '" + address + "'.");
      }
      host = name;
      port = number;
      secure = tls;
    }
  }

  /** One WebSocket connection and the bytes waiting to be sent on it. */
  private static final class Connection {

    /** Frames waiting to be sent. Guarded by this connection's monitor. */
    final FlixelNetBuffer pending = new FlixelNetBuffer(1024);
    Socket socket;
    final int id;
    final int generation;
    private int maskState;
    final boolean client;
    volatile boolean open;
    volatile boolean closed;
    boolean finished;

    Connection(int id, int generation, boolean client) {
      this.id = id;
      this.generation = generation;
      this.client = client;
      this.maskState = (int) System.nanoTime() | 1;
    }

    int nextMask() {
      maskState ^= maskState << 13;
      maskState ^= maskState >>> 17;
      maskState ^= maskState << 5;
      return maskState;
    }
  }
}
