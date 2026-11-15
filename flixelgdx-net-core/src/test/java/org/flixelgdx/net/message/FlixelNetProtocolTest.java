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

import org.flixelgdx.net.message.TestMessages.Chat;
import org.flixelgdx.net.message.TestMessages.Empty;
import org.flixelgdx.net.message.TestMessages.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetProtocolTest {

  @Test
  void messagesAreNumberedByNameNoMatterTheOrderAdded() {
    FlixelNetProtocol first = new FlixelNetProtocol("game", 1)
        .add(TestMessagesMoveNetCodec.INSTANCE)
        .add(TestMessagesChatNetCodec.INSTANCE);
    FlixelNetProtocol second = new FlixelNetProtocol("game", 1)
        .add(TestMessagesChatNetCodec.INSTANCE)
        .add(TestMessagesMoveNetCodec.INSTANCE);
    first.freeze();
    second.freeze();

    assertEquals(0, first.entry(Chat.class).getIndex());
    assertEquals(1, first.entry(Move.class).getIndex());
    assertEquals(0, second.entry(Chat.class).getIndex());
    assertEquals(first.getHash(), second.getHash());
    assertSame(TestMessagesMoveNetCodec.INSTANCE, first.entry(1).getCodec());
    assertNull(first.entry(2));
    assertNull(first.entry(-1));
    assertEquals(2, first.getCount());
  }

  @Test
  void fingerprintChangesWithNameVersionOrMessages() {
    int base = frozen(new FlixelNetProtocol("game", 1).add(TestMessagesMoveNetCodec.INSTANCE)).getHash();
    assertNotEquals(base, frozen(new FlixelNetProtocol("other", 1).add(TestMessagesMoveNetCodec.INSTANCE)).getHash());
    assertNotEquals(base, frozen(new FlixelNetProtocol("game", 2).add(TestMessagesMoveNetCodec.INSTANCE)).getHash());
    assertNotEquals(base, frozen(new FlixelNetProtocol("game", 1).add(TestMessagesChatNetCodec.INSTANCE)).getHash());
    assertNotEquals(base, frozen(new FlixelNetProtocol("game", 1)
        .add(TestMessagesMoveNetCodec.INSTANCE).add(TestMessagesEmptyNetCodec.INSTANCE)).getHash());
  }

  @Test
  void addingIsLockedOnceFrozenAndDuplicatesAreRefused() {
    FlixelNetProtocol protocol = new FlixelNetProtocol("game", 1).add(TestMessagesMoveNetCodec.INSTANCE);
    assertThrows(IllegalArgumentException.class, () -> protocol.add(TestMessagesMoveNetCodec.INSTANCE));
    assertThrows(IllegalStateException.class, protocol::getHash);
    assertFalse(protocol.isFrozen());
    protocol.freeze();
    protocol.freeze();
    assertTrue(protocol.isFrozen());
    assertThrows(IllegalStateException.class, () -> protocol.add(TestMessagesChatNetCodec.INSTANCE));
    assertThrows(IllegalArgumentException.class, () -> protocol.obtain(Empty.class));
  }

  @Test
  void freedMessagesAreResetAndHandedOutAgain() {
    FlixelNetProtocol protocol = frozen(new FlixelNetProtocol("game", 1).add(TestMessagesChatNetCodec.INSTANCE));
    Chat first = protocol.obtain(Chat.class);
    first.sender = 4;
    first.text = "hi";
    Chat other = protocol.obtain(Chat.class);
    assertNotSame(first, other);

    protocol.free(first);
    Chat again = protocol.obtain(Chat.class);
    assertSame(first, again);
    assertEquals(0, again.sender);
    assertNull(again.text);
  }

  private static FlixelNetProtocol frozen(FlixelNetProtocol protocol) {
    protocol.freeze();
    return protocol;
  }
}
