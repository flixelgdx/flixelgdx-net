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
import org.jetbrains.annotations.NotNull;

/**
 * A reusable block of bytes that network data is written into and read back out of.
 *
 * <p>Think of it like a shipping box with a packing list. The sender puts items in the box in a
 * set order (an id, then a position, then a name). The receiver takes them out in exactly the same
 * order. Nothing in the box says what each item is, so both sides must agree on the order. That is
 * what keeps packets small.
 *
 * <p>Writing always adds to the end of the buffer, and the buffer grows on its own when it runs
 * out of room. Reading starts at the beginning and moves forward. One buffer is meant to be kept
 * and reused for the whole life of a connection, so sending and receiving do not create garbage:
 *
 * <pre>{@code
 * // Sender: keep one buffer and clear it before each packet.
 * buffer.clear();
 * buffer.writeVarUInt(playerId);
 * buffer.writeQuantized(x, 0f, 1280f, 16);
 * buffer.writeQuantized(y, 0f, 720f, 16);
 * buffer.writeBool(jumping);
 * transport.send(connection, FlixelNetChannel.UNRELIABLE, buffer.getData(), 0, buffer.getSize());
 *
 * // Receiver: look at the received bytes without copying them, then read in the same order.
 * buffer.wrap(data, offset, length);
 * int playerId = buffer.readVarUInt();
 * float x = buffer.readQuantized(0f, 1280f, 16);
 * float y = buffer.readQuantized(0f, 720f, 16);
 * boolean jumping = buffer.readBool();
 * }</pre>
 *
 * <h2>Keeping packets small</h2>
 *
 * <ul>
 *   <li>{@link #writeVarUInt(int)} and {@link #writeVarInt(int)} use fewer bytes for small numbers,
 *       which most ids and counts are.</li>
 *   <li>{@link #writeQuantized(float, float, float, int)} stores a float that stays inside a known
 *       range in as few bits as you choose.</li>
 *   <li>{@link #writeBool(boolean)} and {@link #writeBits(int, int)} pack several small values into
 *       one byte. Values written back to back share bytes; the next whole-byte write starts on a
 *       fresh byte.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>Bytes that arrive from the network can never be trusted. Every read checks that enough bytes
 * are left and throws {@link FlixelNetException} when they are not, so a short or hostile packet
 * cannot read past its end. Multi-byte values are stored in little-endian order on every platform.
 *
 * <p>A buffer is not safe to use from several threads at once.
 */
public final class FlixelNetBuffer {

  /** The number of bytes a buffer made with {@link #FlixelNetBuffer()} can hold before it grows. */
  public static final int DEFAULT_CAPACITY = 256;

  /** The largest size, in bytes, a buffer is allowed to grow to. */
  public static final int MAX_CAPACITY = 1 << 24;

  private static final int MAX_QUANTIZED_BITS = 30;

  /** The Unicode replacement character, used in place of text that cannot be decoded. */
  private static final char REPLACEMENT = (char) 0xFFFD;

  private byte[] data;

  /** The buffer's own array, set aside while the buffer is a view of someone else's array. */
  private byte[] own;
  private char[] chars;

  /** Index of the first valid byte. Only a wrapped view starts anywhere but zero. */
  private int start;

  /** Index of the next byte to read. */
  private int pos;

  /** Index one past the last valid byte, which is also where the next write lands. */
  private int end;

  /** How many bits of the last written byte are in use, or zero when writes are byte aligned. */
  private int writeBit;

  /** How many bits of the last read byte are consumed, or zero when reads are byte aligned. */
  private int readBit;

  private boolean readOnly;

  /** Creates an empty buffer with room for {@link #DEFAULT_CAPACITY} bytes. */
  public FlixelNetBuffer() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates an empty buffer with room for the given number of bytes.
   *
   * @param capacity How many bytes the buffer can hold before it has to grow.
   * @throws IllegalArgumentException If {@code capacity} is negative or above {@link #MAX_CAPACITY}.
   */
  public FlixelNetBuffer(int capacity) {
    if (capacity < 0 || capacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("Capacity must be between 0 and " + MAX_CAPACITY + ", got " + capacity + ".");
    }
    data = new byte[capacity];
    chars = new char[0];
  }

  /**
   * Empties the buffer so it can be reused for a new packet.
   *
   * <p>The memory is kept, so this is cheap. A buffer that was a read-only view from
   * {@link #wrap(byte[], int, int)} lets go of the wrapped array and goes back to its own memory.
   */
  public void clear() {
    if (readOnly) {
      data = own;
      own = null;
    }
    start = 0;
    pos = 0;
    end = 0;
    writeBit = 0;
    readBit = 0;
    readOnly = false;
  }

  /** Moves the read position back to the beginning so the same data can be read again. */
  public void rewind() {
    pos = start;
    readBit = 0;
  }

  /**
   * Turns this buffer into a read-only view of part of an existing array, without copying it.
   *
   * <p>Use this to read a packet a transport just handed over. The array is only borrowed: do not
   * keep the buffer around after the code that received the packet returns, because the transport
   * may reuse the array. To keep the data, use {@link #set(byte[], int, int)} instead.
   *
   * @param source The array to read from.
   * @param offset The index of the first byte to read.
   * @param length How many bytes can be read.
   * @throws IndexOutOfBoundsException If the range does not fit inside {@code source}.
   */
  public void wrap(byte @NotNull [] source, int offset, int length) {
    checkRange(source.length, offset, length);
    if (!readOnly) {
      own = data;
    }
    data = source;
    start = offset;
    pos = offset;
    end = offset + length;
    writeBit = 0;
    readBit = 0;
    readOnly = true;
  }

  /**
   * Replaces the contents of this buffer with a copy of part of an array.
   *
   * @param source The array to copy from.
   * @param offset The index of the first byte to copy.
   * @param length How many bytes to copy.
   * @throws IndexOutOfBoundsException If the range does not fit inside {@code source}.
   */
  public void set(byte @NotNull [] source, int offset, int length) {
    checkRange(source.length, offset, length);
    clear();
    ensureCapacity(length);
    System.arraycopy(source, offset, data, 0, length);
    end = length;
  }

  /**
   * Makes sure the buffer can take the given number of extra bytes without growing again.
   *
   * @param extra How many more bytes are about to be written.
   * @throws FlixelNetException If the buffer is read-only or would grow past {@link #MAX_CAPACITY}.
   */
  public void ensureCapacity(int extra) {
    if (readOnly) {
      throw new FlixelNetException("This buffer is a read-only view; call clear() or set(...) before writing.");
    }
    int needed = end + extra;
    if (needed <= data.length) {
      return;
    }
    if (extra < 0 || needed > MAX_CAPACITY) {
      throw new FlixelNetException("Buffer cannot grow past " + MAX_CAPACITY + " bytes.");
    }
    int grown = Math.max(Math.max(data.length << 1, needed), 16);
    byte[] bigger = new byte[Math.min(grown, MAX_CAPACITY)];
    System.arraycopy(data, 0, bigger, 0, end);
    data = bigger;
  }

  /**
   * Writes one byte.
   *
   * @param value The byte to write. Only the lowest 8 bits are kept.
   */
  public void writeByte(int value) {
    ensureCapacity(1);
    writeBit = 0;
    data[end++] = (byte) value;
  }

  /**
   * Writes a true or false value using a single bit.
   *
   * <p>Several booleans written one after another share the same byte.
   *
   * @param value The value to write.
   */
  public void writeBool(boolean value) {
    writeBits(value ? 1 : 0, 1);
  }

  /**
   * Writes a 16-bit number.
   *
   * @param value The number to write. Only the lowest 16 bits are kept.
   */
  public void writeShort(int value) {
    ensureCapacity(2);
    writeBit = 0;
    data[end++] = (byte) value;
    data[end++] = (byte) (value >>> 8);
  }

  /**
   * Writes a 32-bit number using exactly four bytes.
   *
   * <p>Prefer {@link #writeVarInt(int)} or {@link #writeVarUInt(int)} when the number is usually
   * small.
   *
   * @param value The number to write.
   */
  public void writeInt(int value) {
    ensureCapacity(4);
    writeBit = 0;
    data[end++] = (byte) value;
    data[end++] = (byte) (value >>> 8);
    data[end++] = (byte) (value >>> 16);
    data[end++] = (byte) (value >>> 24);
  }

  /**
   * Writes a 64-bit number using exactly eight bytes.
   *
   * @param value The number to write.
   */
  public void writeLong(long value) {
    writeInt((int) value);
    writeInt((int) (value >>> 32));
  }

  /**
   * Writes a float at full precision using four bytes.
   *
   * <p>Prefer {@link #writeQuantized(float, float, float, int)} when the value stays inside a
   * known range, such as a position on screen.
   *
   * @param value The number to write.
   */
  public void writeFloat(float value) {
    writeInt(Float.floatToRawIntBits(value));
  }

  /**
   * Writes a double at full precision using eight bytes.
   *
   * @param value The number to write.
   */
  public void writeDouble(double value) {
    writeLong(Double.doubleToRawLongBits(value));
  }

  /**
   * Writes a number that is zero or positive using one to five bytes, fewer for smaller numbers.
   *
   * <p>Numbers below 128 take one byte and numbers below 16384 take two. A negative number still
   * works but always takes five bytes, so use {@link #writeVarInt(int)} for those.
   *
   * @param value The number to write.
   */
  public void writeVarUInt(int value) {
    ensureCapacity(5);
    writeBit = 0;
    while ((value & ~0x7F) != 0) {
      data[end++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    data[end++] = (byte) value;
  }

  /**
   * Writes a number that may be negative using one to five bytes, fewer the closer it is to zero.
   *
   * @param value The number to write.
   */
  public void writeVarInt(int value) {
    writeVarUInt((value << 1) ^ (value >> 31));
  }

  /**
   * Writes a 64-bit number that is zero or positive using one to ten bytes, fewer for smaller
   * numbers.
   *
   * @param value The number to write.
   */
  public void writeVarULong(long value) {
    ensureCapacity(10);
    writeBit = 0;
    while ((value & ~0x7FL) != 0L) {
      data[end++] = (byte) ((value & 0x7FL) | 0x80L);
      value >>>= 7;
    }
    data[end++] = (byte) value;
  }

  /**
   * Writes a 64-bit number that may be negative using one to ten bytes, fewer the closer it is to
   * zero.
   *
   * @param value The number to write.
   */
  public void writeVarLong(long value) {
    writeVarULong((value << 1) ^ (value >> 63));
  }

  /**
   * Writes the lowest bits of a number, using only as many bits as asked for.
   *
   * <p>This is how to store a value with a small known range, such as a direction from 0 to 7 in
   * 3 bits. Bit writes that follow each other share bytes. Any other kind of write finishes the
   * current byte first, so group bit writes together to save the most space.
   *
   * @param value The number whose lowest {@code count} bits are written.
   * @param count How many bits to write, from 1 to 32.
   * @throws IllegalArgumentException If {@code count} is not from 1 to 32.
   */
  public void writeBits(int value, int count) {
    if (count < 1 || count > 32) {
      throw new IllegalArgumentException("Bit count must be from 1 to 32, got " + count + ".");
    }
    ensureCapacity(5);
    while (count > 0) {
      if (writeBit == 0) {
        data[end++] = 0;
      }
      int taken = Math.min(8 - writeBit, count);
      data[end - 1] |= (byte) ((value & ((1 << taken) - 1)) << writeBit);
      value >>>= taken;
      count -= taken;
      writeBit = (writeBit + taken) & 7;
    }
  }

  /**
   * Writes a float that stays inside a known range using only the given number of bits.
   *
   * <p>The range is cut into equal steps and the nearest step is stored, like rounding a length to
   * the nearest mark on a ruler. More bits means finer marks. For example, an x position from 0 to
   * 1280 stored in 16 bits is accurate to about a fiftieth of a pixel and takes half the space of
   * a full float. Values outside the range are moved to the nearest end of it.
   *
   * <p>The receiver must call {@link #readQuantized(float, float, int)} with the same range and
   * bit count.
   *
   * @param value The number to write.
   * @param min The smallest value the number can be.
   * @param max The largest value the number can be. Must be greater than {@code min}.
   * @param bits How many bits to use, from 1 to 30.
   * @throws IllegalArgumentException If the range is empty or {@code bits} is not from 1 to 30.
   */
  public void writeQuantized(float value, float min, float max, int bits) {
    checkQuantized(min, max, bits);
    double unit = ((double) value - min) / ((double) max - min);
    if (!(unit > 0.0)) {
      unit = 0.0;
    } else if (unit > 1.0) {
      unit = 1.0;
    }
    writeBits((int) Math.round(unit * ((1L << bits) - 1L)), bits);
  }

  /**
   * Writes text.
   *
   * <p>The text is stored as UTF-8 with its length in front, so any character works and plain
   * English text takes one byte per letter. Writing does not create garbage.
   *
   * @param text The text to write.
   */
  public void writeString(@NotNull CharSequence text) {
    int length = text.length();
    int bytes = 0;
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        bytes++;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    writeVarUInt(bytes);
    ensureCapacity(bytes);
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        data[end++] = (byte) c;
      } else if (c < 0x800) {
        data[end++] = (byte) (0xC0 | (c >> 6));
        data[end++] = (byte) (0x80 | (c & 0x3F));
      } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
        int point = Character.toCodePoint(c, text.charAt(++i));
        data[end++] = (byte) (0xF0 | (point >> 18));
        data[end++] = (byte) (0x80 | ((point >> 12) & 0x3F));
        data[end++] = (byte) (0x80 | ((point >> 6) & 0x3F));
        data[end++] = (byte) (0x80 | (point & 0x3F));
      } else {
        data[end++] = (byte) (0xE0 | (c >> 12));
        data[end++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        data[end++] = (byte) (0x80 | (c & 0x3F));
      }
    }
  }

  /**
   * Writes part of a byte array as is, with no length in front.
   *
   * @param source The array to copy from.
   * @param offset The index of the first byte to copy.
   * @param length How many bytes to copy.
   * @throws IndexOutOfBoundsException If the range does not fit inside {@code source}.
   */
  public void writeBytes(byte @NotNull [] source, int offset, int length) {
    checkRange(source.length, offset, length);
    ensureCapacity(length);
    writeBit = 0;
    System.arraycopy(source, offset, data, end, length);
    end += length;
  }

  /**
   * Writes everything another buffer holds, whether or not it has been read, with no length in
   * front.
   *
   * @param other The buffer to copy from. It is not changed.
   */
  public void writeBuffer(@NotNull FlixelNetBuffer other) {
    writeBytes(other.data, other.start, other.end - other.start);
  }

  /**
   * Reads one byte as a number from -128 to 127.
   *
   * @return The byte that was read.
   * @throws FlixelNetException If no bytes are left.
   */
  public byte readByte() {
    require(1);
    return data[pos++];
  }

  /**
   * Reads one byte as a number from 0 to 255.
   *
   * @return The byte that was read.
   * @throws FlixelNetException If no bytes are left.
   */
  public int readUByte() {
    return readByte() & 0xFF;
  }

  /**
   * Reads a true or false value written by {@link #writeBool(boolean)}.
   *
   * @return The value that was read.
   * @throws FlixelNetException If no bytes are left.
   */
  public boolean readBool() {
    return readBits(1) != 0;
  }

  /**
   * Reads a 16-bit number as a value from -32768 to 32767.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than two bytes are left.
   */
  public short readShort() {
    return (short) readUShort();
  }

  /**
   * Reads a 16-bit number as a value from 0 to 65535.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than two bytes are left.
   */
  public int readUShort() {
    require(2);
    int value = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
    pos += 2;
    return value;
  }

  /**
   * Reads a 32-bit number written by {@link #writeInt(int)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than four bytes are left.
   */
  public int readInt() {
    require(4);
    int value = (data[pos] & 0xFF)
        | ((data[pos + 1] & 0xFF) << 8)
        | ((data[pos + 2] & 0xFF) << 16)
        | ((data[pos + 3] & 0xFF) << 24);
    pos += 4;
    return value;
  }

  /**
   * Reads a 64-bit number written by {@link #writeLong(long)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than eight bytes are left.
   */
  public long readLong() {
    long low = readInt() & 0xFFFFFFFFL;
    long high = readInt();
    return low | (high << 32);
  }

  /**
   * Reads a float written by {@link #writeFloat(float)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than four bytes are left.
   */
  public float readFloat() {
    return Float.intBitsToFloat(readInt());
  }

  /**
   * Reads a double written by {@link #writeDouble(double)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If fewer than eight bytes are left.
   */
  public double readDouble() {
    return Double.longBitsToDouble(readLong());
  }

  /**
   * Reads a number written by {@link #writeVarUInt(int)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If the data ends early or is not a valid number.
   */
  public int readVarUInt() {
    int value = 0;
    for (int shift = 0; shift < 35; shift += 7) {
      int b = readByte();
      value |= (b & 0x7F) << shift;
      if (b >= 0) {
        return value;
      }
    }
    throw new FlixelNetException("Variable-length number is longer than 5 bytes.");
  }

  /**
   * Reads a number written by {@link #writeVarInt(int)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If the data ends early or is not a valid number.
   */
  public int readVarInt() {
    int value = readVarUInt();
    return (value >>> 1) ^ -(value & 1);
  }

  /**
   * Reads a number written by {@link #writeVarULong(long)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If the data ends early or is not a valid number.
   */
  public long readVarULong() {
    long value = 0L;
    for (int shift = 0; shift < 70; shift += 7) {
      int b = readByte();
      value |= (long) (b & 0x7F) << shift;
      if (b >= 0) {
        return value;
      }
    }
    throw new FlixelNetException("Variable-length number is longer than 10 bytes.");
  }

  /**
   * Reads a number written by {@link #writeVarLong(long)}.
   *
   * @return The number that was read.
   * @throws FlixelNetException If the data ends early or is not a valid number.
   */
  public long readVarLong() {
    long value = readVarULong();
    return (value >>> 1) ^ -(value & 1L);
  }

  /**
   * Reads a number written by {@link #writeBits(int, int)}.
   *
   * @param count How many bits to read, from 1 to 32. Must match what was written.
   * @return The number that was read. Bits above {@code count} are zero.
   * @throws IllegalArgumentException If {@code count} is not from 1 to 32.
   * @throws FlixelNetException If the data ends early.
   */
  public int readBits(int count) {
    if (count < 1 || count > 32) {
      throw new IllegalArgumentException("Bit count must be from 1 to 32, got " + count + ".");
    }
    int value = 0;
    int shift = 0;
    while (count > 0) {
      if (readBit == 0) {
        if (pos >= end) {
          throw new FlixelNetException("Tried to read past the end of the buffer.");
        }
        pos++;
      }
      int taken = Math.min(8 - readBit, count);
      value |= (((data[pos - 1] & 0xFF) >>> readBit) & ((1 << taken) - 1)) << shift;
      shift += taken;
      count -= taken;
      readBit = (readBit + taken) & 7;
    }
    return value;
  }

  /**
   * Reads a float written by {@link #writeQuantized(float, float, float, int)}.
   *
   * @param min The smallest value the number can be. Must match what was written.
   * @param max The largest value the number can be. Must match what was written.
   * @param bits How many bits were used, from 1 to 30. Must match what was written.
   * @return The number that was read, which is always inside the range.
   * @throws IllegalArgumentException If the range is empty or {@code bits} is not from 1 to 30.
   * @throws FlixelNetException If the data ends early.
   */
  public float readQuantized(float min, float max, int bits) {
    checkQuantized(min, max, bits);
    double unit = readBits(bits) / (double) ((1L << bits) - 1L);
    return (float) (min + unit * ((double) max - min));
  }

  /**
   * Reads text written by {@link #writeString(CharSequence)} as a new {@link String}.
   *
   * <p>This creates a new object each time. For text that arrives often, such as every frame,
   * use {@link #readString(FlixelString)} instead.
   *
   * @return The text that was read.
   * @throws FlixelNetException If the data ends early.
   */
  @NotNull
  public String readString() {
    // Decode first: it may replace the scratch array, so the array must be read afterward.
    int count = decodeString();
    return new String(chars, 0, count);
  }

  /**
   * Reads text written by {@link #writeString(CharSequence)} into an existing {@link FlixelString},
   * without creating garbage.
   *
   * @param out Where to put the text. Whatever it held before is replaced.
   * @throws FlixelNetException If the data ends early.
   */
  public void readString(@NotNull FlixelString out) {
    int count = decodeString();
    out.clear();
    for (int i = 0; i < count; i++) {
      out.concat(chars[i]);
    }
  }

  /**
   * Reads raw bytes into an array.
   *
   * @param target The array to copy into.
   * @param offset The index in {@code target} to start copying to.
   * @param length How many bytes to read.
   * @throws IndexOutOfBoundsException If the range does not fit inside {@code target}.
   * @throws FlixelNetException If fewer than {@code length} bytes are left.
   */
  public void readBytes(byte @NotNull [] target, int offset, int length) {
    checkRange(target.length, offset, length);
    require(length);
    System.arraycopy(data, pos, target, offset, length);
    pos += length;
  }

  /**
   * Skips over bytes without reading them.
   *
   * @param length How many bytes to skip.
   * @throws FlixelNetException If {@code length} is negative or fewer than {@code length} bytes
   *     are left.
   */
  public void skip(int length) {
    require(length);
    pos += length;
  }

  /**
   * Returns how many whole bytes are left to read.
   *
   * @return The number of unread bytes.
   */
  public int remaining() {
    return end - pos;
  }

  /**
   * Returns a new array holding a copy of everything in the buffer.
   *
   * <p>This creates a new array each time, so it is meant for tests and tools, not for code that
   * runs every frame.
   *
   * @return A copy of the buffer's contents.
   */
  public byte @NotNull [] toArray() {
    byte[] copy = new byte[end - start];
    System.arraycopy(data, start, copy, 0, copy.length);
    return copy;
  }

  /**
   * Returns the array that holds the buffer's bytes.
   *
   * <p>The valid bytes start at {@link #getOffset()} and there are {@link #getSize()} of them.
   * The array is replaced when the buffer grows, so do not hold on to it across writes.
   *
   * @return The backing array.
   */
  public byte @NotNull [] getData() {
    return data;
  }

  /**
   * Returns the index in {@link #getData()} of the first valid byte.
   *
   * <p>This is zero unless the buffer is a view made by {@link #wrap(byte[], int, int)}.
   *
   * @return The index of the first valid byte.
   */
  public int getOffset() {
    return start;
  }

  /**
   * Returns how many bytes the buffer holds.
   *
   * @return The number of valid bytes.
   */
  public int getSize() {
    return end - start;
  }

  /**
   * Returns how many bytes have been read so far.
   *
   * @return The read position, counted from the first valid byte.
   */
  public int getPosition() {
    return pos - start;
  }

  /**
   * Moves the read position.
   *
   * @param position The new read position, counted from the first valid byte.
   * @throws IndexOutOfBoundsException If {@code position} is negative or past the end of the data.
   */
  public void setPosition(int position) {
    if (position < 0 || position > end - start) {
      throw new IndexOutOfBoundsException("Position " + position + " is outside 0 to " + (end - start) + ".");
    }
    pos = start + position;
    readBit = 0;
  }

  /**
   * Returns how many bytes the buffer can hold before it has to grow.
   *
   * @return The capacity in bytes.
   */
  public int getCapacity() {
    return data.length - start;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  /**
   * Checks that enough bytes are left for a whole-byte read and ends any bit read in progress.
   *
   * @param length How many bytes are about to be read.
   */
  private void require(int length) {
    if (length < 0 || length > end - pos) {
      throw new FlixelNetException("Tried to read past the end of the buffer.");
    }
    readBit = 0;
  }

  /**
   * Decodes a length-prefixed UTF-8 string into the scratch character array.
   *
   * <p>Broken byte sequences become the Unicode replacement character instead of failing, because
   * what matters for safety is never reading outside the string, not rejecting odd text.
   *
   * @return How many characters were decoded.
   */
  private int decodeString() {
    int bytes = readVarUInt();
    require(bytes);
    if (chars.length < bytes) {
      chars = new char[bytes];
    }
    int limit = pos + bytes;
    int count = 0;
    while (pos < limit) {
      int b = data[pos++] & 0xFF;
      if (b < 0x80) {
        chars[count++] = (char) b;
      } else if ((b & 0xE0) == 0xC0 && pos < limit) {
        chars[count++] = (char) (((b & 0x1F) << 6) | (data[pos++] & 0x3F));
      } else if ((b & 0xF0) == 0xE0 && pos + 1 < limit) {
        chars[count++] = (char) (((b & 0x0F) << 12) | ((data[pos] & 0x3F) << 6) | (data[pos + 1] & 0x3F));
        pos += 2;
      } else if ((b & 0xF8) == 0xF0 && pos + 2 < limit) {
        int point = ((b & 0x07) << 18)
            | ((data[pos] & 0x3F) << 12)
            | ((data[pos + 1] & 0x3F) << 6)
            | (data[pos + 2] & 0x3F);
        pos += 3;
        if (point >= 0x10000 && point <= 0x10FFFF) {
          chars[count++] = Character.highSurrogate(point);
          chars[count++] = Character.lowSurrogate(point);
        } else {
          chars[count++] = REPLACEMENT;
        }
      } else {
        chars[count++] = REPLACEMENT;
      }
    }
    return count;
  }

  private static void checkRange(int size, int offset, int length) {
    if (offset < 0 || length < 0 || offset > size - length) {
      throw new IndexOutOfBoundsException(
          "Range of " + length + " bytes at " + offset + " does not fit in an array of " + size + ".");
    }
  }

  private static void checkQuantized(float min, float max, int bits) {
    if (!(max > min)) {
      throw new IllegalArgumentException("Range must not be empty: min " + min + ", max " + max + ".");
    }
    if (bits < 1 || bits > MAX_QUANTIZED_BITS) {
      throw new IllegalArgumentException("Bit count must be from 1 to " + MAX_QUANTIZED_BITS + ", got " + bits + ".");
    }
  }
}
