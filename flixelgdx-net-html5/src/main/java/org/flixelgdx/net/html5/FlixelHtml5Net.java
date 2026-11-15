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

import org.flixelgdx.net.FlixelNet;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Networking for games running in a browser.
 *
 * <p>Call {@link #install()} once from the web launcher, before the game starts. After that,
 * {@link FlixelNet#join} knows how to reach a host from a browser tab:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   FlixelHtml5Net.install();
 *   FlixelHtml5Launcher.launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>A browser can join a session but cannot host one, so {@link FlixelNet#host} always fails
 * here.
 */
public final class FlixelHtml5Net implements FlixelNetTransportFactory {

  private FlixelHtml5Net() {}

  /** Makes this platform's transport available through {@link FlixelNet}. */
  public static void install() {
    FlixelNet.install(new FlixelHtml5Net());
  }

  @Override
  public @Nullable FlixelNetTransport newDatagramTransport() {
    return null;
  }

  @Override
  public @NotNull FlixelNetTransport newWebTransport() {
    return new FlixelHtml5WebTransport();
  }
}
