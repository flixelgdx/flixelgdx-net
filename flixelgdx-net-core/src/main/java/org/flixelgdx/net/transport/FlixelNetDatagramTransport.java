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
import org.flixelgdx.collections.FlixelIntMap;
import org.flixelgdx.collections.FlixelMap;
import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetException;
import org.jetbrains.annotations.NotNull;

/**
 * A transport that turns loose, unreliable packets (such as UDP) into proper connections with
 * both a reliable and an unreliable channel.
 *
 * <p>The raw network only offers "throw a small packet and hope". Think of sending numbered
 * postcards through a careless post office: some get lost, some arrive twice, some arrive in the
 * wrong order. This class does what a careful pen pal would do:
 *
 * <ul>
 *   <li><b>Joining.</b> Before anything else, the two sides shake hands, and each proves it can
 *       really receive at the address it claims to have. After that, every packet carries a
 *       secret number only the two of them know, so strangers cannot butt in.</li>
 *   <li><b>The reliable channel.</b> Every message gets a number. The receiver keeps telling the
 *       sender which numbers it has, the sender sends the missing ones again, and the receiver
 *       hands them over strictly in order. A message too big for one packet is cut into pieces
 *       and put back together.</li>
 *   <li><b>The unreliable channel.</b> Messages are sent once and never again. One that arrives
 *       after a newer one has already been delivered is thrown away, so the game never steps
 *       backward in time.</li>
 *   <li><b>Noticing silence.</b> If nothing is heard from the other side for
 *       {@link #getTimeout()} seconds, the connection is closed.</li>
 * </ul>
 *
 * <p>All of this runs on the game thread inside {@link #poll(FlixelNetTransportListener)}, so
 * poll it every frame; a transport that is not polled cannot resend or answer, and the other side
 * will eventually give up on it.
 *
 * <p>Games normally get one of these ready-made from a platform module instead of building it by
 * hand.
 *
 * @param <A> The address type of the socket this transport runs on.
 */
public final class FlixelNetDatagramTransport<A> implements FlixelNetTransport {

  /** The largest packet, in bytes, this transport ever hands to its socket. */
  public static final int MAX_DATAGRAM = 1200;

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#UNRELIABLE}. */
  public static final int MAX_UNRELIABLE_SIZE = MAX_DATAGRAM - 20;

  /** The largest packet, in bytes, that can be sent on {@link FlixelNetChannel#RELIABLE}. */
  public static final int MAX_RELIABLE_SIZE = 1 << 17;

  /** How many seconds of silence close a connection, unless changed. */
  public static final float DEFAULT_TIMEOUT = 10f;

  private static final int MAGIC = 0x46585544;
  private static final int CONNECT = 1;
  private static final int CHALLENGE = 2;
  private static final int RESPONSE = 3;
  private static final int ACCEPT = 4;
  private static final int DENIED = 5;
  private static final int DATA = 6;
  private static final int DISCONNECT = 7;

  /** A join request is padded to this size so the answer is never larger than the question. */
  private static final int CONNECT_SIZE = 40;
  private static final int HEADER = 15;
  private static final int CHUNK_HEADER = 5;
  private static final int SEGMENT_SIZE = MAX_DATAGRAM - HEADER - CHUNK_HEADER;
  private static final int WINDOW = 256;
  private static final int WINDOW_MASK = WINDOW - 1;
  private static final int CHUNK_UNRELIABLE = 1;
  private static final int CHUNK_RELIABLE = 2;
  private static final int CHUNK_MORE = 0x80;
  private static final int MAX_BACKLOG = 1 << 22;
  private static final int STAGE_CONNECTING = 0;
  private static final int STAGE_RESPONDING = 1;
  private static final int STAGE_OPEN = 2;
  private static final double RETRY_INTERVAL = 0.25;
  private static final double KEEPALIVE_INTERVAL = 1.0;
  private static final double MIN_RTO = 0.05;
  private static final double MAX_RTO = 1.0;

  private final long secret;
  private long saltCounter;

  private final FlixelNetDatagramSocket<A> socket;
  private final Clock clock;
  private final FlixelNetEventQueue queue = new FlixelNetEventQueue();
  private final FlixelArray<Connection<A>> connections = new FlixelArray<>();
  private final FlixelMap<A, Connection<A>> byAddress = new FlixelMap<>();
  private final FlixelIntMap<Connection<A>> byId = new FlixelIntMap<>();
  private final FlixelNetBuffer out = new FlixelNetBuffer(MAX_DATAGRAM);
  private final FlixelNetBuffer in = new FlixelNetBuffer();
  private final byte[] received = new byte[MAX_DATAGRAM];
  private FlixelNetTransportState state = FlixelNetTransportState.CLOSED;
  private int nextId = 1;
  private int maxConnections = 64;
  private float timeout = DEFAULT_TIMEOUT;
  private float connectTimeout = 5f;

  /**
   * Creates a transport on a socket, using the system clock.
   *
   * @param socket The socket to send and receive through. The transport opens and closes it.
   * @param secret A number that is hard to guess, such as one from a secure random source. It
   *     protects the joining handshake against forged packets.
   */
  public FlixelNetDatagramTransport(@NotNull FlixelNetDatagramSocket<A> socket, long secret) {
    this(socket, secret, () -> System.nanoTime() / 1.0e9);
  }

  /**
   * Creates a transport on a socket with its own clock, which lets tests control time.
   *
   * @param socket The socket to send and receive through. The transport opens and closes it.
   * @param secret A number that is hard to guess. It protects the joining handshake against
   *     forged packets.
   * @param clock Where the transport reads the current time from.
   */
  public FlixelNetDatagramTransport(@NotNull FlixelNetDatagramSocket<A> socket, long secret, @NotNull Clock clock) {
    this.socket = socket;
    this.secret = secret;
    this.clock = clock;
  }

  @Override
  public void listen(int port) {
    requireClosed();
    socket.open(port);
    queue.clear();
    state = FlixelNetTransportState.LISTENING;
  }

  @Override
  public void connect(@NotNull String address) {
    requireClosed();
    A target = socket.resolve(address);
    socket.open(0);
    queue.clear();
    state = FlixelNetTransportState.CONNECTING;
    double now = clock.now();
    Connection<A> conn = new Connection<>(target, HOST, now);
    conn.salt = mix(secret + (++saltCounter) * 0x9E3779B97F4A7C15L ^ Double.doubleToLongBits(now));
    add(conn);
    sendConnect(conn);
    conn.nextRetry = now + RETRY_INTERVAL;
  }

  @Override
  public void poll(@NotNull FlixelNetTransportListener listener) {
    if (state != FlixelNetTransportState.CLOSED) {
      double now = clock.now();
      int length;
      while (state != FlixelNetTransportState.CLOSED && (length = socket.receive(received)) >= 0) {
        try {
          handle(socket.getSender(), length, now);
        } catch (FlixelNetException e) {
          // A packet that makes no sense is dropped. Anyone can send anything to an open port,
          // so this must never be allowed to do more than waste a little time.
        }
      }
      for (int i = connections.getSize() - 1; i >= 0; i--) {
        service(connections.get(i), now);
      }
    }
    queue.drain(listener);
  }

  @Override
  public boolean send(int connection, @NotNull FlixelNetChannel channel, byte @NotNull [] data, int offset,
      int length) {
    if (length > getMaxPacketSize(channel)) {
      throw new IllegalArgumentException("Packet of " + length + " bytes is larger than the "
          + getMaxPacketSize(channel) + " bytes allowed on the " + channel + " channel.");
    }
    if (offset < 0 || length < 0 || offset > data.length - length) {
      throw new IndexOutOfBoundsException(
          "Range of " + length + " bytes at " + offset + " does not fit in an array of " + data.length + ".");
    }
    Connection<A> conn = byId.get(connection);
    if (conn == null || conn.stage != STAGE_OPEN) {
      return false;
    }
    if (channel == FlixelNetChannel.UNRELIABLE) {
      begin(conn);
      out.writeByte(CHUNK_UNRELIABLE);
      out.writeShort(conn.sendUnreliable++);
      out.writeShort(length);
      out.writeBytes(data, offset, length);
      flushDatagram(conn, clock.now());
      return true;
    }
    if (!queueReliable(conn, data, offset, length)) {
      // The other side has stopped taking in what is sent; holding ever more for it would only
      // eat memory.
      remove(conn, FlixelNetCloseReason.ERROR, true);
      return false;
    }
    transmit(conn, clock.now());
    return true;
  }

  @Override
  public void disconnect(int connection) {
    Connection<A> conn = byId.get(connection);
    if (conn != null) {
      remove(conn, FlixelNetCloseReason.LOCAL, true);
    }
  }

  @Override
  public void close() {
    if (state == FlixelNetTransportState.CLOSED) {
      return;
    }
    for (int i = 0, n = connections.getSize(); i < n; i++) {
      sendDisconnect(connections.get(i));
    }
    shutDown();
    queue.clear();
  }

  @Override
  public int getMaxPacketSize(@NotNull FlixelNetChannel channel) {
    return channel == FlixelNetChannel.UNRELIABLE ? MAX_UNRELIABLE_SIZE : MAX_RELIABLE_SIZE;
  }

  @Override
  public @NotNull String getRoute(int connection) {
    return "UDP";
  }

  @Override
  public @NotNull FlixelNetTransportState getState() {
    return state;
  }

  /**
   * Returns how many connections are open or being opened right now.
   *
   * @return The number of connections.
   */
  public int getConnectionCount() {
    return connections.getSize();
  }

  /**
   * Returns the socket this transport runs on.
   *
   * @return The socket.
   */
  @NotNull
  public FlixelNetDatagramSocket<A> getSocket() {
    return socket;
  }

  public float getTimeout() {
    return timeout;
  }

  /**
   * Sets how many seconds of silence close a connection.
   *
   * @param seconds The limit in seconds. Must be greater than zero.
   * @throws IllegalArgumentException If {@code seconds} is not greater than zero.
   */
  public void setTimeout(float seconds) {
    if (!(seconds > 0f)) {
      throw new IllegalArgumentException("Timeout must be greater than zero, got " + seconds + ".");
    }
    timeout = seconds;
  }

  public float getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Sets how many seconds a client keeps trying to reach a host before giving up.
   *
   * @param seconds The limit in seconds. Must be greater than zero.
   * @throws IllegalArgumentException If {@code seconds} is not greater than zero.
   */
  public void setConnectTimeout(float seconds) {
    if (!(seconds > 0f)) {
      throw new IllegalArgumentException("Connect timeout must be greater than zero, got " + seconds + ".");
    }
    connectTimeout = seconds;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  /**
   * Sets how many clients a host accepts at once. Anyone past that is refused.
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

  /** Reads one packet that arrived and acts on it. */
  private void handle(A sender, int length, double now) {
    in.wrap(received, 0, length);
    int type = in.readUByte();
    Connection<A> conn = byAddress.get(sender);
    switch (type) {
      case CONNECT -> {
        if (state == FlixelNetTransportState.LISTENING && length >= CONNECT_SIZE && in.readInt() == MAGIC) {
          long salt = in.readLong();
          out.clear();
          out.writeByte(CHALLENGE);
          out.writeLong(salt);
          out.writeLong(cookie(sender, salt));
          socket.send(sender, out.getData(), 0, out.getSize());
        }
      }
      case CHALLENGE -> {
        if (conn != null && conn.stage == STAGE_CONNECTING && in.readLong() == conn.salt) {
          conn.cookie = in.readLong();
          conn.token = token(conn.salt, conn.cookie);
          conn.stage = STAGE_RESPONDING;
          sendResponse(conn);
          conn.nextRetry = now + RETRY_INTERVAL;
        }
      }
      case RESPONSE -> {
        if (state != FlixelNetTransportState.LISTENING) {
          return;
        }
        long salt = in.readLong();
        long cookie = in.readLong();
        if (cookie != cookie(sender, salt)) {
          return;
        }
        long token = token(salt, cookie);
        if (conn == null) {
          if (connections.getSize() >= maxConnections) {
            out.clear();
            out.writeByte(DENIED);
            out.writeLong(salt);
            socket.send(sender, out.getData(), 0, out.getSize());
            return;
          }
          conn = new Connection<>(sender, nextId++, now);
          conn.token = token;
          conn.stage = STAGE_OPEN;
          add(conn);
          queue.pushOpen(conn.id);
        }
        if (conn.token == token) {
          // Also reached when the first answer was lost and the client asks again.
          out.clear();
          out.writeByte(ACCEPT);
          out.writeLong(token);
          socket.send(sender, out.getData(), 0, out.getSize());
        }
      }
      case ACCEPT -> {
        if (conn != null && conn.stage == STAGE_RESPONDING && in.readLong() == conn.token) {
          conn.stage = STAGE_OPEN;
          conn.lastHeard = now;
          state = FlixelNetTransportState.CONNECTED;
          queue.pushOpen(HOST);
        }
      }
      case DENIED -> {
        if (conn != null && conn.stage != STAGE_OPEN && in.readLong() == conn.salt) {
          remove(conn, FlixelNetCloseReason.REFUSED, false);
        }
      }
      case DATA -> {
        if (conn != null && conn.stage == STAGE_OPEN && in.readLong() == conn.token) {
          conn.lastHeard = now;
          data(conn, now);
        }
      }
      case DISCONNECT -> {
        if (conn != null && conn.stage == STAGE_OPEN && in.readLong() == conn.token) {
          remove(conn, FlixelNetCloseReason.REMOTE, false);
        }
      }
      default -> {
        // Not one of ours.
      }
    }
  }

  /** Reads the rest of a data packet: what the other side has received, then its messages. */
  private void data(Connection<A> conn, double now) {
    int ack = in.readUShort();
    int ackBits = in.readInt();
    acknowledge(conn, ack, ackBits, now);
    while (in.remaining() > 0) {
      int kind = in.readUByte();
      int number = in.readUShort();
      int length = in.readUShort();
      if (length > in.remaining()) {
        throw new FlixelNetException("A message is longer than the packet it came in.");
      }
      int at = in.getOffset() + in.getPosition();
      in.skip(length);
      if ((kind & 0x7F) == CHUNK_UNRELIABLE) {
        // Anything that is not newer than the last message delivered is late or a copy.
        if (!conn.heardUnreliable || (short) (number - conn.lastUnreliable) > 0) {
          conn.heardUnreliable = true;
          conn.lastUnreliable = number;
          queue.pushData(conn.id, FlixelNetChannel.UNRELIABLE, received, at, length);
        }
      } else if ((kind & 0x7F) == CHUNK_RELIABLE) {
        if (length > SEGMENT_SIZE) {
          throw new FlixelNetException("A reliable piece is larger than allowed.");
        }
        conn.ackPending = true;
        int ahead = (short) (number - conn.receiveNext);
        if (ahead >= 0 && ahead < WINDOW) {
          int slot = number & WINDOW_MASK;
          if (!conn.receiveHas[slot]) {
            if (conn.receiveData[slot] == null) {
              conn.receiveData[slot] = new byte[SEGMENT_SIZE];
            }
            System.arraycopy(received, at, conn.receiveData[slot], 0, length);
            conn.receiveLength[slot] = length;
            conn.receiveMore[slot] = (kind & CHUNK_MORE) != 0;
            conn.receiveHas[slot] = true;
          }
          if (!deliverInOrder(conn)) {
            return;
          }
        }
      } else {
        throw new FlixelNetException("Unknown kind of message piece.");
      }
    }
  }

  /**
   * Hands over every reliable piece that is next in line, joining pieces of a split message.
   *
   * @return Whether the connection is still open afterward.
   */
  private boolean deliverInOrder(Connection<A> conn) {
    while (conn.receiveHas[conn.receiveNext & WINDOW_MASK]) {
      int slot = conn.receiveNext & WINDOW_MASK;
      conn.receiveHas[slot] = false;
      conn.receiveNext = (conn.receiveNext + 1) & 0xFFFF;
      boolean more = conn.receiveMore[slot];
      if (!more && conn.assembling.getSize() == 0) {
        queue.pushData(conn.id, FlixelNetChannel.RELIABLE, conn.receiveData[slot], 0, conn.receiveLength[slot]);
        continue;
      }
      if (conn.assembling.getSize() + conn.receiveLength[slot] > MAX_RELIABLE_SIZE) {
        // Nobody honest sends a message this large; refuse to keep growing.
        remove(conn, FlixelNetCloseReason.ERROR, true);
        return false;
      }
      conn.assembling.writeBytes(conn.receiveData[slot], 0, conn.receiveLength[slot]);
      if (!more) {
        queue.pushData(conn.id, FlixelNetChannel.RELIABLE, conn.assembling.getData(), 0, conn.assembling.getSize());
        conn.assembling.clear();
      }
    }
    return true;
  }

  /** Takes note of which reliable pieces the other side says it has. */
  private void acknowledge(Connection<A> conn, int ack, int ackBits, double now) {
    int outstanding = (conn.sendNext - conn.sendBase) & 0xFFFF;
    int ahead = (short) (ack - conn.sendBase);
    if (ahead < 0 || ahead > outstanding) {
      // An old packet that arrived late, or nonsense. Either way it has nothing new to say.
      return;
    }
    for (int i = 0; i < ahead; i++) {
      acknowledge(conn, conn.window[conn.sendBase & WINDOW_MASK], now);
      conn.sendBase = (conn.sendBase + 1) & 0xFFFF;
    }
    outstanding -= ahead;
    for (int i = 0; i < 32 && i + 1 < outstanding; i++) {
      if ((ackBits & (1 << i)) != 0) {
        acknowledge(conn, conn.window[(ack + 1 + i) & WINDOW_MASK], now);
      }
    }
  }

  private void acknowledge(Connection<A> conn, Segment segment, double now) {
    if (segment.acked) {
      return;
    }
    segment.acked = true;
    if (segment.sends == 1) {
      // Only a piece sent exactly once gives an honest measurement; otherwise there is no
      // telling which copy this answers.
      double sample = now - segment.sentAt;
      conn.rtt = conn.rtt == 0.0 ? sample : conn.rtt * 0.875 + sample * 0.125;
      conn.rto = Math.max(MIN_RTO, Math.min(MAX_RTO, conn.rtt * 2.0 + 0.01));
    }
  }

  /** Does the regular upkeep of one connection: retries, resends, keeping in touch, giving up. */
  private void service(Connection<A> conn, double now) {
    if (conn.stage != STAGE_OPEN) {
      if (now - conn.created > connectTimeout) {
        remove(conn, FlixelNetCloseReason.REFUSED, false);
      } else if (now >= conn.nextRetry) {
        if (conn.stage == STAGE_CONNECTING) {
          sendConnect(conn);
        } else {
          sendResponse(conn);
        }
        conn.nextRetry = now + RETRY_INTERVAL;
      }
      return;
    }
    if (now - conn.lastHeard > timeout) {
      remove(conn, FlixelNetCloseReason.TIMEOUT, true);
      return;
    }
    refill(conn);
    transmit(conn, now);
    if (conn.ackPending || now - conn.lastSent > KEEPALIVE_INTERVAL) {
      begin(conn);
      flushDatagram(conn, now);
    }
  }

  /**
   * Accepts a reliable message for sending, cutting it into pieces if needed.
   *
   * @return {@code false} if far too much is already waiting to be sent.
   */
  private boolean queueReliable(Connection<A> conn, byte[] data, int offset, int length) {
    int outstanding = (conn.sendNext - conn.sendBase) & 0xFFFF;
    if (conn.backlogRead == conn.backlog.getSize() && pieces(length) <= WINDOW - outstanding) {
      enqueue(conn, data, offset, length);
      return true;
    }
    if (conn.backlogRead > 0) {
      // Slide what is still waiting to the front, so the space already used up is reclaimed.
      byte[] waiting = conn.backlog.getData();
      int remaining = conn.backlog.getSize() - conn.backlogRead;
      int from = conn.backlogRead;
      conn.backlog.clear();
      conn.backlog.writeBytes(waiting, from, remaining);
      conn.backlogRead = 0;
    }
    if (conn.backlog.getSize() + length > MAX_BACKLOG) {
      return false;
    }
    conn.backlog.writeVarUInt(length);
    conn.backlog.writeBytes(data, offset, length);
    return true;
  }

  /** Moves waiting messages into the send window as room appears. */
  private void refill(Connection<A> conn) {
    FlixelNetBuffer backlog = conn.backlog;
    while (conn.backlogRead < backlog.getSize()) {
      backlog.setPosition(conn.backlogRead);
      int length = backlog.readVarUInt();
      int outstanding = (conn.sendNext - conn.sendBase) & 0xFFFF;
      if (pieces(length) > WINDOW - outstanding) {
        return;
      }
      enqueue(conn, backlog.getData(), backlog.getPosition(), length);
      conn.backlogRead = backlog.getPosition() + length;
    }
    backlog.clear();
    conn.backlogRead = 0;
  }

  private static int pieces(int length) {
    return length == 0 ? 1 : (length + SEGMENT_SIZE - 1) / SEGMENT_SIZE;
  }

  private void enqueue(Connection<A> conn, byte[] data, int offset, int length) {
    int count = pieces(length);
    for (int i = 0; i < count; i++) {
      int slot = conn.sendNext & WINDOW_MASK;
      Segment segment = conn.window[slot];
      if (segment == null) {
        segment = new Segment();
        conn.window[slot] = segment;
      }
      int size = Math.min(SEGMENT_SIZE, length - i * SEGMENT_SIZE);
      System.arraycopy(data, offset + i * SEGMENT_SIZE, segment.data, 0, size);
      segment.length = size;
      segment.more = i + 1 < count;
      segment.acked = false;
      segment.sends = 0;
      conn.sendNext = (conn.sendNext + 1) & 0xFFFF;
    }
  }

  /** Sends every reliable piece that is new or has waited too long for an answer. */
  private void transmit(Connection<A> conn, double now) {
    boolean open = false;
    for (int id = conn.sendBase; id != conn.sendNext; id = (id + 1) & 0xFFFF) {
      Segment segment = conn.window[id & WINDOW_MASK];
      if (segment.acked) {
        continue;
      }
      // Each resend waits a little longer than the last, so a struggling connection is not
      // drowned in copies.
      if (segment.sends > 0 && now - segment.sentAt < conn.rto * Math.min(segment.sends, 4)) {
        continue;
      }
      if (open && out.getSize() + CHUNK_HEADER + segment.length > MAX_DATAGRAM) {
        flushDatagram(conn, now);
        open = false;
      }
      if (!open) {
        begin(conn);
        open = true;
      }
      out.writeByte(CHUNK_RELIABLE | (segment.more ? CHUNK_MORE : 0));
      out.writeShort(id);
      out.writeShort(segment.length);
      out.writeBytes(segment.data, 0, segment.length);
      segment.sentAt = now;
      segment.sends++;
    }
    if (open) {
      flushDatagram(conn, now);
    }
  }

  /** Starts a data packet with the secret number and the list of what has been received. */
  private void begin(Connection<A> conn) {
    out.clear();
    out.writeByte(DATA);
    out.writeLong(conn.token);
    out.writeShort(conn.receiveNext);
    int bits = 0;
    for (int i = 0; i < 32; i++) {
      if (conn.receiveHas[(conn.receiveNext + 1 + i) & WINDOW_MASK]) {
        bits |= 1 << i;
      }
    }
    out.writeInt(bits);
  }

  private void flushDatagram(Connection<A> conn, double now) {
    socket.send(conn.address, out.getData(), 0, out.getSize());
    conn.lastSent = now;
    conn.ackPending = false;
  }

  private void sendConnect(Connection<A> conn) {
    out.clear();
    out.writeByte(CONNECT);
    out.writeInt(MAGIC);
    out.writeLong(conn.salt);
    while (out.getSize() < CONNECT_SIZE) {
      out.writeByte(0);
    }
    socket.send(conn.address, out.getData(), 0, out.getSize());
  }

  private void sendResponse(Connection<A> conn) {
    out.clear();
    out.writeByte(RESPONSE);
    out.writeLong(conn.salt);
    out.writeLong(conn.cookie);
    socket.send(conn.address, out.getData(), 0, out.getSize());
  }

  private void sendDisconnect(Connection<A> conn) {
    if (conn.stage != STAGE_OPEN) {
      return;
    }
    out.clear();
    out.writeByte(DISCONNECT);
    out.writeLong(conn.token);
    // Sent twice because this is the one packet that can never be asked for again.
    socket.send(conn.address, out.getData(), 0, out.getSize());
    socket.send(conn.address, out.getData(), 0, out.getSize());
  }

  private void add(Connection<A> conn) {
    connections.add(conn);
    byAddress.put(conn.address, conn);
    byId.put(conn.id, conn);
  }

  private void remove(Connection<A> conn, FlixelNetCloseReason reason, boolean tell) {
    if (tell) {
      sendDisconnect(conn);
    }
    connections.removeValue(conn, true);
    byAddress.remove(conn.address);
    byId.remove(conn.id);
    queue.pushClose(conn.id, reason);
    if (state != FlixelNetTransportState.LISTENING) {
      shutDown();
    }
  }

  private void shutDown() {
    connections.clear();
    byAddress.clear();
    byId.clear();
    socket.close();
    state = FlixelNetTransportState.CLOSED;
  }

  private void requireClosed() {
    if (state != FlixelNetTransportState.CLOSED) {
      throw new IllegalStateException("The transport is already " + state + "; close() it first.");
    }
  }

  /**
   * Returns the number a host hands to someone asking to join. Only someone who really receives
   * at that address can send it back, and the host does not have to remember it.
   */
  private long cookie(A sender, long salt) {
    return mix(secret ^ mix(sender.hashCode() * 0x9E3779B97F4A7C15L + salt));
  }

  private static long token(long salt, long cookie) {
    return mix(salt + cookie * 0xC2B2AE3D27D4EB4FL);
  }

  /** Scrambles a number thoroughly, so related inputs give unrelated outputs. */
  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
    return value ^ (value >>> 31);
  }

  /** Tells a transport what time it is. */
  public interface Clock {

    /**
     * Returns the current time.
     *
     * @return A number of seconds that only ever grows.
     */
    double now();
  }

  /** One reliable piece waiting to be confirmed by the other side. */
  private static final class Segment {
    double sentAt;
    final byte[] data = new byte[SEGMENT_SIZE];
    int length;
    int sends;
    boolean more;
    boolean acked;
  }

  /** Everything the transport remembers about one remote end. */
  private static final class Connection<A> {
    long salt;
    long cookie;
    long token;
    final double created;
    double nextRetry;
    double lastHeard;
    double lastSent;
    double rtt;
    double rto = 0.2;

    final A address;
    final Segment[] window = new Segment[WINDOW];
    final byte[][] receiveData = new byte[WINDOW][];
    final int[] receiveLength = new int[WINDOW];
    final boolean[] receiveHas = new boolean[WINDOW];
    final boolean[] receiveMore = new boolean[WINDOW];
    final FlixelNetBuffer backlog = new FlixelNetBuffer(0);
    final FlixelNetBuffer assembling = new FlixelNetBuffer(0);
    final int id;
    int stage = STAGE_CONNECTING;
    int backlogRead;

    /** The number of the oldest reliable piece not yet confirmed, and of the next one to make. */
    int sendBase;
    int sendNext;
    int sendUnreliable;

    /** The number of the next reliable piece to hand over, and of the last unreliable message. */
    int receiveNext;
    int lastUnreliable;

    boolean heardUnreliable;
    boolean ackPending;

    Connection(A address, int id, double now) {
      this.address = address;
      this.id = id;
      this.created = now;
      this.lastHeard = now;
      this.lastSent = now;
    }
  }
}
