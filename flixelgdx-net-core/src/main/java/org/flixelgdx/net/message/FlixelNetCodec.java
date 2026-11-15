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
import org.jetbrains.annotations.NotNull;

/**
 * Knows how to turn one kind of message into bytes and back.
 *
 * <p>Almost nobody writes one of these by hand: marking a class with {@link NetMessage} generates
 * it. Write one yourself only when a message needs a custom byte layout, and then add it to a
 * {@link FlixelNetProtocol} the same way as a generated one.
 *
 * @param <T> The message class this codec handles.
 */
public interface FlixelNetCodec<T> {

  /**
   * Returns the message class this codec handles.
   *
   * @return The message class.
   */
  @NotNull
  Class<T> getType();

  /**
   * Returns a name that identifies this kind of message on both sides of a connection.
   *
   * <p>It must be the same in every build that should be able to talk to each other, and no two
   * codecs in one protocol may share it. Generated codecs use the full class name.
   *
   * @return The message's name.
   */
  @NotNull
  String getName();

  /**
   * Returns a short description of what the message's bytes contain.
   *
   * <p>It is never sent. It is mixed into {@link FlixelNetProtocol#getHash()} so that two builds
   * whose messages no longer match refuse to connect, instead of misreading each other.
   *
   * @return A description that changes whenever the byte layout changes.
   */
  @NotNull
  String getLayout();

  /**
   * Creates a new, empty message.
   *
   * @return The new message.
   */
  @NotNull
  T create();

  /**
   * Puts a message back to the state a new one has, so it can be reused.
   *
   * @param message The message to reset.
   */
  void reset(@NotNull T message);

  /**
   * Writes a message's fields into a buffer.
   *
   * @param message The message to write.
   * @param out The buffer to write into.
   */
  void write(@NotNull T message, @NotNull FlixelNetBuffer out);

  /**
   * Fills a message's fields from a buffer, in the same order {@link #write} wrote them.
   *
   * @param message The message to fill.
   * @param in The buffer to read from.
   */
  void read(@NotNull T message, @NotNull FlixelNetBuffer in);

  /**
   * Makes one message hold the same values as another.
   *
   * <p>Generated codecs copy field by field. A codec written by hand that does not override this
   * gets a slower copy that writes the message into a spare buffer and reads it back.
   *
   * @param from The message to copy. It is not changed.
   * @param to The message that receives the values.
   */
  default void copy(@NotNull T from, @NotNull T to) {
    FlixelNetBuffer spare = FlixelNetCodecs.SPARE;
    spare.clear();
    write(from, spare);
    read(to, spare);
  }

  /**
   * Fills a message with values part of the way between two others, which is how movement is
   * made smooth between two updates from the network.
   *
   * <p>Think of two photos of a rolling ball taken a moment apart. With {@code t} at 0 the result
   * is the first photo, at 1 it is the second, and at 0.5 the ball is drawn halfway between. In
   * a generated codec, {@code float} and {@code double} fields slide like the ball does. Every
   * other kind of field cannot be "halfway", so it keeps the value of {@code from} until
   * {@code t} reaches 1.
   *
   * @param from The earlier message.
   * @param to The later message.
   * @param t How far along the way from {@code from} to {@code to}, from 0 to 1.
   * @param out The message that receives the result. It may be {@code from} or {@code to}.
   */
  default void lerp(@NotNull T from, @NotNull T to, float t, @NotNull T out) {
    T source = t < 1f ? from : to;
    if (source != out) {
      copy(source, out);
    }
  }
}
