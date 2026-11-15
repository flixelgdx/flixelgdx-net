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

import org.flixelgdx.util.FlixelString;

/** Message classes used by the tests, covering every kind of field the processor supports. */
public final class TestMessages {

  private TestMessages() {}

  /** The three colors a test player can have. */
  public enum Team {
    RED, GREEN, BLUE
  }

  /** A small, typical message. */
  @NetMessage
  public static class Move {
    @NetRange(min = -1, max = 1, bits = 8)
    public float dx;
    @NetRange(min = -1, max = 1, bits = 8)
    public float dy;
    public boolean jump;
    public boolean crouch;
  }

  /** A message with text. */
  @NetMessage
  public static class Chat {
    public int sender;
    public String text;
  }

  /** A message with no fields at all. */
  @NetMessage
  public static class Empty {
  }

  /** A message that is part of another message. */
  @NetMessage
  public static class Point {
    @NetRange(min = 0, max = 1280)
    public float x;
    @NetRange(min = 0, max = 720)
    public float y;
  }

  /** A parent class whose fields are sent before the child's. */
  @NetMessage
  public static class Base {
    public int id;
  }

  /** One of everything. */
  @NetMessage
  public static class Everything extends Base {
    public static int ignoredStatic = 5;
    public transient int ignoredTransient = 6;
    public boolean flag;
    public byte tiny;
    public short small;
    public char letter;
    public int number;
    public long big;
    public float real;
    public double precise;
    public String text;
    public final FlixelString name = new FlixelString();
    public FlixelString nick;
    public Team team;
    public final Point spawn = new Point();
    public Point target;
    public int[] ints;
    public byte[] bytes;
    public boolean[] flags;
    public float[] floats;
    public long[] longs;
    public char[] chars;
    @NetRange(min = 0, max = 100)
    public int health;
    @NetRange(min = -5, max = 5)
    public byte offset;
    @NetRange(min = 1000, max = 1001)
    public short oneBit;
    @NetRange(min = Integer.MIN_VALUE, max = Integer.MAX_VALUE)
    public int full;
  }
}
