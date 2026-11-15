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
package org.flixelgdx.net.message;

import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetException;
import org.flixelgdx.net.message.TestMessages.Chat;
import org.flixelgdx.net.message.TestMessages.Empty;
import org.flixelgdx.net.message.TestMessages.Everything;
import org.flixelgdx.net.message.TestMessages.Move;
import org.flixelgdx.net.message.TestMessages.Point;
import org.flixelgdx.net.message.TestMessages.Team;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetCodecTest {

  private final FlixelNetBuffer buffer = new FlixelNetBuffer();

  @Test
  void smallMessageIsPackedTightly() {
    Move move = new Move();
    move.dx = 1f;
    move.dy = -1f;
    move.jump = true;
    TestMessagesMoveNetCodec.INSTANCE.write(move, buffer);
    assertEquals(3, buffer.getSize(), "two 8-bit floats and two booleans sharing a byte");

    Move back = TestMessagesMoveNetCodec.INSTANCE.create();
    TestMessagesMoveNetCodec.INSTANCE.read(back, buffer);
    assertEquals(1f, back.dx);
    assertEquals(-1f, back.dy);
    assertTrue(back.jump);
    assertFalse(back.crouch);
  }

  @Test
  void everyFieldTypeRoundTrips() {
    Everything sent = new Everything();
    sent.id = -77;
    sent.flag = true;
    sent.tiny = -8;
    sent.small = 12345;
    sent.letter = 'Q';
    sent.number = -123456;
    sent.big = Long.MIN_VALUE;
    sent.real = 1.5f;
    sent.precise = Math.PI;
    sent.text = "hello";
    sent.name.set("final name");
    sent.team = Team.BLUE;
    sent.spawn.x = 640f;
    sent.spawn.y = 360f;
    sent.target = new Point();
    sent.target.x = 1280f;
    sent.ints = new int[] { 1, -2, 300000 };
    sent.bytes = new byte[] { 1, 2, 3 };
    sent.flags = new boolean[] { true, false, true };
    sent.floats = new float[] { 0.5f };
    sent.longs = new long[] { Long.MAX_VALUE };
    sent.chars = new char[] { 'a', 'b' };
    sent.health = 100;
    sent.offset = -5;
    sent.oneBit = 1001;
    sent.full = Integer.MIN_VALUE;

    TestMessagesEverythingNetCodec codec = TestMessagesEverythingNetCodec.INSTANCE;
    codec.write(sent, buffer);
    Everything got = codec.create();
    codec.read(got, buffer);

    assertEquals(0, buffer.remaining());
    assertEquals(-77, got.id);
    assertTrue(got.flag);
    assertEquals(-8, got.tiny);
    assertEquals(12345, got.small);
    assertEquals('Q', got.letter);
    assertEquals(-123456, got.number);
    assertEquals(Long.MIN_VALUE, got.big);
    assertEquals(1.5f, got.real);
    assertEquals(Math.PI, got.precise);
    assertEquals("hello", got.text);
    assertEquals("final name", got.name.toString());
    assertNotNull(got.nick);
    assertEquals("", got.nick.toString(), "a null FlixelString arrives empty");
    assertEquals(Team.BLUE, got.team);
    assertEquals(640f, got.spawn.x, 0.02f);
    assertEquals(360f, got.spawn.y, 0.02f);
    assertEquals(1280f, got.target.x);
    assertArrayEquals(sent.ints, got.ints);
    assertArrayEquals(sent.bytes, got.bytes);
    assertArrayEquals(sent.flags, got.flags);
    assertArrayEquals(sent.floats, got.floats);
    assertArrayEquals(sent.longs, got.longs);
    assertArrayEquals(sent.chars, got.chars);
    assertEquals(100, got.health);
    assertEquals(-5, got.offset);
    assertEquals(1001, got.oneBit);
    assertEquals(Integer.MIN_VALUE, got.full);
    assertEquals(6, got.ignoredTransient, "transient fields are left alone");
  }

  @Test
  void rangedIntegersAreClampedOnBothEnds() {
    Everything sent = new Everything();
    sent.target = new Point();
    sent.health = 500;
    sent.offset = 100;
    TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer);
    Everything got = new Everything();
    TestMessagesEverythingNetCodec.INSTANCE.read(got, buffer);
    assertEquals(100, got.health);
    assertEquals(5, got.offset);
    assertEquals(1000, got.oneBit, "a value below the range moves up to min");
  }

  @Test
  void nullsBecomeEmptyValues() {
    Everything sent = new Everything();
    sent.target = new Point();
    TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer);
    Everything got = new Everything();
    TestMessagesEverythingNetCodec.INSTANCE.read(got, buffer);
    assertEquals("", got.text);
    assertEquals(Team.RED, got.team, "a null enum is sent as the first constant");
    assertEquals(0, got.ints.length);
  }

  @Test
  void nullNestedMessageIsRefusedWithAClearError() {
    Everything sent = new Everything();
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer));
    assertTrue(e.getMessage().contains("target"));
  }

  @Test
  void arraysAreReusedWhenTheLengthMatches() {
    Everything sent = new Everything();
    sent.target = new Point();
    sent.ints = new int[] { 7, 8 };
    TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer);
    Everything got = new Everything();
    int[] existing = new int[2];
    got.ints = existing;
    TestMessagesEverythingNetCodec.INSTANCE.read(got, buffer);
    assertSame(existing, got.ints);
    assertArrayEquals(new int[] { 7, 8 }, got.ints);
  }

  @Test
  void hostileArrayLengthIsRejectedBeforeAllocating() {
    // id, flag, tiny, small, letter, number, big, real, precise, text, name, nick, team, spawn,
    // target, then the length of ints.
    Everything sent = new Everything();
    sent.target = new Point();
    TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer);
    byte[] bytes = buffer.toArray();
    FlixelNetBuffer evil = new FlixelNetBuffer();
    int intsLengthAt = findIntsLength(sent);
    evil.writeBytes(bytes, 0, intsLengthAt);
    evil.writeVarUInt(50_000_000);
    assertThrows(FlixelNetException.class, () -> TestMessagesEverythingNetCodec.INSTANCE.read(new Everything(), evil));
  }

  @Test
  void truncatedMessageThrowsInsteadOfReadingGarbage() {
    Chat chat = new Chat();
    chat.sender = 3;
    chat.text = "a fairly long line of chat";
    TestMessagesChatNetCodec.INSTANCE.write(chat, buffer);
    FlixelNetBuffer cut = new FlixelNetBuffer();
    cut.writeBytes(buffer.getData(), 0, buffer.getSize() - 5);
    assertThrows(FlixelNetException.class, () -> TestMessagesChatNetCodec.INSTANCE.read(new Chat(), cut));
  }

  @Test
  void outOfRangeEnumIsRejected() {
    // Team needs 2 bits, which can also hold the number 3, but there is no fourth constant.
    Everything sent = new Everything();
    sent.target = new Point();
    sent.team = Team.BLUE;
    TestMessagesEverythingNetCodec.INSTANCE.write(sent, buffer);
    byte[] bytes = buffer.toArray();
    boolean rejected = false;
    for (int i = 0; i < bytes.length && !rejected; i++) {
      byte original = bytes[i];
      bytes[i] |= 0x03;
      FlixelNetBuffer changed = new FlixelNetBuffer();
      changed.writeBytes(bytes, 0, bytes.length);
      try {
        TestMessagesEverythingNetCodec.INSTANCE.read(new Everything(), changed);
      } catch (FlixelNetException e) {
        rejected = e.getMessage().contains("team");
      }
      bytes[i] = original;
    }
    assertTrue(rejected, "setting the enum's bits to 3 must be refused");
  }

  @Test
  void resetReturnsAMessageToItsStartingValues() {
    Everything message = new Everything();
    message.id = 9;
    message.flag = true;
    message.text = "x";
    message.name.set("y");
    message.team = Team.GREEN;
    message.spawn.x = 5f;
    message.ints = new int[] { 4, 5 };
    message.health = 50;
    TestMessagesEverythingNetCodec.INSTANCE.reset(message);
    assertEquals(0, message.id);
    assertFalse(message.flag);
    assertNull(message.text);
    assertEquals(0, message.name.length());
    assertNull(message.team);
    assertEquals(0f, message.spawn.x);
    assertArrayEquals(new int[] { 0, 0 }, message.ints);
    assertEquals(0, message.health);
  }

  @Test
  void emptyMessageTakesNoBytes() {
    TestMessagesEmptyNetCodec.INSTANCE.write(new Empty(), buffer);
    assertEquals(0, buffer.getSize());
    assertEquals("", TestMessagesEmptyNetCodec.LAYOUT);
  }

  @Test
  void layoutDescribesTheBytesIncludingNestedMessages() {
    assertEquals("dx:f[-1.0,1.0,8];dy:f[-1.0,1.0,8];jump:z;crouch:z;", TestMessagesMoveNetCodec.LAYOUT);
    assertTrue(TestMessagesEverythingNetCodec.LAYOUT.startsWith("id:i;flag:z;"));
    assertTrue(TestMessagesEverythingNetCodec.LAYOUT.contains("team:e3;"));
    assertTrue(TestMessagesEverythingNetCodec.LAYOUT.contains("spawn:{" + TestMessagesPointNetCodec.LAYOUT + "};"));
    assertTrue(TestMessagesEverythingNetCodec.LAYOUT.contains("health:i[0,100];"));
    assertNotEquals(TestMessagesMoveNetCodec.LAYOUT, TestMessagesChatNetCodec.LAYOUT);
    assertEquals("org.flixelgdx.net.message.TestMessages$Move", TestMessagesMoveNetCodec.INSTANCE.getName());
    assertSame(Move.class, TestMessagesMoveNetCodec.INSTANCE.getType());
  }

  /** Returns how many bytes come before the length of the ints array in a written message. */
  private static int findIntsLength(Everything message) {
    FlixelNetBuffer probe = new FlixelNetBuffer();
    message.ints = new int[0];
    TestMessagesEverythingNetCodec.INSTANCE.write(message, probe);
    int withEmpty = probe.getSize();
    probe.clear();
    message.ints = new int[] { 1 };
    TestMessagesEverythingNetCodec.INSTANCE.write(message, probe);
    // Adding one small int adds exactly one byte, right after the length byte, so everything
    // from the length onward shifts. Compare the two encodings to find the first difference.
    byte[] one = probe.toArray();
    probe.clear();
    message.ints = new int[0];
    TestMessagesEverythingNetCodec.INSTANCE.write(message, probe);
    byte[] zero = probe.toArray();
    for (int i = 0; i < withEmpty; i++) {
      if (zero[i] != one[i]) {
        return i;
      }
    }
    throw new AssertionError("could not find the array length");
  }

  @Test
  void copyAndLerpAreGeneratedFieldByField() {
    TestMessagesPointNetCodec codec = TestMessagesPointNetCodec.INSTANCE;
    TestMessages.Point a = new TestMessages.Point();
    TestMessages.Point b = new TestMessages.Point();
    TestMessages.Point out = new TestMessages.Point();
    a.x = 10f;
    a.y = 20f;
    b.x = 20f;
    b.y = 60f;
    codec.lerp(a, b, 0.25f, out);
    assertEquals(12.5f, out.x);
    assertEquals(30f, out.y);
    codec.copy(b, out);
    assertEquals(20f, out.x);
    assertEquals(60f, out.y);
  }

  @Test
  void lerpKeepsWholeValuesFromTheEarlierMessageUntilTheEnd() {
    TestMessagesEverythingNetCodec codec = TestMessagesEverythingNetCodec.INSTANCE;
    TestMessages.Everything a = new TestMessages.Everything();
    TestMessages.Everything b = new TestMessages.Everything();
    TestMessages.Everything out = new TestMessages.Everything();
    a.number = 1;
    a.real = 0f;
    a.text = "old";
    a.name.set("first");
    a.ints = new int[] { 1, 2 };
    a.spawn.x = 0f;
    b.number = 2;
    b.real = 8f;
    b.text = "new";
    b.name.set("second");
    b.ints = new int[] { 3, 4, 5 };
    b.spawn.x = 100f;

    codec.lerp(a, b, 0.5f, out);
    assertEquals(1, out.number);
    assertEquals(4f, out.real);
    assertEquals("old", out.text);
    assertEquals("first", out.name.toString());
    assertEquals(2, out.ints.length);
    assertEquals(50f, out.spawn.x);

    codec.lerp(a, b, 1f, out);
    assertEquals(2, out.number);
    assertEquals(8f, out.real);
    assertEquals("new", out.text);
    assertEquals("second", out.name.toString());
    assertEquals(3, out.ints.length);
    assertEquals(5, out.ints[2]);
    // The result owns its own array, so changing it does not change the source.
    out.ints[0] = 99;
    assertEquals(3, b.ints[0]);
  }
}
