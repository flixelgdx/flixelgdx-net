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

import org.flixelgdx.Flixel;
import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.net.transport.FlixelNetIceConfig;
import org.flixelgdx.net.transport.FlixelNetLoopback;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportFactory;
import org.flixelgdx.signal.FlixelSignal.SignalHandler;
import org.flixelgdx.signal.FlixelSignalData.UpdateSignalData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The quick way to get a {@link FlixelNetSession} onto the real network.
 *
 * <p>A session does not know how to reach other computers; a transport does, and which transport
 * is right depends on the platform. This class picks for you. Each platform's launcher installs
 * its transports once at startup, and from then on the same two lines work everywhere:
 *
 * <pre>{@code
 * // In the desktop launcher:   FlixelJvmNet.install();
 * // In the web launcher:       FlixelHtml5Net.install();
 *
 * FlixelNetSession session = new FlixelNetSession(protocol);
 * FlixelNet.attach(session);              // Runs session.update(...) every frame.
 * FlixelNet.host(session, 7777);          // Or: FlixelNet.join(session, "192.168.1.20:7777");
 * }</pre>
 *
 * <h2>A host that also plays</h2>
 *
 * <p>The simplest way to write a multiplayer game is to write the client once and have the
 * host's own player join like everyone else, through a connection that never leaves the program:
 *
 * <pre>{@code
 * FlixelNet.host(hostSession, 7777);
 * FlixelNet.joinLocal(hostSession, clientSession);
 * }</pre>
 */
public final class FlixelNet {

  @SuppressWarnings("checkstyle:ConstantName")
  private static final FlixelArray<FlixelNetSession> attached = new FlixelArray<>();
  private static final SignalHandler<UpdateSignalData> UPDATER = FlixelNet::update;
  @SuppressWarnings("checkstyle:ConstantName")
  private static final FlixelNetIceConfig ice = new FlixelNetIceConfig();
  private static long lastNanos;
  private static FlixelNetTransportFactory factory;

  private FlixelNet() {}

  /**
   * Tells the extension how this platform reaches the network.
   *
   * <p>Platform modules call this from their own {@code install()} method, so games rarely call
   * it directly.
   *
   * @param transports What makes this platform's transports, or {@code null} to remove it.
   */
  public static void install(@Nullable FlixelNetTransportFactory transports) {
    factory = transports;
  }

  /**
   * Starts hosting a session on every kind of transport this platform has, all on the same port
   * number.
   *
   * @param session The session to host.
   * @param port The port to listen on.
   * @throws IllegalStateException If no platform was installed, the platform cannot host (a
   *     browser cannot), or the session is busy.
   */
  public static void host(@NotNull FlixelNetSession session, int port) {
    FlixelNetTransportFactory transports = requireFactory();
    FlixelNetTransport datagram = transports.newDatagramTransport();
    FlixelNetTransport web = transports.newWebTransport();
    if (datagram != null) {
      session.listen(datagram, port);
    }
    if (web != null) {
      session.listen(web, port);
    }
    if (datagram == null && web == null) {
      throw new IllegalStateException("This platform has no transport that can host.");
    }
  }

  /**
   * Starts joining a host over the network.
   *
   * <p>An address that starts with {@code ws://} or {@code wss://} is joined the way a browser
   * joins. Anything else, such as {@code "192.168.1.20:7777"}, is joined directly where the
   * platform can do that, and the browser way where it cannot.
   *
   * @param session The session that should join.
   * @param address Where the host is.
   * @throws IllegalStateException If no platform was installed, the platform has no transport
   *     that fits the address, or the session is busy.
   */
  public static void join(@NotNull FlixelNetSession session, @NotNull String address) {
    FlixelNetTransportFactory transports = requireFactory();
    boolean web = address.startsWith("ws://") || address.startsWith("wss://");
    FlixelNetTransport transport = web ? null : transports.newDatagramTransport();
    if (transport == null) {
      transport = transports.newWebTransport();
    }
    if (transport == null) {
      throw new IllegalStateException("This platform has no transport that can join '" + address + "'.");
    }
    session.join(transport, address);
  }

  /**
   * Joins one session to another inside the same program, with no network in between.
   *
   * <p>This is how a host becomes a player in its own game. The host session keeps accepting
   * other players as before.
   *
   * @param host The session that is hosting, or about to.
   * @param client The session that should join it.
   * @return The connection between the two, which can add pretend lag and packet loss for
   *     testing.
   */
  @NotNull
  public static FlixelNetLoopback joinLocal(@NotNull FlixelNetSession host, @NotNull FlixelNetSession client) {
    FlixelNetLoopback loopback = new FlixelNetLoopback();
    host.listen(loopback.getHost(), 0);
    client.join(loopback.newClient(), "");
    return loopback;
  }

  /**
   * Makes a session run by itself: {@link FlixelNetSession#update(float)} is called once per
   * frame, before the game's own update, until {@link #detach(FlixelNetSession)}.
   *
   * <p>Real time is used, not game time, so a paused or slowed-down game does not look like a
   * dead connection to the other side.
   *
   * @param session The session to run every frame.
   */
  public static void attach(@NotNull FlixelNetSession session) {
    if (attached.contains(session, true)) {
      return;
    }
    if (attached.isEmpty()) {
      lastNanos = System.nanoTime();
      Flixel.Signals.preUpdate.add(UPDATER);
    }
    attached.add(session);
  }

  /**
   * Stops running a session every frame. The session is not closed.
   *
   * @param session The session to stop running.
   */
  public static void detach(@NotNull FlixelNetSession session) {
    if (attached.removeValue(session, true) && attached.isEmpty()) {
      Flixel.Signals.preUpdate.remove(UPDATER);
    }
  }

  /**
   * Returns the helper servers that WebRTC connections made through this class may use.
   *
   * <p>Add servers to it before hosting or joining. It starts out empty, which is enough on a
   * local network and for a host with a public address.
   *
   * @return The shared configuration.
   */
  @NotNull
  public static FlixelNetIceConfig getIce() {
    return ice;
  }

  /**
   * Returns what makes this platform's transports.
   *
   * @return The installed factory, or {@code null} if no platform was installed.
   */
  @Nullable
  public static FlixelNetTransportFactory getFactory() {
    return factory;
  }

  private static void update(UpdateSignalData data) {
    long now = System.nanoTime();
    float elapsed = Math.min(1f, (now - lastNanos) / 1.0e9f);
    lastNanos = now;
    for (int i = attached.getSize() - 1; i >= 0; i--) {
      attached.get(i).update(elapsed);
    }
  }

  private static FlixelNetTransportFactory requireFactory() {
    if (factory == null) {
      throw new IllegalStateException("No network platform is installed. Call the platform's install() method "
          + "(for example FlixelJvmNet.install()) from the launcher first.");
    }
    return factory;
  }
}
