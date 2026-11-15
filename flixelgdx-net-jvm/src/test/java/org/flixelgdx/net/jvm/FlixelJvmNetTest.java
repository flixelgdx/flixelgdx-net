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
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.FlixelNetSessionState;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlixelJvmNetTest {

  private final StringBuilder log = new StringBuilder();
  private final FlixelNetSession host = session("host");
  private final FlixelNetSession udpClient = session("udp");
  private final FlixelNetSession webClient = session("web");
  private final FlixelNetSession localClient = session("local");

  @AfterEach
  void tearDown() {
    udpClient.close();
    webClient.close();
    localClient.close();
    host.close();
    FlixelNet.install(null);
  }

  @Test
  void oneHostServesUdpWebSocketAndLocalPlayersOnOnePortNumber() throws Exception {
    assertThrows(IllegalStateException.class, () -> FlixelNet.host(host, 7777));
    FlixelJvmNet.install();
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }

    FlixelNet.host(host, port);
    FlixelNet.joinLocal(host, localClient);
    FlixelNet.join(udpClient, "127.0.0.1:" + port);
    FlixelNet.join(webClient, "ws://127.0.0.1:" + port);
    await(() -> host.getPeerCount() == 3 && udpClient.getState() == FlixelNetSessionState.JOINED
        && webClient.getState() == FlixelNetSessionState.JOINED
        && localClient.getState() == FlixelNetSessionState.JOINED);

    Shout shout = host.obtain(Shout.class);
    shout.text = "hi all";
    host.send(shout, FlixelNetChannel.RELIABLE);
    Shout reply = webClient.obtain(Shout.class);
    reply.text = "hi host";
    webClient.send(reply, FlixelNetChannel.UNRELIABLE);
    await(() -> count("hi all") == 3 && count("hi host") == 1);

    host.close();
    await(() -> udpClient.getState() == FlixelNetSessionState.IDLE && webClient.getState() == FlixelNetSessionState.IDLE
        && localClient.getState() == FlixelNetSessionState.IDLE);
    assertEquals(3, count("SHUTDOWN") - count("host lost"));
  }

  private FlixelNetSession session(String name) {
    FlixelNetSession session = new FlixelNetSession(new FlixelNetProtocol("jvm-test", 1).add(ShoutNetCodec.INSTANCE));
    session.on(Shout.class, (peer, shout) -> log.append(name).append(" heard ").append(shout.text).append(';'));
    session.onDisconnect.add(peer -> log.append(name).append(" lost ").append(peer.getDisconnectReason()).append(';'));
    return session;
  }

  private int count(String piece) {
    int found = 0;
    for (int at = log.indexOf(piece); at >= 0; at = log.indexOf(piece, at + 1)) {
      found++;
    }
    return found;
  }

  private void await(Condition condition) throws InterruptedException {
    long deadline = System.nanoTime() + 8_000_000_000L;
    while (!condition.met()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out. Log so far: " + log);
      }
      host.update(0.005f);
      udpClient.update(0.005f);
      webClient.update(0.005f);
      localClient.update(0.005f);
      Thread.sleep(5);
    }
  }

  private interface Condition {
    boolean met();
  }
}
