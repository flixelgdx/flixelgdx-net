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

import org.flixelgdx.util.FlixelString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetBufferTest {

  @Test
  void primitivesRoundTrip() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeByte(-5);
    buffer.writeByte(200);
    buffer.writeShort(-1234);
    buffer.writeShort(65000);
    buffer.writeInt(Integer.MIN_VALUE);
    buffer.writeLong(Long.MIN_VALUE + 7L);
    buffer.writeFloat(3.25f);
    buffer.writeDouble(-1.0e100);

    assertEquals(1 + 1 + 2 + 2 + 4 + 8 + 4 + 8, buffer.getSize());
    assertEquals(-5, buffer.readByte());
    assertEquals(200, buffer.readUByte());
    assertEquals(-1234, buffer.readShort());
    assertEquals(65000, buffer.readUShort());
    assertEquals(Integer.MIN_VALUE, buffer.readInt());
    assertEquals(Long.MIN_VALUE + 7L, buffer.readLong());
    assertEquals(3.25f, buffer.readFloat());
    assertEquals(-1.0e100, buffer.readDouble());
    assertEquals(0, buffer.remaining());
  }

  @Test
  void multiByteValuesAreLittleEndian() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeInt(0x04030201);
    assertArrayEquals(new byte[] { 1, 2, 3, 4 }, buffer.toArray());
  }

  @Test
  void varIntsRoundTripAndStaySmall() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    int[] unsigned = { 0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, -1 };
    int[] sizes = { 1, 1, 1, 2, 2, 3, 5, 5 };
    for (int i = 0; i < unsigned.length; i++) {
      buffer.clear();
      buffer.writeVarUInt(unsigned[i]);
      assertEquals(sizes[i], buffer.getSize(), "size of " + unsigned[i]);
      assertEquals(unsigned[i], buffer.readVarUInt());
    }

    int[] signed = { 0, -1, 1, -64, 63, -65, Integer.MIN_VALUE, Integer.MAX_VALUE };
    for (int value : signed) {
      buffer.clear();
      buffer.writeVarInt(value);
      assertEquals(value, buffer.readVarInt());
    }
    buffer.clear();
    buffer.writeVarInt(-64);
    assertEquals(1, buffer.getSize());

    long[] longs = { 0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40 };
    for (long value : longs) {
      buffer.clear();
      buffer.writeVarLong(value);
      buffer.writeVarULong(value);
      assertEquals(value, buffer.readVarLong());
      assertEquals(value, buffer.readVarULong());
    }
  }

  @Test
  void overlongVarIntIsRejected() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    for (int i = 0; i < 12; i++) {
      buffer.writeByte(0x80);
    }
    assertThrows(FlixelNetException.class, buffer::readVarUInt);
    buffer.rewind();
    assertThrows(FlixelNetException.class, buffer::readVarULong);
  }

  @Test
  void bitsShareBytesAndAlignBeforeWholeBytes() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeBool(true);
    buffer.writeBool(false);
    buffer.writeBits(5, 3);
    buffer.writeBool(true);
    assertEquals(1, buffer.getSize());

    buffer.writeByte(42);
    buffer.writeBits(0x1ABCD, 17);
    buffer.writeBits(-1, 32);
    buffer.writeInt(99);

    assertTrue(buffer.readBool());
    assertFalse(buffer.readBool());
    assertEquals(5, buffer.readBits(3));
    assertTrue(buffer.readBool());
    assertEquals(42, buffer.readByte());
    assertEquals(0x1ABCD, buffer.readBits(17));
    assertEquals(-1, buffer.readBits(32));
    assertEquals(99, buffer.readInt());
    assertEquals(0, buffer.remaining());
  }

  @Test
  void bitCountIsValidated() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    assertThrows(IllegalArgumentException.class, () -> buffer.writeBits(1, 0));
    assertThrows(IllegalArgumentException.class, () -> buffer.writeBits(1, 33));
    assertThrows(IllegalArgumentException.class, () -> buffer.readBits(0));
  }

  @Test
  void quantizedFloatsStayWithinOneStep() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    float[] values = { 0f, 1280f, 640.37f, 12.5f, -50f, 5000f };
    for (float value : values) {
      buffer.writeQuantized(value, 0f, 1280f, 16);
    }
    assertEquals(values.length * 2, buffer.getSize());

    float step = 1280f / 65535f;
    assertEquals(0f, buffer.readQuantized(0f, 1280f, 16));
    assertEquals(1280f, buffer.readQuantized(0f, 1280f, 16));
    assertEquals(640.37f, buffer.readQuantized(0f, 1280f, 16), step);
    assertEquals(12.5f, buffer.readQuantized(0f, 1280f, 16), step);
    assertEquals(0f, buffer.readQuantized(0f, 1280f, 16), "below the range clamps to min");
    assertEquals(1280f, buffer.readQuantized(0f, 1280f, 16), "above the range clamps to max");
  }

  @Test
  void quantizedArgumentsAreValidated() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    assertThrows(IllegalArgumentException.class, () -> buffer.writeQuantized(1f, 5f, 5f, 8));
    assertThrows(IllegalArgumentException.class, () -> buffer.writeQuantized(1f, 0f, 1f, 31));
    buffer.writeQuantized(Float.NaN, 0f, 1f, 8);
    assertEquals(0f, buffer.readQuantized(0f, 1f, 8));
  }

  @Test
  void stringsRoundTripIncludingUnicode() {
    FlixelNetBuffer buffer = new FlixelNetBuffer(4);
    String accented = "caf" + (char) 0xE9;
    String threeByte = new String(new char[] { (char) 0x65E5, (char) 0x672C, (char) 0x8A9E });
    String emoji = "smile " + new String(Character.toChars(0x1F600)) + " done";
    String loneSurrogate = "lone " + (char) 0xD83D + " end";
    String[] texts = { "", "hello", accented, threeByte, emoji, loneSurrogate };
    for (String text : texts) {
      buffer.writeString(text);
    }
    FlixelString reused = new FlixelString();
    for (int i = 0; i < texts.length - 1; i++) {
      int before = buffer.getPosition();
      assertEquals(texts[i], buffer.readString());
      buffer.setPosition(before);
      buffer.readString(reused);
      assertEquals(texts[i], reused.toString());
    }
    assertEquals(loneSurrogate, buffer.readString());
    assertEquals(0, buffer.remaining());
  }

  @Test
  void asciiStringTakesOneBytePerLetterPlusLength() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeString("hello");
    assertEquals(6, buffer.getSize());
  }

  @Test
  void stringLengthLargerThanPacketIsRejected() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeVarUInt(1_000_000);
    buffer.writeByte('a');
    assertThrows(FlixelNetException.class, buffer::readString);
  }

  @Test
  void brokenUtf8NeverReadsOutsideTheString() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeVarUInt(2);
    buffer.writeByte(0xF0);
    buffer.writeByte(0x9F);
    buffer.writeByte('X');
    assertEquals("" + (char) 0xFFFD + (char) 0xFFFD, buffer.readString());
    assertEquals('X', buffer.readByte());
  }

  @Test
  void readingPastTheEndThrows() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeShort(7);
    assertThrows(FlixelNetException.class, buffer::readInt);
    assertEquals(7, buffer.readShort());
    assertThrows(FlixelNetException.class, buffer::readByte);
    assertThrows(FlixelNetException.class, buffer::readBool);
    assertThrows(FlixelNetException.class, () -> buffer.skip(1));
    assertThrows(FlixelNetException.class, () -> buffer.skip(-1));
  }

  @Test
  void bufferGrowsAndKeepsItsContents() {
    FlixelNetBuffer buffer = new FlixelNetBuffer(0);
    for (int i = 0; i < 1000; i++) {
      buffer.writeInt(i);
    }
    assertEquals(4000, buffer.getSize());
    for (int i = 0; i < 1000; i++) {
      assertEquals(i, buffer.readInt());
    }
  }

  @Test
  void wrapReadsAViewWithoutCopyingAndRefusesWrites() {
    byte[] packet = { 9, 9, 5, 0, 0, 0, 7, 9 };
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.wrap(packet, 2, 5);

    assertTrue(buffer.isReadOnly());
    assertEquals(5, buffer.getSize());
    assertEquals(2, buffer.getOffset());
    assertEquals(5, buffer.readInt());
    assertEquals(4, buffer.getPosition());
    assertEquals(7, buffer.readByte());
    assertThrows(FlixelNetException.class, buffer::readByte);
    assertThrows(FlixelNetException.class, () -> buffer.writeByte(1));
    assertThrows(IndexOutOfBoundsException.class, () -> buffer.wrap(packet, 6, 5));

    buffer.rewind();
    assertEquals(5, buffer.readInt());
  }

  @Test
  void setCopiesAndLeavesTheSourceAlone() {
    byte[] packet = { 1, 2, 3, 4 };
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.wrap(packet, 0, 4);
    buffer.set(packet, 1, 2);
    buffer.writeByte(77);

    assertFalse(buffer.isReadOnly());
    assertArrayEquals(new byte[] { 2, 3, 77 }, buffer.toArray());
    assertArrayEquals(new byte[] { 1, 2, 3, 4 }, packet);
  }

  @Test
  void clearAfterWrapNeverWritesIntoTheWrappedArray() {
    byte[] packet = { 1, 2, 3, 4 };
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.wrap(packet, 0, 4);
    buffer.wrap(packet, 1, 2);
    buffer.clear();
    buffer.writeInt(-1);

    assertFalse(buffer.isReadOnly());
    assertArrayEquals(new byte[] { 1, 2, 3, 4 }, packet);
    assertEquals(-1, buffer.readInt());
  }

  @Test
  void rawBytesAndBuffersCanBeAppended() {
    FlixelNetBuffer inner = new FlixelNetBuffer();
    inner.writeShort(0x0201);
    FlixelNetBuffer outer = new FlixelNetBuffer();
    outer.writeBytes(new byte[] { 9, 8, 7 }, 1, 2);
    outer.writeBuffer(inner);

    byte[] out = new byte[5];
    outer.readBytes(out, 1, 4);
    assertArrayEquals(new byte[] { 0, 8, 7, 1, 2 }, out);
  }

  @Test
  void clearMakesTheBufferReusable() {
    FlixelNetBuffer buffer = new FlixelNetBuffer();
    buffer.writeBool(true);
    buffer.writeInt(5);
    buffer.readBool();
    buffer.clear();
    assertEquals(0, buffer.getSize());
    buffer.writeBool(false);
    buffer.writeBool(true);
    assertEquals(1, buffer.getSize());
    assertFalse(buffer.readBool());
    assertTrue(buffer.readBool());
  }
}
