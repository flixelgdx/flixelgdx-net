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

import org.flixelgdx.net.FlixelNet;
import org.flixelgdx.net.transport.FlixelNetDatagramTransport;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportFactory;
import org.jetbrains.annotations.NotNull;

import java.net.SocketAddress;
import java.security.SecureRandom;

/**
 * Networking for desktop, Android and dedicated servers.
 *
 * <p>Call {@link #install()} once from the launcher, before the game starts. After that,
 * {@link FlixelNet#host} and {@link FlixelNet#join} know how to reach the network on this
 * platform:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   FlixelJvmNet.install();
 *   FlixelDesktopLauncher.launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>A host listens two ways at once on the same port number: over UDP for desktop and Android
 * players, and over WebSocket for players in a browser. Browser connections upgrade themselves
 * to WebRTC when the native WebRTC library is present; see {@link FlixelJvmWebTransport}.
 */
public final class FlixelJvmNet implements FlixelNetTransportFactory {

  private static final SecureRandom RANDOM = new SecureRandom();

  private FlixelJvmNet() {}

  /** Makes this platform's transports available through {@link FlixelNet}. */
  public static void install() {
    FlixelNet.install(new FlixelJvmNet());
  }

  /**
   * Creates a UDP transport, for games that want to set one up by hand.
   *
   * @return A new, closed transport.
   */
  @NotNull
  public static FlixelNetDatagramTransport<SocketAddress> newUdpTransport() {
    return new FlixelNetDatagramTransport<>(new FlixelJvmDatagramSocket(), RANDOM.nextLong());
  }

  @Override
  public @NotNull FlixelNetTransport newDatagramTransport() {
    return newUdpTransport();
  }

  @Override
  public @NotNull FlixelNetTransport newWebTransport() {
    FlixelJvmWebTransport transport = new FlixelJvmWebTransport();
    transport.setIce(FlixelNet.getIce());
    return transport;
  }
}
