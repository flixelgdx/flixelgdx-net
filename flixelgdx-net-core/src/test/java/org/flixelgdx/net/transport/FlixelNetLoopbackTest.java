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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetLoopbackTest {

  private static final byte[] HELLO = { 1, 2, 3 };

  private FlixelNetLoopback loopback;
  private FlixelNetTransport host;
  private FlixelNetTransport client;
  private RecordingListener hostLog;
  private RecordingListener clientLog;

  @BeforeEach
  void setUp() {
    loopback = new FlixelNetLoopback();
    host = loopback.getHost();
    client = loopback.newClient();
    hostLog = new RecordingListener();
    clientLog = new RecordingListener();
  }

  @Test
  void clientJoinsAListeningHost() {
    host.listen(7777);
    assertEquals(FlixelNetTransportState.LISTENING, host.getState());
    client.connect("anything");
    assertEquals(FlixelNetTransportState.CONNECTING, client.getState());
    assertFalse(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 3),
        "nothing can be sent before the connection is reported open");

    host.poll(hostLog);
    client.poll(clientLog);

    assertEquals("open 1\n", hostLog.take());
    assertEquals("open 0\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CONNECTED, client.getState());
    assertEquals(1, loopback.getClientCount());
    assertSame(host, loopback.getHost());
  }

  @Test
  void connectingWithNoHostIsRefused() {
    client.connect("");
    client.poll(clientLog);
    assertEquals("close 0 REFUSED\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());

    // A refused client can simply try again once a host exists.
    host.listen(0);
    client.connect("");
    client.poll(clientLog);
    assertEquals("open 0\n", clientLog.take());
  }

  @Test
  void packetsTravelBothWaysOnBothChannels() {
    join();
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 3));
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, HELLO, 1, 2));
    assertTrue(host.send(1, FlixelNetChannel.RELIABLE, HELLO, 2, 1));

    host.poll(hostLog);
    client.poll(clientLog);

    assertEquals("data 1 RELIABLE 1,2,3\ndata 1 UNRELIABLE 2,3\n", hostLog.take());
    assertEquals("data 0 RELIABLE 3\n", clientLog.take());
  }

  @Test
  void sentBytesAreCopiedSoTheArrayCanBeReused() {
    join();
    byte[] scratch = { 5 };
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, scratch, 0, 1);
    scratch[0] = 6;
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, scratch, 0, 1);
    host.poll(hostLog);
    assertEquals("data 1 RELIABLE 5\ndata 1 RELIABLE 6\n", hostLog.take());
  }

  @Test
  void eachClientGetsItsOwnConnection() {
    FlixelNetTransport second = loopback.newClient();
    RecordingListener secondLog = new RecordingListener();
    host.listen(0);
    client.connect("");
    second.connect("");
    host.poll(hostLog);
    client.poll(clientLog);
    second.poll(secondLog);
    assertEquals("open 1\nopen 2\n", hostLog.take());
    clientLog.take();
    secondLog.take();

    host.send(2, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    second.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 1, 1);
    host.poll(hostLog);
    client.poll(clientLog);
    second.poll(secondLog);

    assertEquals("data 2 RELIABLE 2\n", hostLog.take());
    assertEquals("", clientLog.take());
    assertEquals("data 0 RELIABLE 1\n", secondLog.take());
    assertEquals(2, loopback.getClientCount());
  }

  @Test
  void hostCanKickAClient() {
    join();
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    host.disconnect(1);
    host.disconnect(1);

    assertFalse(host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 1));
    assertFalse(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 1));
    host.poll(hostLog);
    client.poll(clientLog);

    assertEquals("data 1 RELIABLE 1\nclose 1 LOCAL\n", hostLog.take(), "packets sent before the kick still arrive");
    assertEquals("close 0 REMOTE\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
    assertEquals(0, loopback.getClientCount());
  }

  @Test
  void clientCanLeaveAndRejoinWithANewConnection() {
    join();
    client.disconnect(FlixelNetTransport.HOST);
    host.poll(hostLog);
    client.poll(clientLog);
    assertEquals("close 1 REMOTE\n", hostLog.take());
    assertEquals("close 0 LOCAL\n", clientLog.take());

    client.connect("");
    host.poll(hostLog);
    client.poll(clientLog);
    assertEquals("open 2\n", hostLog.take());
    assertEquals("open 0\n", clientLog.take());
    assertFalse(host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 1), "the old connection id stays dead");
    assertTrue(host.send(2, FlixelNetChannel.RELIABLE, HELLO, 0, 1));
  }

  @Test
  void closeIsSilentLocallyButToldToTheOtherSide() {
    join();
    host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    client.close();
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
    client.poll(clientLog);
    host.poll(hostLog);
    assertEquals("", clientLog.take());
    assertEquals("close 1 REMOTE\n", hostLog.take());

    client.connect("");
    host.poll(hostLog);
    client.poll(clientLog);
    hostLog.take();
    clientLog.take();

    host.close();
    assertEquals(FlixelNetTransportState.CLOSED, host.getState());
    host.poll(hostLog);
    client.poll(clientLog);
    assertEquals("", hostLog.take());
    assertEquals("close 0 REMOTE\n", clientLog.take());
    assertEquals(0, loopback.getClientCount());
  }

  @Test
  void closingFromInsideAListenerIsSafe() {
    join();
    host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    int[] packets = { 0 };
    client.poll(new FlixelNetTransportListener() {
      @Override
      public void onOpen(int connection) {}

      @Override
      public void onClose(int connection, FlixelNetCloseReason reason) {}

      @Override
      public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
        packets[0]++;
        client.close();
      }
    });
    assertEquals(1, packets[0]);
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
  }

  @Test
  void latencyHoldsPacketsUntilTheClockPassesThem() {
    loopback.setLatency(0.1f);
    host.listen(0);
    client.connect("");
    host.poll(hostLog);
    assertEquals("", hostLog.take(), "even the join is delayed");

    loopback.advance(0.1f);
    host.poll(hostLog);
    client.poll(clientLog);
    assertEquals("open 1\n", hostLog.take());
    assertEquals("open 0\n", clientLog.take());

    client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, HELLO, 0, 1);
    loopback.advance(0.05f);
    host.poll(hostLog);
    assertEquals("", hostLog.take());
    loopback.advance(0.06f);
    host.poll(hostLog);
    assertEquals("data 1 UNRELIABLE 1\n", hostLog.take());
  }

  @Test
  void lossOnlyAffectsTheUnreliableChannel() {
    join();
    loopback.setLoss(1f);
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, HELLO, 0, 1),
        "a lost packet still counts as sent");
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 1, 1);
    host.poll(hostLog);
    assertEquals("data 1 RELIABLE 2\n", hostLog.take());
  }

  @Test
  void partialLossIsRepeatableForAGivenSeed() {
    join();
    loopback.setLoss(0.5f);
    String first = sendHundredUnreliable(1234);
    String second = sendHundredUnreliable(1234);
    assertEquals(first, second);

    int arrived = first.split("\n").length;
    assertTrue(arrived > 25 && arrived < 75, "about half should arrive, got " + arrived);
  }

  @Test
  void oversizedPacketsAndWrongRolesAreRejected() {
    join();
    byte[] big = new byte[FlixelNetLoopback.MAX_UNRELIABLE_SIZE + 1];
    assertEquals(FlixelNetLoopback.MAX_UNRELIABLE_SIZE, host.getMaxPacketSize(FlixelNetChannel.UNRELIABLE));
    assertThrows(IllegalArgumentException.class, () -> host.send(1, FlixelNetChannel.UNRELIABLE, big, 0, big.length));
    assertTrue(host.send(1, FlixelNetChannel.RELIABLE, big, 0, big.length));

    assertThrows(IllegalStateException.class, () -> host.connect(""));
    assertThrows(IllegalStateException.class, () -> host.listen(0));
    assertThrows(IllegalStateException.class, () -> client.listen(0));
    assertThrows(IllegalStateException.class, () -> client.connect(""));
    assertThrows(IllegalArgumentException.class, () -> loopback.setLoss(1.5f));
    assertThrows(IllegalArgumentException.class, () -> loopback.setLatency(-1f));
  }

  private void join() {
    host.listen(0);
    client.connect("");
    host.poll(hostLog);
    client.poll(clientLog);
    hostLog.take();
    clientLog.take();
  }

  private String sendHundredUnreliable(int seed) {
    loopback.setSeed(seed);
    byte[] packet = new byte[1];
    for (int i = 0; i < 100; i++) {
      packet[0] = (byte) i;
      client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, packet, 0, 1);
    }
    host.poll(hostLog);
    return hostLog.take();
  }
}
