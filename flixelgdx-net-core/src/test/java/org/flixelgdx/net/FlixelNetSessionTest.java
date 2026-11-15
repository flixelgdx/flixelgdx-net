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
package org.flixelgdx.net;

import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.TestMessages.Chat;
import org.flixelgdx.net.message.TestMessages.Move;
import org.flixelgdx.net.message.TestMessagesChatNetCodec;
import org.flixelgdx.net.message.TestMessagesEmptyNetCodec;
import org.flixelgdx.net.message.TestMessagesMoveNetCodec;
import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetCloseReason;
import org.flixelgdx.net.transport.FlixelNetLoopback;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetSessionTest {

  private FlixelNetLoopback loopback;
  private FlixelNetSession host;
  private FlixelNetSession client;
  private StringBuilder log;

  @BeforeEach
  void setUp() {
    loopback = new FlixelNetLoopback();
    log = new StringBuilder();
    host = session("host", protocol(1));
    client = session("client", protocol(1));
  }

  @Test
  void clientJoinsAndBothSidesHearAboutIt() {
    host.listen(loopback.getHost(), 7777);
    assertTrue(host.isHost());
    assertEquals(FlixelNetSession.HOST_ID, host.getLocalId());
    client.join(loopback.newClient(), "");
    assertEquals(FlixelNetSessionState.JOINING, client.getState());
    assertNull(client.getHost());

    pump(3);

    assertEquals("host connect 1;client connect 0;", take());
    assertEquals(FlixelNetSessionState.JOINED, client.getState());
    assertEquals(1, client.getLocalId());
    assertEquals(1, host.getPeerCount());
    assertEquals(1, client.getPeerCount());
    assertNotNull(client.getHost());
    assertSame(host.getPeerAt(0), host.getPeer(1));
    assertTrue(host.getPeer(1).isConnected());
    assertNull(host.getPeer(2));
  }

  @Test
  void messagesReachTheirHandlersOnBothSides() {
    join();
    host.on(Move.class, (peer, move) -> log.append("host got move ").append(move.dx).append(" from ")
        .append(peer.getId()).append(';'));
    client.on(Chat.class, (peer, chat) -> log.append("client got '").append(chat.text).append("' from ")
        .append(peer.getId()).append(';'));

    Move move = client.obtain(Move.class);
    move.dx = 1f;
    assertTrue(client.send(move, FlixelNetChannel.UNRELIABLE));
    Chat chat = host.obtain(Chat.class);
    chat.text = "welcome";
    assertTrue(host.send(chat, FlixelNetChannel.RELIABLE));
    pump(2);

    // The host runs first each frame, so its chat is on the wire before the client's move.
    assertEquals("client got 'welcome' from 0;host got move 1.0 from 1;", take());
  }

  @Test
  void sentMessagesAreReused() {
    join();
    Move first = client.obtain(Move.class);
    first.dx = 1f;
    client.send(first, FlixelNetChannel.UNRELIABLE);
    Move second = client.obtain(Move.class);
    assertSame(first, second);
    assertEquals(0f, second.dx);
  }

  @Test
  void manyMessagesShareOnePacket() {
    join();
    int[] moves = { 0 };
    host.on(Move.class, (peer, move) -> moves[0]++);
    long before = host.getPeerAt(0).getBytesReceived();
    for (int i = 0; i < 20; i++) {
      client.send(client.obtain(Move.class), FlixelNetChannel.UNRELIABLE);
    }
    pump(2);
    long bytes = host.getPeerAt(0).getBytesReceived() - before;
    assertEquals(20, moves[0]);
    // Each move is 1 byte of message number plus 3 bytes of fields.
    assertEquals(80, bytes);
  }

  @Test
  void packetsAreSplitWhenTheyWouldBeTooLarge() {
    join();
    int[] chats = { 0 };
    host.on(Chat.class, (peer, chat) -> chats[0]++);
    String text = "x".repeat(500);
    for (int i = 0; i < 6; i++) {
      Chat chat = client.obtain(Chat.class);
      chat.text = text;
      client.send(chat, FlixelNetChannel.UNRELIABLE);
    }
    pump(2);
    assertEquals(6, chats[0], "3000 bytes of chat cannot fit one 1200-byte packet, but all of it arrives");

    Chat huge = client.obtain(Chat.class);
    huge.text = "x".repeat(2000);
    assertThrows(IllegalArgumentException.class, () -> client.send(huge, FlixelNetChannel.UNRELIABLE));
  }

  @Test
  void hostCanSendToOneOrToAllButOne() {
    FlixelNetSession second = session("second", protocol(1));
    host.listen(loopback.getHost(), 0);
    client.join(loopback.newClient(), "");
    second.join(loopback.newClient(), "");
    for (int i = 0; i < 3; i++) {
      loopback.advance(0.01f);
      host.update(0.01f);
      client.update(0.01f);
      second.update(0.01f);
    }
    take();
    client.on(Chat.class, (peer, chat) -> log.append("client:").append(chat.text).append(';'));
    second.on(Chat.class, (peer, chat) -> log.append("second:").append(chat.text).append(';'));

    Chat toOne = host.obtain(Chat.class);
    toOne.text = "only you";
    host.sendTo(host.getPeer(2), toOne, FlixelNetChannel.RELIABLE);
    Chat toRest = host.obtain(Chat.class);
    toRest.text = "not second";
    host.sendExcept(host.getPeer(2), toRest, FlixelNetChannel.RELIABLE);
    Chat toAll = host.obtain(Chat.class);
    toAll.text = "all";
    host.send(toAll, FlixelNetChannel.RELIABLE);
    host.update(0.01f);
    client.update(0.01f);
    second.update(0.01f);

    assertEquals("client:not second;client:all;second:only you;second:all;", take());
  }

  @Test
  void differentProtocolIsTurnedAway() {
    FlixelNetSession old = session("old", protocol(2));
    host.listen(loopback.getHost(), 0);
    old.join(loopback.newClient(), "");
    for (int i = 0; i < 3; i++) {
      host.update(0.01f);
      old.update(0.01f);
    }
    assertEquals("old failed VERSION_MISMATCH;", take());
    assertEquals(FlixelNetSessionState.IDLE, old.getState());
    assertEquals(0, host.getPeerCount());
    assertEquals(0, loopback.getClientCount());
  }

  @Test
  void fullHostTurnsNewPeersAway() {
    host.setMaxPeers(1);
    join();
    FlixelNetSession late = session("late", protocol(1));
    late.join(loopback.newClient(), "");
    for (int i = 0; i < 3; i++) {
      host.update(0.01f);
      late.update(0.01f);
    }
    assertEquals("late failed FULL;", take());
    assertEquals(1, host.getPeerCount());
  }

  @Test
  void joiningWithNoHostFails() {
    client.join(loopback.newClient(), "");
    client.update(0.01f);
    assertEquals("client failed REFUSED;", take());
    assertEquals(FlixelNetSessionState.IDLE, client.getState());
  }

  @Test
  void joiningAHostThatNeverAnswersTimesOut() {
    // A bare transport that accepts the connection but never says a word.
    loopback.getHost().listen(0);
    client.setTimeout(2f);
    client.join(loopback.newClient(), "");
    for (int i = 0; i < 25; i++) {
      client.update(0.1f);
    }
    assertEquals("client failed TIMEOUT;", take());
  }

  @Test
  void clientLeavingIsReportedOnBothSides() {
    join();
    client.close();
    assertEquals("client disconnect 0 LEFT;", take());
    assertEquals(FlixelNetSessionState.IDLE, client.getState());
    assertEquals(-1, client.getLocalId());
    host.update(0.01f);
    assertEquals("host disconnect 1 LEFT;", take());
    assertEquals(0, host.getPeerCount());
    assertEquals(0, loopback.getClientCount());
  }

  @Test
  void hostShuttingDownIsReportedOnBothSides() {
    join();
    host.close();
    assertEquals("host disconnect 1 SHUTDOWN;", take());
    client.update(0.01f);
    assertEquals("client disconnect 0 SHUTDOWN;", take());
    assertEquals(FlixelNetSessionState.IDLE, client.getState());
  }

  @Test
  void kickedPeerLearnsWhy() {
    join();
    FlixelNetPeer peer = host.getPeer(1);
    host.kick(peer);
    assertEquals("host disconnect 1 KICKED;", take());
    assertFalse(peer.isConnected());
    assertEquals(FlixelNetDisconnectReason.KICKED, peer.getDisconnectReason());
    client.update(0.01f);
    assertEquals("client disconnect 0 KICKED;", take());
    assertThrows(IllegalStateException.class, () -> client.kick(peer));
  }

  @Test
  void silentPeerTimesOut() {
    host.setTimeout(3f);
    join();
    // Only the host keeps running; the client has frozen.
    for (int i = 0; i < 40; i++) {
      loopback.advance(0.1f);
      host.update(0.1f);
    }
    assertEquals("host disconnect 1 TIMEOUT;", take());
  }

  @Test
  void pingsKeepAQuietSessionAliveAndMeasureLag() {
    host.setTimeout(3f);
    client.setTimeout(3f);
    loopback.setLatency(0.05f);
    host.listen(loopback.getHost(), 0);
    client.join(loopback.newClient(), "");
    for (int i = 0; i < 600; i++) {
      loopback.advance(1f / 60f);
      host.update(1f / 60f);
      client.update(1f / 60f);
    }
    assertEquals("host connect 1;client connect 0;", take());
    // 50 ms each way, plus up to a frame of waiting on each side before the next poll.
    assertEquals(100, host.getPeer(1).getPing(), 40);
    assertEquals(100, client.getHost().getPing(), 40);
  }

  @Test
  void sendRateHoldsMessagesUntilTheNextTick() {
    join();
    int[] moves = { 0 };
    host.on(Move.class, (peer, move) -> moves[0]++);
    client.setSendRate(10f);
    assertEquals(10f, client.getSendRate(), 0.001f);
    client.send(client.obtain(Move.class), FlixelNetChannel.UNRELIABLE);
    client.update(0.02f);
    host.update(0.02f);
    assertEquals(0, moves[0]);
    client.update(0.09f);
    host.update(0.09f);
    assertEquals(1, moves[0]);

    client.send(client.obtain(Move.class), FlixelNetChannel.UNRELIABLE);
    client.flush();
    host.update(0.001f);
    assertEquals(2, moves[0], "flush() does not wait for the tick");
  }

  @Test
  void garbageFromAPeerNeverCrashesTheHostAndGetsItDropped() {
    host.listen(loopback.getHost(), 0);
    FlixelNetTransport raw = loopback.newClient();
    raw.connect("");
    FlixelNetTransportListener ignore = new FlixelNetTransportListener() {
      @Override
      public void onOpen(int connection) {}

      @Override
      public void onClose(int connection, FlixelNetCloseReason reason) {
        log.append("raw closed;");
      }

      @Override
      public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {}
    };
    raw.poll(ignore);
    byte[] junk = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F };
    raw.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, junk, 0, junk.length);
    host.update(0.01f);
    raw.poll(ignore);
    assertEquals("raw closed;", take());
    assertEquals(0, host.getPeerCount());
  }

  @Test
  void connectedPeerSendingGarbageIsDroppedAfterAFewTries() {
    join();
    FlixelNetPeer hostSide = client.getHost();
    byte[] junk = { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80 };
    for (int i = 0; i < 8; i++) {
      hostSide.getTransport().send(hostSide.getConnection(), FlixelNetChannel.RELIABLE, junk, 0, junk.length);
    }
    host.update(0.01f);
    assertEquals("host disconnect 1 ERROR;", take());
  }

  @Test
  void userMessagesBeforeTheHandshakeAreIgnored() {
    host.listen(loopback.getHost(), 0);
    host.on(Move.class, (peer, move) -> log.append("move;"));
    FlixelNetTransport raw = loopback.newClient();
    raw.connect("");
    raw.poll(new FlixelNetTransportListener() {
      @Override
      public void onOpen(int connection) {}

      @Override
      public void onClose(int connection, FlixelNetCloseReason reason) {}

      @Override
      public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {}
    });
    // Message number 8 is the first user message; a peer must say hello before using it.
    byte[] early = { 9, 0, 0, 0 };
    raw.send(FlixelNetTransport.HOST, FlixelNetChannel.RELIABLE, early, 0, early.length);
    host.update(0.01f);
    assertEquals("", take());
    assertEquals(0, host.getPeerCount());
  }

  @Test
  void closingFromInsideAHandlerIsSafe() {
    join();
    host.on(Move.class, (peer, move) -> {
      log.append("move;");
      host.close();
    });
    client.send(client.obtain(Move.class), FlixelNetChannel.RELIABLE);
    client.send(client.obtain(Move.class), FlixelNetChannel.RELIABLE);
    pump(2);
    assertEquals("move;host disconnect 1 SHUTDOWN;client disconnect 0 SHUTDOWN;", take());
  }

  @Test
  void handlersCanBeRemovedAndSessionsReused() {
    join();
    FlixelNetHandler<Move> handler = (peer, move) -> log.append("move;");
    host.on(Move.class, handler);
    assertTrue(host.off(Move.class, handler));
    assertFalse(host.off(Move.class, handler));
    client.send(client.obtain(Move.class), FlixelNetChannel.RELIABLE);
    pump(2);
    assertEquals("", take());

    client.close();
    host.update(0.01f);
    take();
    client.join(loopback.newClient(), "");
    pump(3);
    assertEquals("host connect 2;client connect 0;", take());
    assertEquals(2, client.getLocalId());
  }

  @Test
  void misuseIsRejectedClearly() {
    join();
    assertThrows(IllegalStateException.class, () -> client.listen(loopback.getHost(), 0));
    assertThrows(IllegalStateException.class, () -> client.join(loopback.newClient(), ""));
    assertThrows(IllegalStateException.class, () -> host.join(loopback.newClient(), ""));
    assertThrows(IllegalArgumentException.class, () -> host.send("not a message", FlixelNetChannel.RELIABLE));
    assertThrows(IllegalArgumentException.class, () -> host.setTimeout(0f));
    assertThrows(IllegalArgumentException.class, () -> host.setPingInterval(-1f));
    assertThrows(IllegalArgumentException.class, () -> host.setMaxPeers(0));
    assertFalse(new FlixelNetSession(protocol(1)).send(new Move(), FlixelNetChannel.RELIABLE));
  }

  private static FlixelNetProtocol protocol(int version) {
    return new FlixelNetProtocol("test", version)
        .add(TestMessagesMoveNetCodec.INSTANCE)
        .add(TestMessagesChatNetCodec.INSTANCE)
        .add(TestMessagesEmptyNetCodec.INSTANCE);
  }

  private FlixelNetSession session(String name, FlixelNetProtocol protocol) {
    FlixelNetSession session = new FlixelNetSession(protocol);
    session.onConnect.add(peer -> log.append(name).append(" connect ").append(peer.getId()).append(';'));
    session.onDisconnect.add(peer -> log.append(name).append(" disconnect ").append(peer.getId()).append(' ')
        .append(peer.getDisconnectReason()).append(';'));
    session.onJoinFailed.add(reason -> log.append(name).append(" failed ").append(reason).append(';'));
    return session;
  }

  private void join() {
    host.listen(loopback.getHost(), 0);
    client.join(loopback.newClient(), "");
    pump(3);
    take();
  }

  private void pump(int frames) {
    for (int i = 0; i < frames; i++) {
      loopback.advance(0.01f);
      host.update(0.01f);
      client.update(0.01f);
    }
  }

  private String take() {
    String text = log.toString();
    log.setLength(0);
    return text;
  }
}
