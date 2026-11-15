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
package org.flixelgdx.net.rollback;

import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.net.FlixelNetException;
import org.flixelgdx.net.message.FlixelNetCodec;
import org.jetbrains.annotations.NotNull;

/**
 * The one kind of message a {@link FlixelRollbackSession} sends: an envelope of bytes it packs
 * and unpacks by itself.
 *
 * <p>Games never create or read this. It only has to be part of the protocol, which
 * {@link FlixelRollbackSession#addMessages} takes care of.
 */
public final class FlixelRollbackPacket {

  /** The codec to add to a protocol; {@link FlixelRollbackSession#addMessages} does it for you. */
  public static final FlixelNetCodec<FlixelRollbackPacket> CODEC = new Codec();

  final FlixelNetBuffer body = new FlixelNetBuffer();

  FlixelRollbackPacket() {}

  private static final class Codec implements FlixelNetCodec<FlixelRollbackPacket> {

    @Override
    public @NotNull Class<FlixelRollbackPacket> getType() {
      return FlixelRollbackPacket.class;
    }

    @Override
    public @NotNull String getName() {
      return "org.flixelgdx.net.rollback.FlixelRollbackPacket";
    }

    @Override
    public @NotNull String getLayout() {
      return "rollback:1;";
    }

    @Override
    public @NotNull FlixelRollbackPacket create() {
      return new FlixelRollbackPacket();
    }

    @Override
    public void reset(@NotNull FlixelRollbackPacket message) {
      message.body.clear();
    }

    @Override
    public void write(@NotNull FlixelRollbackPacket message, @NotNull FlixelNetBuffer out) {
      out.writeVarUInt(message.body.getSize());
      out.writeBuffer(message.body);
    }

    @Override
    public void read(@NotNull FlixelRollbackPacket message, @NotNull FlixelNetBuffer in) {
      int length = in.readVarUInt();
      if (length < 0 || length > in.remaining()) {
        throw new FlixelNetException("A rollback packet is longer than the packet it came in.");
      }
      message.body.set(in.getData(), in.getOffset() + in.getPosition(), length);
      in.skip(length);
    }
  }
}
