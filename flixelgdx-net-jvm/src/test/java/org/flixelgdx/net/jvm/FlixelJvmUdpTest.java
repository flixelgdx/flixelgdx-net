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
import org.flixelgdx.net.transport.FlixelNetDatagramTransport;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelJvmUdpTest {

  private final FlixelNetDatagramTransport<SocketAddress> host = FlixelJvmNet.newUdpTransport();
  private final FlixelNetDatagramTransport<SocketAddress> client = FlixelJvmNet.newUdpTransport();
  private final Recorder hostLog = new Recorder();
  private final Recorder clientLog = new Recorder();

  @AfterEach
  void tearDown() {
    client.close();
    host.close();
  }

  @Test
  void twoTransportsTalkOverRealUdp() throws InterruptedException {
    host.listen(0);
    int port = ((FlixelJvmDatagramSocket) host.getSocket()).getPort();
    assertTrue(port > 0);
    client.connect("127.0.0.1:" + port);
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");
    assertEquals(FlixelNetTransportState.CONNECTED, client.getState());
    hostLog.take();
    clientLog.take();

    byte[] hello = { 1, 2, 3 };
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, hello, 0, 3);
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, hello, 0, 2);
    host.send(1, FlixelNetChannel.RELIABLE, hello, 2, 1);
    Recorder.await(host, hostLog, client, clientLog, "data 1 RELIABLE 1,2,3\n", "data 1 UNRELIABLE 1,2\n",
        "data 0 RELIABLE 3\n");

    byte[] big = new byte[100_000];
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, big, 0, big.length);
    Recorder.await(host, hostLog, client, clientLog, "data 1 RELIABLE 100000 bytes\n");

    hostLog.take();
    clientLog.take();
    client.disconnect(FlixelNetTransport.HOST);
    Recorder.await(host, hostLog, client, clientLog, "close 1 REMOTE\n", "close 0 LOCAL\n");
  }

  @Test
  void aSecondHostOnTheSamePortIsRefusedClearly() {
    host.listen(0);
    int port = ((FlixelJvmDatagramSocket) host.getSocket()).getPort();
    FlixelNetDatagramTransport<SocketAddress> other = FlixelJvmNet.newUdpTransport();
    assertThrows(IllegalStateException.class, () -> other.listen(port));
    assertEquals(FlixelNetTransportState.CLOSED, other.getState());
  }

  @Test
  void addressesAreParsedAndBadOnesExplained() {
    FlixelJvmDatagramSocket socket = new FlixelJvmDatagramSocket();
    assertEquals("/127.0.0.1:7777", socket.resolve("127.0.0.1:7777").toString());
    assertEquals("/127.0.0.1:7777", socket.resolve("udp://127.0.0.1:7777").toString());
    assertTrue(socket.resolve("[::1]:7777").toString().endsWith(":7777"));
    assertThrows(IllegalArgumentException.class, () -> socket.resolve("127.0.0.1"));
    assertThrows(IllegalArgumentException.class, () -> socket.resolve("127.0.0.1:port"));
    assertThrows(IllegalArgumentException.class, () -> socket.resolve("127.0.0.1:99999"));
    assertThrows(IllegalArgumentException.class, () -> socket.resolve(":7777"));
    assertEquals(-1, socket.getPort());
  }
}
