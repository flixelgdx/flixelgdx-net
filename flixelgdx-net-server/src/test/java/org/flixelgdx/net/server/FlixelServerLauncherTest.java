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
package org.flixelgdx.net.server;

import org.flixelgdx.Flixel;
import org.flixelgdx.FlixelGame;
import org.flixelgdx.FlixelState;
import org.flixelgdx.net.FlixelNet;
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.FlixelNetSessionState;
import org.flixelgdx.net.jvm.FlixelJvmNet;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelServerLauncherTest {

  private static final int PORT = 47917;

  private static volatile int updates;
  private static volatile boolean created;
  private static volatile boolean destroyed;
  private static volatile int joined;

  @Test
  void aGameRunsWithoutAWindowAndHostsASession() throws Exception {
    Thread server = new Thread(() -> FlixelServerLauncher.launch(new FlixelGame("server", ServerState::new) {
    }, 120),
        "server");
    server.start();
    long deadline = System.currentTimeMillis() + 10_000;
    while (updates < 10 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(created);
    assertTrue(updates >= 10, "the state updated " + updates + " times");
    assertTrue(FlixelServerLauncher.isServer());
    assertNotNull(FlixelServerLauncher.getRunner());
    assertTrue(FlixelServerLauncher.getRunner().isRunning());
    assertFalse(Flixel.autoPause);

    // A player joins the headless host from another thread, the way a separate program would.
    FlixelNetSession client = new FlixelNetSession(new FlixelNetProtocol("server-test", 1));
    client.join(FlixelJvmNet.newUdpTransport(), "127.0.0.1:" + PORT);
    while (client.getState() == FlixelNetSessionState.JOINING && System.currentTimeMillis() < deadline) {
      client.update(0.01f);
      Thread.sleep(10);
    }
    assertEquals(FlixelNetSessionState.JOINED, client.getState());
    while (joined == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(1, joined);

    // The rate holds: roughly 120 updates in a second.
    int before = updates;
    Thread.sleep(1000);
    int ran = updates - before;
    assertTrue(ran > 90 && ran < 150, "ran " + ran + " updates in a second");

    assertTrue(FlixelServerLauncher.getRunner().stopAndWait(5000));
    server.join(5000);
    assertFalse(server.isAlive());
    assertTrue(destroyed);
    client.close();
  }

  static class ServerState extends FlixelState {

    private FlixelNetSession session;

    @Override
    public void create() {
      super.create();
      created = true;
      session = new FlixelNetSession(new FlixelNetProtocol("server-test", 1));
      session.onConnect.add(peer -> joined++);
      FlixelNet.host(session, PORT);
    }

    @Override
    public void update(float elapsed) {
      super.update(elapsed);
      session.update(elapsed);
      updates++;
    }

    @Override
    public void destroy() {
      session.close();
      destroyed = true;
      super.destroy();
    }
  }
}
