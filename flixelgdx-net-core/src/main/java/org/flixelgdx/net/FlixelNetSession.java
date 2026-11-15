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

import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.collections.FlixelIntMap;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetCloseReason;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportListener;
import org.flixelgdx.signal.FlixelSignal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A multiplayer session: one host, the clients that have joined it, and typed messages flowing
 * between them.
 *
 * <p>Think of a session as a phone call with an operator. The host is the operator and every
 * client has a line to it. Clients never talk to each other directly; anything one client wants
 * the others to know goes to the host, which decides what to pass on. That one rule is what makes
 * cheating hard: the host is the only one whose word counts.
 *
 * <p>The session takes care of the parts every game needs and nobody wants to write: checking
 * that both sides run compatible builds, noticing when someone's connection dies, measuring ping,
 * packing many small messages into few packets, and turning bytes back into message objects.
 *
 * <h2>Hosting</h2>
 *
 * <pre>{@code
 * FlixelNetSession session = new FlixelNetSession(protocol);
 * session.onConnect.add(peer -> Flixel.info("Player {} joined", peer.getId()));
 * session.onDisconnect.add(peer -> Flixel.info("Player {} left", peer.getId()));
 * session.on(MoveInput.class, (peer, input) -> movePlayer(peer, input));
 * session.listen(transport, 7777);
 * }</pre>
 *
 * <h2>Joining</h2>
 *
 * <pre>{@code
 * FlixelNetSession session = new FlixelNetSession(protocol);
 * session.onConnect.add(host -> Flixel.info("Joined as player {}", session.getLocalId()));
 * session.onJoinFailed.add(reason -> Flixel.info("Could not join: {}", reason));
 * session.on(WorldState.class, (host, state) -> showWorld(state));
 * session.join(transport, "192.168.1.20:7777");
 * }</pre>
 *
 * <h2>Every frame, and sending</h2>
 *
 * <pre>{@code
 * // Once per frame, on both sides:
 * session.update(elapsed);
 *
 * // Whenever there is something to say:
 * MoveInput input = session.obtain(MoveInput.class);
 * input.dx = 1f;
 * session.send(input, FlixelNetChannel.UNRELIABLE);
 * }</pre>
 *
 * <p>On a client, {@link #send(Object, FlixelNetChannel)} goes to the host. On a host it goes to
 * every client; use {@link #sendTo(FlixelNetPeer, Object, FlixelNetChannel)} for one of them.
 * Messages are not sent the instant these are called: they are collected and go out together at
 * the end of {@link #update(float)}, which keeps the number of packets low.
 *
 * <p>Every signal and handler runs on the thread that calls {@link #update(float)}. A session
 * must only be used from that thread.
 *
 * @see FlixelNetProtocol
 * @see NetMessage
 */
public class FlixelNetSession {

  /** The id of the host, as returned by {@link FlixelNetPeer#getId()} and {@link #getLocalId()}. */
  public static final int HOST_ID = 0;

  /** How many seconds of silence make a peer count as gone, unless changed. */
  public static final float DEFAULT_TIMEOUT = 10f;

  private static final int MAGIC = 0x464E4554;
  private static final int WIRE_VERSION = 1;
  private static final int HELLO = 0;
  private static final int WELCOME = 1;
  private static final int REJECT = 2;
  private static final int PING = 3;
  private static final int PONG = 4;
  private static final int BYE = 5;
  private static final int FIRST_USER = 8;
  private static final int MAX_BAD_PACKETS = 8;
  private static final FlixelNetDisconnectReason[] REASONS = FlixelNetDisconnectReason.values();

  /**
   * Dispatched when a peer becomes part of the session. On a host this is a client that joined.
   * On a client it is the host, and it means joining succeeded.
   */
  public final FlixelSignal<FlixelNetPeer> onConnect = new FlixelSignal<>();

  /**
   * Dispatched when a peer that was connected is gone. {@link FlixelNetPeer#getDisconnectReason()}
   * says why. On a client this means the session is over.
   */
  public final FlixelSignal<FlixelNetPeer> onDisconnect = new FlixelSignal<>();

  /** Dispatched on a client when {@link #join(FlixelNetTransport, String)} did not work out. */
  public final FlixelSignal<FlixelNetDisconnectReason> onJoinFailed = new FlixelSignal<>();

  private double clock;

  /** The clock when joining began, so a host that never answers is given up on. */
  private double joinStarted;

  private final FlixelNetProtocol protocol;
  private final FlixelArray<Link> links = new FlixelArray<>();
  private final FlixelArray<FlixelNetPeer> peers = new FlixelArray<>();
  private final FlixelArray<FlixelNetPeer> pending = new FlixelArray<>();
  private final FlixelArray<FlixelArray<FlixelNetHandler<?>>> handlers = new FlixelArray<>();
  private final FlixelNetBuffer scratch = new FlixelNetBuffer();
  private final FlixelNetBuffer in = new FlixelNetBuffer();
  private FlixelNetSessionState state = FlixelNetSessionState.IDLE;
  private FlixelNetPeer hostPeer;
  private int localId = -1;
  private int nextPeerId = 1;
  private int maxPeers = Integer.MAX_VALUE;

  /** Raised every time the session ends, so loops that were running stop touching stale state. */
  private int epoch;
  private float timeout = DEFAULT_TIMEOUT;
  private float pingInterval = 1f;
  private float pingTimer;
  private float sendInterval;
  private float sendTimer;

  /**
   * Creates a session that speaks the given protocol.
   *
   * <p>The protocol is locked from here on: no more messages can be added to it.
   *
   * @param protocol Every kind of message this session can send and receive.
   */
  public FlixelNetSession(@NotNull FlixelNetProtocol protocol) {
    this.protocol = protocol;
    protocol.freeze();
    for (int i = 0, n = protocol.getCount(); i < n; i++) {
      handlers.add(new FlixelArray<>());
    }
  }

  /**
   * Starts hosting on a transport, or adds one more transport to a session that is already
   * hosting.
   *
   * <p>A host can listen on several transports at once, for example one that browsers can reach
   * and a faster one for desktop players. Everyone ends up in the same session.
   *
   * @param transport The transport to accept clients on. It must be closed.
   * @param port The port to listen on.
   * @throws IllegalStateException If the session is joining or joined to another host.
   */
  public void listen(@NotNull FlixelNetTransport transport, int port) {
    if (state != FlixelNetSessionState.IDLE && state != FlixelNetSessionState.HOSTING) {
      throw new IllegalStateException("A session that is " + state + " cannot host; close() it first.");
    }
    transport.listen(port);
    links.add(new Link(transport));
    state = FlixelNetSessionState.HOSTING;
    localId = HOST_ID;
  }

  /**
   * Starts joining a host.
   *
   * <p>This returns right away. Later, during {@link #update(float)}, either {@link #onConnect}
   * is dispatched with the host, or {@link #onJoinFailed} is dispatched with the reason.
   *
   * @param transport The transport to connect through. It must be closed.
   * @param address Where the host is, in the form the transport expects.
   * @throws IllegalStateException If the session is not {@link FlixelNetSessionState#IDLE}.
   */
  public void join(@NotNull FlixelNetTransport transport, @NotNull String address) {
    if (state != FlixelNetSessionState.IDLE) {
      throw new IllegalStateException("A session that is " + state + " cannot join; close() it first.");
    }
    transport.connect(address);
    links.add(new Link(transport));
    state = FlixelNetSessionState.JOINING;
    hostPeer = null;
    localId = -1;
    pingTimer = 0f;
    sendTimer = 0f;
    joinStarted = clock;
  }

  /**
   * Runs the session for one frame: delivers what arrived, drops peers that went silent, and
   * sends what was queued.
   *
   * <p>Call this once per frame, whether hosting or joined. Every handler and signal of this
   * session runs from inside this call.
   *
   * @param elapsed How many seconds have passed since the last call.
   */
  public void update(float elapsed) {
    clock += elapsed;
    if (state == FlixelNetSessionState.IDLE) {
      return;
    }
    int started = epoch;
    for (int i = 0; i < links.getSize() && epoch == started; i++) {
      Link link = links.get(i);
      link.transport.poll(link);
    }
    if (epoch != started) {
      return;
    }

    if (state == FlixelNetSessionState.JOINING && clock - joinStarted > timeout) {
      failJoin(FlixelNetDisconnectReason.TIMEOUT);
      return;
    }
    for (int i = pending.getSize() - 1; i >= 0; i--) {
      FlixelNetPeer peer = pending.get(i);
      if (state == FlixelNetSessionState.HOSTING && clock - peer.lastHeard > timeout) {
        finish(peer, FlixelNetDisconnectReason.TIMEOUT, false);
      }
    }
    for (int i = peers.getSize() - 1; i >= 0 && epoch == started; i--) {
      FlixelNetPeer peer = peers.get(i);
      if (clock - peer.lastHeard > timeout) {
        finish(peer, FlixelNetDisconnectReason.TIMEOUT, true);
      }
    }
    if (epoch != started) {
      return;
    }

    pingTimer += elapsed;
    if (pingTimer >= pingInterval) {
      pingTimer = 0f;
      int now = (int) (clock * 1000.0);
      for (int i = 0, n = peers.getSize(); i < n; i++) {
        FlixelNetPeer peer = peers.get(i);
        scratch.clear();
        scratch.writeVarUInt(PING);
        scratch.writeInt(now);
        append(peer, FlixelNetChannel.UNRELIABLE);
      }
    }

    sendTimer += elapsed;
    if (sendTimer >= sendInterval) {
      sendTimer = 0f;
      flush();
    }
  }

  /**
   * Runs a handler every time a message of the given class arrives.
   *
   * <p>Several handlers can be added for the same class; they run in the order they were added.
   *
   * @param type The message class to listen for.
   * @param handler What to run when one arrives.
   * @param <T> The message class.
   * @throws IllegalArgumentException If the class is not part of this session's protocol.
   */
  public <T> void on(@NotNull Class<T> type, @NotNull FlixelNetHandler<T> handler) {
    handlers.get(protocol.entry(type).getIndex()).add(handler);
  }

  /**
   * Stops a handler added with {@link #on(Class, FlixelNetHandler)}.
   *
   * @param type The message class the handler was added for.
   * @param handler The handler to remove.
   * @param <T> The message class.
   * @return {@code true} if the handler was found and removed.
   */
  public <T> boolean off(@NotNull Class<T> type, @NotNull FlixelNetHandler<T> handler) {
    return handlers.get(protocol.entry(type).getIndex()).removeValue(handler, true);
  }

  /**
   * Hands out an empty message to fill in and send, reusing an old one when possible.
   *
   * @param type The message class.
   * @param <T> The message class.
   * @return A message with every field at its starting value.
   * @throws IllegalArgumentException If the class is not part of this session's protocol.
   */
  @NotNull
  public <T> T obtain(@NotNull Class<T> type) {
    return protocol.obtain(type);
  }

  /**
   * Queues a message for the host (when joined) or for every client (when hosting).
   *
   * <p>The message belongs to the session again once this returns: it will be reused, so do not
   * read or change it afterward.
   *
   * @param message The message to send, usually from {@link #obtain(Class)}.
   * @param channel How carefully to deliver it.
   * @return {@code true} if it was queued for at least one peer.
   * @throws IllegalArgumentException If the message's class is not part of this session's
   *     protocol, or the message is too large for the channel.
   */
  public boolean send(@NotNull Object message, @NotNull FlixelNetChannel channel) {
    return sendExcept(null, message, channel);
  }

  /**
   * Queues a message for one peer.
   *
   * <p>The message belongs to the session again once this returns.
   *
   * @param peer The peer to send to.
   * @param message The message to send, usually from {@link #obtain(Class)}.
   * @param channel How carefully to deliver it.
   * @return {@code true} if it was queued, {@code false} if the peer is not connected.
   * @throws IllegalArgumentException If the message's class is not part of this session's
   *     protocol, or the message is too large for the channel.
   */
  public boolean sendTo(@NotNull FlixelNetPeer peer, @NotNull Object message, @NotNull FlixelNetChannel channel) {
    FlixelNetProtocol.Entry<?> entry = protocol.entry(message.getClass());
    try {
      if (peer.state != FlixelNetPeer.CONNECTED) {
        return false;
      }
      encode(entry, message);
      append(peer, channel);
      return true;
    } finally {
      protocol.free(message);
    }
  }

  /**
   * Queues a message for every connected peer but one.
   *
   * <p>This is what a host uses to pass on something one client said to all the others. The
   * message belongs to the session again once this returns.
   *
   * @param skip The peer to leave out, or {@code null} to leave nobody out.
   * @param message The message to send, usually from {@link #obtain(Class)}.
   * @param channel How carefully to deliver it.
   * @return {@code true} if it was queued for at least one peer.
   * @throws IllegalArgumentException If the message's class is not part of this session's
   *     protocol, or the message is too large for the channel.
   */
  public boolean sendExcept(@Nullable FlixelNetPeer skip, @NotNull Object message, @NotNull FlixelNetChannel channel) {
    FlixelNetProtocol.Entry<?> entry = protocol.entry(message.getClass());
    try {
      boolean queued = false;
      boolean encoded = false;
      for (int i = 0, n = peers.getSize(); i < n; i++) {
        FlixelNetPeer peer = peers.get(i);
        if (peer == skip) {
          continue;
        }
        if (!encoded) {
          encode(entry, message);
          encoded = true;
        }
        append(peer, channel);
        queued = true;
      }
      return queued;
    } finally {
      protocol.free(message);
    }
  }

  /**
   * Sends everything that is queued right now instead of waiting for the end of
   * {@link #update(float)}.
   */
  public void flush() {
    for (int i = 0, n = peers.getSize(); i < n; i++) {
      FlixelNetPeer peer = peers.get(i);
      flush(peer, FlixelNetChannel.RELIABLE);
      flush(peer, FlixelNetChannel.UNRELIABLE);
    }
  }

  /**
   * Removes a peer from the session. Only a host can do this.
   *
   * <p>The peer is told it was kicked, and {@link #onDisconnect} is dispatched for it before this
   * returns.
   *
   * @param peer The peer to remove.
   * @throws IllegalStateException If this session is not hosting.
   */
  public void kick(@NotNull FlixelNetPeer peer) {
    if (state != FlixelNetSessionState.HOSTING) {
      throw new IllegalStateException("Only a host can kick a peer.");
    }
    if (peer.state != FlixelNetPeer.CLOSED) {
      finish(peer, FlixelNetDisconnectReason.KICKED, true);
    }
  }

  /**
   * Ends the session: a client leaves its host, and a host shuts down and disconnects everyone.
   *
   * <p>The other side is told why. {@link #onDisconnect} is dispatched for every peer that was
   * connected, and the session goes back to {@link FlixelNetSessionState#IDLE} so it can host or
   * join again. Handlers stay registered.
   */
  public void close() {
    if (state == FlixelNetSessionState.IDLE) {
      return;
    }
    FlixelNetDisconnectReason reason = state == FlixelNetSessionState.HOSTING
        ? FlixelNetDisconnectReason.SHUTDOWN
        : FlixelNetDisconnectReason.LEFT;
    for (int i = 0, n = peers.getSize(); i < n; i++) {
      FlixelNetPeer peer = peers.get(i);
      flush(peer, FlixelNetChannel.RELIABLE);
      sendBye(peer, reason);
    }
    reset();
    while (!peers.isEmpty()) {
      FlixelNetPeer peer = peers.pop();
      peer.state = FlixelNetPeer.CLOSED;
      peer.reason = reason;
      onDisconnect.dispatch(peer);
    }
  }

  /**
   * Returns the peer with the given id.
   *
   * @param id The id to look for.
   * @return The connected peer with that id, or {@code null} if there is none.
   */
  @Nullable
  public FlixelNetPeer getPeer(int id) {
    for (int i = 0, n = peers.getSize(); i < n; i++) {
      FlixelNetPeer peer = peers.get(i);
      if (peer.id == id) {
        return peer;
      }
    }
    return null;
  }

  /**
   * Returns the peer at a position in the list of connected peers.
   *
   * <p>Together with {@link #getPeerCount()} this loops over everyone without creating garbage.
   *
   * @param index A position from 0 to {@link #getPeerCount()} minus one.
   * @return The peer at that position.
   */
  @NotNull
  public FlixelNetPeer getPeerAt(int index) {
    return peers.get(index);
  }

  /**
   * Returns how many peers are connected: the clients on a host, or 1 on a joined client.
   *
   * @return The number of connected peers.
   */
  public int getPeerCount() {
    return peers.getSize();
  }

  /**
   * Returns the host this session is joined to.
   *
   * @return The host, or {@code null} unless the session is {@link FlixelNetSessionState#JOINED}.
   */
  @Nullable
  public FlixelNetPeer getHost() {
    return state == FlixelNetSessionState.JOINED ? hostPeer : null;
  }

  /**
   * Returns this side's own id in the session.
   *
   * @return {@link #HOST_ID} when hosting, the id the host handed out when joined, and -1
   *     otherwise.
   */
  public int getLocalId() {
    return localId;
  }

  /**
   * Returns whether this session is the host.
   *
   * @return {@code true} while hosting.
   */
  public boolean isHost() {
    return state == FlixelNetSessionState.HOSTING;
  }

  @NotNull
  public FlixelNetSessionState getState() {
    return state;
  }

  @NotNull
  public FlixelNetProtocol getProtocol() {
    return protocol;
  }

  public float getTimeout() {
    return timeout;
  }

  /**
   * Sets how many seconds of silence make a peer count as gone.
   *
   * <p>The same limit applies to a host that never answers a join.
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

  public float getPingInterval() {
    return pingInterval;
  }

  /**
   * Sets how often ping is measured.
   *
   * <p>These small packets also keep a quiet connection from looking dead, so the interval should
   * stay well below {@link #getTimeout()}.
   *
   * @param seconds The time between measurements in seconds. Must be greater than zero.
   * @throws IllegalArgumentException If {@code seconds} is not greater than zero.
   */
  public void setPingInterval(float seconds) {
    if (!(seconds > 0f)) {
      throw new IllegalArgumentException("Ping interval must be greater than zero, got " + seconds + ".");
    }
    pingInterval = seconds;
  }

  /**
   * Returns how many times per second queued messages are sent.
   *
   * @return The send rate, or zero when messages go out on every {@link #update(float)}.
   */
  public float getSendRate() {
    return sendInterval > 0f ? 1f / sendInterval : 0f;
  }

  /**
   * Sets how many times per second queued messages are sent.
   *
   * <p>A game running at 144 frames per second does not need 144 packets per second. A rate of 20
   * to 30 is typical. Messages queued between sends ride together in one packet.
   *
   * @param perSecond The send rate. Zero or less sends on every {@link #update(float)}.
   */
  public void setSendRate(float perSecond) {
    sendInterval = perSecond > 0f ? 1f / perSecond : 0f;
  }

  public int getMaxPeers() {
    return maxPeers;
  }

  /**
   * Sets how many clients a host accepts. Anyone who tries to join past that is turned away with
   * {@link FlixelNetDisconnectReason#FULL}.
   *
   * @param maxPeers The largest number of clients. Must be at least 1.
   * @throws IllegalArgumentException If {@code maxPeers} is less than 1.
   */
  public void setMaxPeers(int maxPeers) {
    if (maxPeers < 1) {
      throw new IllegalArgumentException("Max peers must be at least 1, got " + maxPeers + ".");
    }
    this.maxPeers = maxPeers;
  }

  /** Writes a message, with its number in front, into the scratch buffer. */
  @SuppressWarnings("unchecked")
  private <T> void encode(FlixelNetProtocol.Entry<T> entry, Object message) {
    scratch.clear();
    scratch.writeVarUInt(FIRST_USER + entry.getIndex());
    entry.write((T) message, scratch);
  }

  /** Adds the scratch buffer to a peer's outgoing packet, sending the packet first if it is full. */
  private void append(FlixelNetPeer peer, FlixelNetChannel channel) {
    FlixelNetBuffer out = channel == FlixelNetChannel.RELIABLE ? peer.reliable : peer.unreliable;
    int max = peer.transport.getMaxPacketSize(channel);
    if (scratch.getSize() > max) {
      throw new IllegalArgumentException("A message of " + scratch.getSize() + " bytes is larger than the " + max
          + " bytes one packet can hold on the " + channel + " channel.");
    }
    if (out.getSize() + scratch.getSize() > max) {
      flush(peer, channel);
    }
    out.writeBuffer(scratch);
  }

  private void flush(FlixelNetPeer peer, FlixelNetChannel channel) {
    FlixelNetBuffer out = channel == FlixelNetChannel.RELIABLE ? peer.reliable : peer.unreliable;
    if (out.getSize() == 0) {
      return;
    }
    if (peer.transport.send(peer.connection, channel, out.getData(), 0, out.getSize())) {
      peer.bytesSent += out.getSize();
    }
    out.clear();
  }

  private void sendBye(FlixelNetPeer peer, FlixelNetDisconnectReason reason) {
    scratch.clear();
    scratch.writeVarUInt(BYE);
    scratch.writeByte(reason.ordinal());
    append(peer, FlixelNetChannel.RELIABLE);
    flush(peer, FlixelNetChannel.RELIABLE);
  }

  /**
   * Removes one peer from the session, on this side's decision or on the other side's word.
   *
   * @param peer The peer to remove.
   * @param reason Why it is being removed.
   * @param tell Whether to tell the peer the reason before cutting the connection.
   */
  private void finish(FlixelNetPeer peer, FlixelNetDisconnectReason reason, boolean tell) {
    boolean wasConnected = peer.state == FlixelNetPeer.CONNECTED;
    if (wasConnected && tell) {
      sendBye(peer, reason);
    }
    peer.link.byConnection.remove(peer.connection);
    peers.removeValue(peer, true);
    pending.removeValue(peer, true);
    peer.state = FlixelNetPeer.CLOSED;
    peer.reason = reason;
    if (state == FlixelNetSessionState.HOSTING) {
      peer.transport.disconnect(peer.connection);
      if (wasConnected) {
        onDisconnect.dispatch(peer);
      }
    } else if (wasConnected) {
      reset();
      onDisconnect.dispatch(peer);
    } else {
      failJoin(reason);
    }
  }

  private void failJoin(FlixelNetDisconnectReason reason) {
    reset();
    onJoinFailed.dispatch(reason);
  }

  /** Closes every transport and forgets every peer that never finished connecting. */
  private void reset() {
    epoch++;
    state = FlixelNetSessionState.IDLE;
    for (int i = 0, n = links.getSize(); i < n; i++) {
      Link link = links.get(i);
      link.byConnection.clear();
      link.transport.close();
    }
    links.clear();
    pending.clear();
    hostPeer = null;
    localId = -1;
    nextPeerId = 1;
  }

  /** Counts a packet that could not be understood, and drops a peer that keeps sending them. */
  private void bad(FlixelNetPeer peer) {
    peer.badPackets++;
    if (peer.state != FlixelNetPeer.CLOSED
        && (peer.state == FlixelNetPeer.HANDSHAKING || peer.badPackets >= MAX_BAD_PACKETS)) {
      finish(peer, FlixelNetDisconnectReason.ERROR, true);
    }
  }

  /** Reads one user message from the incoming buffer and runs its handlers. */
  @SuppressWarnings("unchecked")
  private <T> void deliver(FlixelNetPeer peer, FlixelNetProtocol.Entry<T> entry, int started) {
    T message = entry.obtain();
    try {
      entry.read(message, in);
      FlixelArray<FlixelNetHandler<?>> list = handlers.get(entry.getIndex());
      for (int i = 0; i < list.getSize() && epoch == started && peer.state == FlixelNetPeer.CONNECTED; i++) {
        ((FlixelNetHandler<T>) list.get(i)).handle(peer, message);
      }
    } finally {
      entry.free(message);
    }
  }

  /**
   * Handles one of the session's own messages.
   *
   * @return Whether the rest of the packet should still be read.
   */
  private boolean system(FlixelNetPeer peer, int id) {
    switch (id) {
      case HELLO -> {
        int magic = in.readInt();
        int wire = in.readVarUInt();
        int hash = in.readInt();
        if (state != FlixelNetSessionState.HOSTING || peer.state != FlixelNetPeer.HANDSHAKING) {
          return false;
        }
        if (magic != MAGIC) {
          finish(peer, FlixelNetDisconnectReason.ERROR, false);
        } else if (wire != WIRE_VERSION || hash != protocol.getHash()) {
          reject(peer, FlixelNetDisconnectReason.VERSION_MISMATCH);
        } else if (peers.getSize() >= maxPeers) {
          reject(peer, FlixelNetDisconnectReason.FULL);
        } else {
          accept(peer);
          return true;
        }
        return false;
      }
      case WELCOME -> {
        int assigned = in.readVarUInt();
        if (state != FlixelNetSessionState.JOINING || peer != hostPeer) {
          return false;
        }
        localId = assigned;
        peer.id = HOST_ID;
        peer.state = FlixelNetPeer.CONNECTED;
        pending.removeValue(peer, true);
        peers.add(peer);
        state = FlixelNetSessionState.JOINED;
        onConnect.dispatch(peer);
        return true;
      }
      case REJECT -> {
        FlixelNetDisconnectReason reason = reason(in.readUByte());
        if (state == FlixelNetSessionState.JOINING && peer == hostPeer) {
          finish(peer, reason, false);
        }
        return false;
      }
      case PING -> {
        int time = in.readInt();
        if (peer.state == FlixelNetPeer.CONNECTED) {
          scratch.clear();
          scratch.writeVarUInt(PONG);
          scratch.writeInt(time);
          append(peer, FlixelNetChannel.UNRELIABLE);
        }
        return true;
      }
      case PONG -> {
        // Subtracting the two wrapped millisecond counters still gives the right gap.
        float sample = ((int) (clock * 1000.0) - in.readInt()) / 1000f;
        if (sample >= 0f && sample < 60f) {
          peer.rtt = peer.rtt == 0f ? sample : peer.rtt * 0.8f + sample * 0.2f;
        }
        return true;
      }
      case BYE -> {
        FlixelNetDisconnectReason reason = reason(in.readUByte());
        if (peer.state == FlixelNetPeer.CONNECTED) {
          finish(peer, reason, false);
        }
        return false;
      }
      default -> throw new FlixelNetException("Unknown session message " + id + ".");
    }
  }

  private void accept(FlixelNetPeer peer) {
    peer.id = nextPeerId++;
    peer.state = FlixelNetPeer.CONNECTED;
    pending.removeValue(peer, true);
    peers.add(peer);
    scratch.clear();
    scratch.writeVarUInt(WELCOME);
    scratch.writeVarUInt(peer.id);
    append(peer, FlixelNetChannel.RELIABLE);
    flush(peer, FlixelNetChannel.RELIABLE);
    onConnect.dispatch(peer);
  }

  private void reject(FlixelNetPeer peer, FlixelNetDisconnectReason reason) {
    scratch.clear();
    scratch.writeVarUInt(REJECT);
    scratch.writeByte(reason.ordinal());
    peer.transport.send(peer.connection, FlixelNetChannel.RELIABLE, scratch.getData(), 0, scratch.getSize());
    finish(peer, reason, false);
  }

  private static FlixelNetDisconnectReason reason(int ordinal) {
    if (ordinal >= REASONS.length) {
      throw new FlixelNetException("Unknown disconnect reason " + ordinal + ".");
    }
    return REASONS[ordinal];
  }

  private static FlixelNetDisconnectReason reason(FlixelNetCloseReason reason) {
    return switch (reason) {
      case LOCAL, REMOTE -> FlixelNetDisconnectReason.LEFT;
      case TIMEOUT -> FlixelNetDisconnectReason.TIMEOUT;
      case REFUSED -> FlixelNetDisconnectReason.REFUSED;
      case ERROR -> FlixelNetDisconnectReason.ERROR;
    };
  }

  /** One transport the session is using, and the peers that are connected through it. */
  final class Link implements FlixelNetTransportListener {

    final FlixelNetTransport transport;
    final FlixelIntMap<FlixelNetPeer> byConnection = new FlixelIntMap<>();

    Link(FlixelNetTransport transport) {
      this.transport = transport;
    }

    @Override
    public void onOpen(int connection) {
      FlixelNetPeer peer = new FlixelNetPeer(this, connection, clock);
      byConnection.put(connection, peer);
      pending.add(peer);
      if (state == FlixelNetSessionState.JOINING) {
        hostPeer = peer;
        scratch.clear();
        scratch.writeVarUInt(HELLO);
        scratch.writeInt(MAGIC);
        scratch.writeVarUInt(WIRE_VERSION);
        scratch.writeInt(protocol.getHash());
        transport.send(connection, FlixelNetChannel.RELIABLE, scratch.getData(), 0, scratch.getSize());
      }
    }

    @Override
    public void onClose(int connection, FlixelNetCloseReason closeReason) {
      FlixelNetPeer peer = byConnection.get(connection);
      if (peer != null) {
        finish(peer, reason(closeReason), false);
      } else if (state == FlixelNetSessionState.JOINING) {
        failJoin(reason(closeReason));
      }
    }

    @Override
    public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
      FlixelNetPeer peer = byConnection.get(connection);
      if (peer == null) {
        return;
      }
      peer.lastHeard = clock;
      peer.bytesReceived += length;
      int started = epoch;
      try {
        in.wrap(data, offset, length);
        while (in.remaining() > 0) {
          int id = in.readVarUInt();
          if (id < FIRST_USER) {
            if (!system(peer, id)) {
              return;
            }
          } else {
            FlixelNetProtocol.Entry<?> entry = protocol.entry(id - FIRST_USER);
            if (entry == null) {
              throw new FlixelNetException("Unknown message number " + id + ".");
            }
            if (peer.state != FlixelNetPeer.CONNECTED) {
              return;
            }
            deliver(peer, entry, started);
          }
          if (epoch != started || peer.state == FlixelNetPeer.CLOSED) {
            return;
          }
        }
      } catch (FlixelNetException e) {
        bad(peer);
      }
    }
  }
}
