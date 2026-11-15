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
package org.flixelgdx.net.android;

import org.flixelgdx.net.FlixelNet;
import org.flixelgdx.net.jvm.FlixelJvmNet;
import org.flixelgdx.net.jvm.FlixelJvmRtc;

/**
 * Networking for games running on Android.
 *
 * <p>Call {@link #install()} once from the launcher activity, before the game starts. After
 * that, {@link FlixelNet#host} and {@link FlixelNet#join} know how to reach the network:
 *
 * <pre>{@code
 * protected void onCreate(Bundle savedInstanceState) {
 *   super.onCreate(savedInstanceState);
 *   FlixelAndroidNet.install();
 *   launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>Android uses the same transports as desktop, see {@link FlixelJvmNet}. What this module
 * adds is the packaging an app needs: the native WebRTC library for every Android CPU type, and
 * the {@code INTERNET} permission, which is merged into the game's manifest automatically.
 *
 * <p>Android stops an app's network traffic soon after it leaves the screen, so a phone makes a
 * poor host for a long session. Joining works like anywhere else.
 */
public final class FlixelAndroidNet {

  private FlixelAndroidNet() {}

  /** Makes this platform's transports available through {@link FlixelNet}. */
  public static void install() {
    FlixelJvmNet.install();
  }

  /**
   * Returns whether the native WebRTC library could be loaded on this device.
   *
   * <p>Without it everything still works, and connections that would use WebRTC stay on the
   * WebSocket.
   *
   * @return {@code true} when WebRTC is usable.
   */
  public static boolean isWebRtcAvailable() {
    return FlixelJvmRtc.isAvailable();
  }
}
