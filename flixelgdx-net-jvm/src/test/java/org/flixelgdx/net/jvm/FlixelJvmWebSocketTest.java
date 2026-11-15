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
import org.flixelgdx.net.transport.FlixelNetTransportState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelJvmWebSocketTest {

  private final FlixelJvmWebSocketTransport host = new FlixelJvmWebSocketTransport();
  private final FlixelJvmWebSocketTransport client = new FlixelJvmWebSocketTransport();
  private final Recorder hostLog = new Recorder();
  private final Recorder clientLog = new Recorder();

  @AfterEach
  void tearDown() {
    client.close();
    host.close();
  }

  @Test
  void clientAndHostTalkOverARealWebSocket() throws InterruptedException {
    host.listen(0);
    client.connect("ws://127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");
    assertEquals(FlixelNetTransportState.CONNECTED, client.getState());
    hostLog.take();
    clientLog.take();

    byte[] hello = { 1, 2, 3 };
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, hello, 0, 3);
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, hello, 0, 2);
    host.send(1, FlixelNetChannel.RELIABLE, hello, 2, 1);
    host.send(1, FlixelNetChannel.UNRELIABLE, hello, 0, 0);
    Recorder.await(host, hostLog, client, clientLog, "data 1 RELIABLE 1,2,3\ndata 1 UNRELIABLE 1,2\n",
        "data 0 RELIABLE 3\ndata 0 UNRELIABLE \n");

    // Sizes on both sides of each way a WebSocket can write a length.
    for (int size : new int[] { 124, 125, 126, 127, 65534, 65535, 65536, 100_000 }) {
      byte[] packet = new byte[size];
      client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, packet, 0, size);
      host.send(1, FlixelNetChannel.RELIABLE, packet, 0, size);
      Recorder.await(host, hostLog, client, clientLog, "data 1 RELIABLE " + size + " bytes\n",
          "data 0 RELIABLE " + size + " bytes\n");
    }
  }

  @Test
  void eachSideLearnsWhenTheOtherLeaves() throws InterruptedException {
    host.listen(0);
    client.connect("127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, new byte[] { 9 }, 0, 1);
    client.disconnect(FlixelNetTransport.HOST);
    Recorder.await(host, hostLog, client, clientLog, "data 1 RELIABLE 9\nclose 1 REMOTE\n", "close 0 LOCAL\n");
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());

    hostLog.take();
    clientLog.take();
    client.connect("ws://127.0.0.1:" + host.getPort() + "/any/path");
    Recorder.await(host, hostLog, client, clientLog, "open 2\n", "open 0\n");
    host.disconnect(2);
    Recorder.await(host, hostLog, client, clientLog, "close 2 LOCAL\n", "close 0 REMOTE\n");
  }

  @Test
  void connectingToNothingIsRefused() throws InterruptedException {
    host.listen(0);
    int port = host.getPort();
    host.close();
    client.connect("ws://127.0.0.1:" + port);
    Recorder.await(host, hostLog, client, clientLog, "close 0 REFUSED\n");
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
  }

  @Test
  void aPlainWebRequestIsTurnedAwayWithoutBecomingAConnection() throws Exception {
    host.listen(0);
    try (Socket socket = new Socket("127.0.0.1", host.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      byte[] answer = new byte[64];
      int read = in.read(answer);
      assertTrue(new String(answer, 0, read, StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 400"));
    }
    host.poll(hostLog);
    assertEquals("", hostLog.take());
  }

  @Test
  void aBrowserStyleFragmentedAndMaskedMessageIsUnderstood() throws Exception {
    host.listen(0);
    try (Socket socket = new Socket("127.0.0.1", host.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write(("GET /game HTTP/1.1\r\nHost: localhost\r\nupgrade: WebSocket\r\nConnection: keep-alive, Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      StringBuilder head = new StringBuilder();
      while (!head.toString().endsWith("\r\n\r\n")) {
        head.append((char) in.read());
      }
      // The key and answer are the worked example from the WebSocket standard.
      assertTrue(head.toString().contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), head.toString());

      // One message in two pieces with a ping in between: [tag 0][7][8], masked with 1,2,3,4.
      out.write(new byte[] { 0x02, (byte) 0x82, 1, 2, 3, 4, 0 ^ 1, 7 ^ 2 });
      out.write(new byte[] { (byte) 0x89, (byte) 0x81, 1, 2, 3, 4, 5 ^ 1 });
      out.write(new byte[] { (byte) 0x80, (byte) 0x81, 1, 2, 3, 4, 8 ^ 1 });
      out.flush();
      // The pong comes back unmasked with the ping's payload.
      assertEquals(0x8A, in.read());
      assertEquals(1, in.read());
      assertEquals(5, in.read());

      Recorder.await(host, hostLog, client, clientLog, "open 1\ndata 1 RELIABLE 7,8\n");
    }
    Recorder.await(host, hostLog, client, clientLog, "close 1 ");
  }

  @Test
  void anUnmaskedFrameFromAClientEndsTheConnection() throws Exception {
    host.listen(0);
    try (Socket socket = new Socket("127.0.0.1", host.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      out.write(new byte[] { (byte) 0x82, 2, 0, 7 });
      out.flush();
      Recorder.await(host, hostLog, client, clientLog, "open 1\nclose 1 ERROR\n");
    }
  }

  @Test
  void aFullHostTurnsNewcomersAway() throws InterruptedException {
    host.setMaxConnections(1);
    host.listen(0);
    client.connect("ws://127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, client, clientLog, "open 1\n", "open 0\n");
    FlixelJvmWebSocketTransport late = new FlixelJvmWebSocketTransport();
    Recorder lateLog = new Recorder();
    late.connect("ws://127.0.0.1:" + host.getPort());
    Recorder.await(host, hostLog, late, lateLog, "close 0 REFUSED\n");
    late.close();
  }

  @Test
  void misuseIsRejectedClearly() {
    assertThrows(IllegalArgumentException.class, () -> client.connect("ws://:80"));
    assertThrows(IllegalArgumentException.class, () -> client.connect("ws://host:notaport"));
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
    host.listen(0);
    assertThrows(IllegalStateException.class, () -> host.listen(0));
    assertThrows(IllegalStateException.class, () -> host.connect("ws://127.0.0.1:1"));
    FlixelJvmWebSocketTransport other = new FlixelJvmWebSocketTransport();
    assertThrows(IllegalStateException.class, () -> other.listen(host.getPort()));
    assertThrows(IllegalArgumentException.class, () -> host.setMaxConnections(0));
    assertThrows(IllegalArgumentException.class,
        () -> host.send(1, FlixelNetChannel.UNRELIABLE, new byte[2000], 0, 2000));
    assertEquals(-1, client.getPort());
  }
}
