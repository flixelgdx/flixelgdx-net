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

import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelJvmWebTransportTest {

  private final FlixelJvmWebTransport host = new FlixelJvmWebTransport();
  private final FlixelJvmWebTransport client = new FlixelJvmWebTransport();
  private final Recorder hostLog = new Recorder();
  private final Recorder clientLog = new Recorder();

  @AfterEach
  void tearDown() {
    client.close();
    host.close();
  }

  @Test
  void withoutWebRtcItBehavesLikeAPlainWebSocket() throws InterruptedException {
    host.setWebRtc(false);
    client.setWebRtc(false);
    host.listen(0);
    client.connect("ws://127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, new byte[] { 4 }, 0, 1);
    Recorder.await(host, hostLog, client, clientLog, "data 1 UNRELIABLE 4\n");
    assertEquals("WebSocket", host.getRoute(1));
    assertEquals("WebSocket", client.getRoute(FlixelNetTransport.HOST));
  }

  @Test
  void connectionUpgradesToWebRtcAndKeepsReliablePacketsInOrder() throws InterruptedException {
    Assumptions.assumeTrue(FlixelJvmRtc.isAvailable(), "the native WebRTC library is not available here");
    host.listen(0);
    client.connect("ws://127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");

    // Keep a numbered stream of reliable packets flowing both ways while the route changes
    // underneath it. Every packet must arrive exactly once and in order.
    byte[] packet = new byte[1];
    int sent = 0;
    long deadline = System.nanoTime() + 20_000_000_000L;
    while ((!host.getRoute(1).equals("WebRTC") || !client.getRoute(FlixelNetTransport.HOST).equals("WebRTC")
        || sent < 100) && System.nanoTime() < deadline) {
      packet[0] = (byte) (sent % 100);
      assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, packet, 0, 1));
      assertTrue(host.send(1, FlixelNetChannel.RELIABLE, packet, 0, 1));
      sent++;
      host.poll(hostLog);
      client.poll(clientLog);
      Thread.sleep(5);
    }
    assertEquals("WebRTC", host.getRoute(1));
    assertEquals("WebRTC", client.getRoute(FlixelNetTransport.HOST));

    StringBuilder expectedAtHost = new StringBuilder();
    StringBuilder expectedAtClient = new StringBuilder();
    for (int i = 0; i < sent; i++) {
      expectedAtHost.append("data 1 RELIABLE ").append(i % 100).append('\n');
      expectedAtClient.append("data 0 RELIABLE ").append(i % 100).append('\n');
    }
    Recorder.await(host, hostLog, client, clientLog, expectedAtHost.toString(), expectedAtClient.toString());
    hostLog.take();
    clientLog.take();

    // Now on WebRTC: both channels, both ways, including a packet too large for one datagram.
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, new byte[] { 7 }, 0, 1);
    host.send(1, FlixelNetChannel.UNRELIABLE, new byte[] { 8 }, 0, 1);
    byte[] big = new byte[100_000];
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, big, 0, big.length);
    Recorder.await(host, hostLog, client, clientLog, "data 1 UNRELIABLE 7\n", "data 0 UNRELIABLE 8\n",
        "data 1 RELIABLE 100000 bytes\n");

    hostLog.take();
    clientLog.take();
    client.disconnect(FlixelNetTransport.HOST);
    Recorder.await(host, hostLog, client, clientLog, "close 1 REMOTE\n", "close 0 LOCAL\n");
  }

  @Test
  void settingsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> host.setWebRtcPort(-1));
    assertThrows(IllegalArgumentException.class, () -> host.setWebRtcPort(70000));
    host.setWebRtcPort(7778);
    assertEquals(7778, host.getWebRtcPort());
    assertTrue(host.isWebRtc());
  }
}
