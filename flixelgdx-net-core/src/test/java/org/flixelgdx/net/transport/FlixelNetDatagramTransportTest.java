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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetDatagramTransportTest {

  private static final int PORT = 7777;
  private static final byte[] HELLO = { 1, 2, 3 };

  private FakeNetwork network;
  private FlixelNetDatagramTransport<Integer> host;
  private FlixelNetDatagramTransport<Integer> client;
  private RecordingListener hostLog;
  private RecordingListener clientLog;

  @BeforeEach
  void setUp() {
    network = new FakeNetwork();
    host = network.transport(111L);
    client = network.transport(222L);
    hostLog = new RecordingListener();
    clientLog = new RecordingListener();
  }

  @Test
  void clientJoinsAfterTheHandshake() {
    host.listen(PORT);
    client.connect("7777");
    assertEquals(FlixelNetTransportState.LISTENING, host.getState());
    assertEquals(FlixelNetTransportState.CONNECTING, client.getState());
    assertFalse(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 3));

    step(4);

    assertEquals("open 1\n", hostLog.take());
    assertEquals("open 0\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CONNECTED, client.getState());
    assertEquals(1, host.getConnectionCount());
  }

  @Test
  void joiningSurvivesHeavyLoss() {
    network.loss = 0.6f;
    host.listen(PORT);
    client.connect("7777");
    step(400);
    assertEquals("open 1\n", hostLog.take());
    assertEquals("open 0\n", clientLog.take());
  }

  @Test
  void joiningWithNobodyListeningGivesUp() {
    client.setConnectTimeout(2f);
    client.connect("7777");
    step(150);
    assertEquals("", clientLog.take());
    step(100);
    assertEquals("close 0 REFUSED\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());

    host.listen(PORT);
    client.connect("7777");
    step(4);
    assertEquals("open 0\n", clientLog.take(), "the same transport can try again");
  }

  @Test
  void packetsTravelBothWaysOnBothChannels() {
    join();
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 3));
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, HELLO, 1, 2));
    assertTrue(host.send(1, FlixelNetChannel.RELIABLE, HELLO, 2, 1));
    assertTrue(host.send(1, FlixelNetChannel.RELIABLE, HELLO, 0, 0));
    step(2);
    assertEquals("data 1 RELIABLE 1,2,3\ndata 1 UNRELIABLE 2,3\n", hostLog.take());
    assertEquals("data 0 RELIABLE 3\ndata 0 RELIABLE \n", clientLog.take());
  }

  @Test
  void reliableMessagesArriveOnceAndInOrderThroughATerribleNetwork() {
    join();
    network.loss = 0.3f;
    network.duplicate = 0.2f;
    network.delay = 0.03f;
    network.jitter = 0.08f;

    List<byte[]> toHost = new ArrayList<>();
    List<byte[]> toClient = new ArrayList<>();
    Collector hostGot = new Collector();
    Collector clientGot = new Collector();
    for (int frame = 0; frame < 3000 && (hostGot.reliable.size() < 300 || clientGot.reliable.size() < 300); frame++) {
      if (toHost.size() < 300) {
        // Sizes from empty up to several packets' worth, so some messages have to be split.
        byte[] up = payload(toHost.size(), (int) (network.random() * 4000));
        byte[] down = payload(toClient.size() + 7, (int) (network.random() * 4000));
        toHost.add(up);
        toClient.add(down);
        assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, up, 0, up.length));
        assertTrue(host.send(1, FlixelNetChannel.RELIABLE, down, 0, down.length));
      }
      network.now += 0.01;
      host.poll(hostGot);
      client.poll(clientGot);
    }

    assertEquals(300, hostGot.reliable.size());
    assertEquals(300, clientGot.reliable.size());
    for (int i = 0; i < 300; i++) {
      assertArrayEquals(toHost.get(i), hostGot.reliable.get(i), "message " + i + " to the host");
      assertArrayEquals(toClient.get(i), clientGot.reliable.get(i), "message " + i + " to the client");
    }
    assertEquals(0, hostGot.closes + clientGot.closes);
  }

  @Test
  void unreliableMessagesNeverRepeatOrGoBackward() {
    join();
    network.loss = 0.2f;
    network.duplicate = 0.3f;
    network.jitter = 0.05f;
    Collector got = new Collector();
    byte[] packet = new byte[2];
    for (int i = 0; i < 1000; i++) {
      packet[0] = (byte) (i >> 8);
      packet[1] = (byte) i;
      client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, packet, 0, 2);
      network.now += 0.005;
      host.poll(got);
      client.poll(clientLog);
    }
    int last = -1;
    for (byte[] data : got.unreliable) {
      int number = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
      assertTrue(number > last, "message " + number + " arrived after " + last);
      last = number;
    }
    assertTrue(got.unreliable.size() > 200, "plenty of messages should still arrive, got " + got.unreliable.size());
    assertTrue(got.unreliable.size() < 1000, "some messages should have been lost");
  }

  @Test
  void largestReliableMessageArrivesWholeAndLargerIsRefused() {
    join();
    byte[] big = payload(3, FlixelNetDatagramTransport.MAX_RELIABLE_SIZE);
    Collector got = new Collector();
    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, big, 0, big.length));
    for (int i = 0; i < 20 && got.reliable.isEmpty(); i++) {
      network.now += 0.01;
      host.poll(got);
      client.poll(clientLog);
    }
    assertEquals(1, got.reliable.size());
    assertArrayEquals(big, got.reliable.get(0));

    byte[] tooBig = new byte[FlixelNetDatagramTransport.MAX_RELIABLE_SIZE + 1];
    assertThrows(IllegalArgumentException.class,
        () -> client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, tooBig, 0, tooBig.length));
    byte[] tooBigUnreliable = new byte[FlixelNetDatagramTransport.MAX_UNRELIABLE_SIZE + 1];
    assertThrows(IllegalArgumentException.class,
        () -> client.send(FlixelNetTransport.HOST, FlixelNetChannel.UNRELIABLE, tooBigUnreliable, 0,
            tooBigUnreliable.length));
    assertEquals(FlixelNetDatagramTransport.MAX_UNRELIABLE_SIZE, client.getMaxPacketSize(FlixelNetChannel.UNRELIABLE));
  }

  @Test
  void aBurstLargerThanTheSendWindowStillArrivesInOrder() {
    join();
    Collector got = new Collector();
    byte[] packet = new byte[1000];
    for (int i = 0; i < 700; i++) {
      packet[0] = (byte) (i >> 8);
      packet[1] = (byte) i;
      assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, packet, 0, packet.length));
    }
    for (int i = 0; i < 200 && got.reliable.size() < 700; i++) {
      network.now += 0.01;
      host.poll(got);
      client.poll(clientLog);
    }
    assertEquals(700, got.reliable.size());
    for (int i = 0; i < 700; i++) {
      byte[] data = got.reliable.get(i);
      assertEquals(i, ((data[0] & 0xFF) << 8) | (data[1] & 0xFF));
    }
  }

  @Test
  void aPeerThatNeverReadsIsEventuallyCutOff() {
    join();
    byte[] packet = new byte[60000];
    boolean accepted = true;
    for (int i = 0; i < 200 && accepted; i++) {
      // The host is never polled, so nothing sent here is ever confirmed.
      accepted = client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, packet, 0, packet.length);
    }
    assertFalse(accepted, "sending cannot pile up without limit");
    client.poll(clientLog);
    assertEquals("close 0 ERROR\n", clientLog.take());
  }

  @Test
  void eachSideLearnsWhenTheOtherLeaves() {
    join();
    client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 1);
    client.disconnect(FlixelNetTransport.HOST);
    assertFalse(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 1));
    step(2);
    assertEquals("data 1 RELIABLE 1\nclose 1 REMOTE\n", hostLog.take());
    assertEquals("close 0 LOCAL\n", clientLog.take());
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
    assertEquals(0, host.getConnectionCount());

    client.connect("7777");
    step(4);
    assertEquals("open 2\n", hostLog.take());
    host.disconnect(2);
    step(2);
    assertEquals("close 2 LOCAL\n", hostLog.take());
    assertEquals("open 0\nclose 0 REMOTE\n", clientLog.take());
  }

  @Test
  void closeIsSilentLocallyButToldToTheOtherSide() {
    join();
    host.close();
    assertEquals(FlixelNetTransportState.CLOSED, host.getState());
    step(2);
    assertEquals("", hostLog.take());
    assertEquals("close 0 REMOTE\n", clientLog.take());
  }

  @Test
  void silenceEndsTheConnectionOnBothSides() {
    host.setTimeout(3f);
    client.setTimeout(3f);
    join();
    network.loss = 1f;
    step(350);
    assertEquals("close 1 TIMEOUT\n", hostLog.take());
    assertEquals("close 0 TIMEOUT\n", clientLog.take());
  }

  @Test
  void anIdleConnectionStaysOpen() {
    host.setTimeout(3f);
    client.setTimeout(3f);
    join();
    step(1000);
    assertEquals("", hostLog.take());
    assertEquals("", clientLog.take());
    assertEquals(FlixelNetTransportState.CONNECTED, client.getState());
  }

  @Test
  void aFullHostRefusesNewcomers() {
    host.setMaxConnections(1);
    join();
    FlixelNetDatagramTransport<Integer> late = network.transport(333L);
    RecordingListener lateLog = new RecordingListener();
    late.connect("7777");
    for (int i = 0; i < 4; i++) {
      network.now += 0.01;
      host.poll(hostLog);
      late.poll(lateLog);
    }
    assertEquals("close 0 REFUSED\n", lateLog.take());
    assertEquals("", hostLog.take());
  }

  @Test
  void garbageAndForgeriesAreIgnored() {
    join();
    FakeNetwork.FakeSocket stranger = network.socket();
    stranger.open(9000);
    // Nonsense, a short join request, a join answer with a made-up cookie, and data and a
    // disconnect that claim to come from the real client but lack its secret number.
    stranger.forge(9000, PORT);
    stranger.forge(9000, PORT, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
    stranger.forge(9000, PORT, (byte) 1, (byte) 0x44, (byte) 0x55, (byte) 0x58, (byte) 0x46);
    stranger.forge(9000, PORT, (byte) 3, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7,
        (byte) 8, (byte) 9, (byte) 10, (byte) 11, (byte) 12, (byte) 13, (byte) 14, (byte) 15, (byte) 16);
    int clientPort = ((FakeNetwork.FakeSocket) client.getSocket()).port;
    stranger.forge(clientPort, PORT, (byte) 6, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 1,
        (byte) 0, (byte) 9);
    stranger.forge(clientPort, PORT, (byte) 7, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0);
    step(3);
    assertEquals("", hostLog.take());
    assertEquals(1, host.getConnectionCount());

    assertTrue(client.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, HELLO, 0, 3));
    step(2);
    assertEquals("data 1 RELIABLE 1,2,3\n", hostLog.take(), "the real connection is unharmed");
  }

  @Test
  void aJoinRequestIsNeverAnsweredWithMoreThanItSent() {
    host.listen(PORT);
    FakeNetwork.FakeSocket stranger = network.socket();
    stranger.open(9000);
    byte[] request = new byte[40];
    request[0] = 1;
    request[1] = 0x44;
    request[2] = 0x55;
    request[3] = 0x58;
    request[4] = 0x46;
    stranger.forge(9000, PORT, request);
    network.now += 0.01;
    host.poll(hostLog);
    byte[] answer = new byte[1200];
    int length = stranger.receive(answer);
    assertTrue(length > 0 && length < request.length, "answer of " + length + " bytes to a 40-byte request");
    assertEquals(0, host.getConnectionCount(), "asking alone does not create a connection");
  }

  @Test
  void misuseIsRejectedClearly() {
    assertThrows(IllegalArgumentException.class, () -> client.connect("not a port"));
    assertEquals(FlixelNetTransportState.CLOSED, client.getState());
    host.listen(PORT);
    assertThrows(IllegalStateException.class, () -> host.listen(PORT));
    assertThrows(IllegalStateException.class, () -> host.connect("7777"));
    assertThrows(IllegalStateException.class, () -> network.transport(1L).listen(PORT));
    assertThrows(IllegalArgumentException.class, () -> host.setTimeout(0f));
    assertThrows(IllegalArgumentException.class, () -> host.setConnectTimeout(0f));
    assertThrows(IllegalArgumentException.class, () -> host.setMaxConnections(0));
    assertFalse(host.send(5, FlixelNetChannel.RELIABLE, HELLO, 0, 1));
    host.disconnect(5);
  }

  private void join() {
    host.listen(PORT);
    client.connect("7777");
    step(4);
    hostLog.take();
    clientLog.take();
  }

  private void step(int frames) {
    for (int i = 0; i < frames; i++) {
      network.now += 0.01;
      host.poll(hostLog);
      client.poll(clientLog);
    }
  }

  private static byte[] payload(int seed, int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) (seed * 31 + i * 7);
    }
    return data;
  }

  /** Keeps a copy of every packet delivered, by channel. */
  private static final class Collector implements FlixelNetTransportListener {

    final List<byte[]> reliable = new ArrayList<>();
    final List<byte[]> unreliable = new ArrayList<>();
    int closes;

    @Override
    public void onOpen(int connection) {}

    @Override
    public void onClose(int connection, FlixelNetCloseReason reason) {
      closes++;
    }

    @Override
    public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
      byte[] copy = new byte[length];
      System.arraycopy(data, offset, copy, 0, length);
      (channel == FlixelNetChannel.RELIABLE ? reliable : unreliable).add(copy);
    }
  }
}
