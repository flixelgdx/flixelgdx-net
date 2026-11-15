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

import org.jetbrains.annotations.Nullable;

/**
 * Makes the transports a platform has to offer.
 *
 * <p>Each platform reaches the network differently: desktop and Android can use UDP, a browser
 * cannot. A platform module supplies one of these so game code can ask for "the right transport
 * here" without knowing which platform it is running on.
 */
public interface FlixelNetTransportFactory {

  /**
   * Creates a transport for direct connections between desktop and Android players.
   *
   * <p>This is the fastest option, but browsers cannot use it.
   *
   * @return A new, closed transport, or {@code null} if this platform cannot make one.
   */
  @Nullable
  FlixelNetTransport newDatagramTransport();

  /**
   * Creates a transport that browsers can connect through.
   *
   * @return A new, closed transport, or {@code null} if this platform cannot make one.
   */
  @Nullable
  FlixelNetTransport newWebTransport();
}
