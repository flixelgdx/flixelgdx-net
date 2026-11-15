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

import org.flixelgdx.collections.FlixelArray;
import org.flixelgdx.collections.FlixelIdentityMap;
import org.flixelgdx.net.FlixelNetBuffer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The list of every kind of message a game can send.
 *
 * <p>Think of it as the shared phrase book two people agree on before a trip: both sides must
 * carry exactly the same book or they cannot understand each other. Each game builds one protocol
 * at startup, adds every message to it, and hands it to its sessions. When a client joins, the
 * two sides compare a fingerprint of their books ({@link #getHash()}), and a client with a
 * different book is turned away with a clear reason instead of garbled data.
 *
 * <pre>{@code
 * FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1)
 *   .add(MoveInputNetCodec.INSTANCE)
 *   .add(ChatLineNetCodec.INSTANCE);
 * }</pre>
 *
 * <p>The order messages are added in does not matter. Change the version number whenever the
 * game's rules change in a way that old and new builds should not play together, even if the
 * messages themselves look the same.
 *
 * <p>The protocol also keeps used messages so they can be handed out again, which is why sending
 * and receiving do not create garbage. A protocol must only be used from one thread.
 */
public final class FlixelNetProtocol {

  private final FlixelArray<Entry<?>> entries = new FlixelArray<>();
  private final FlixelIdentityMap<Class<?>, Entry<?>> byType = new FlixelIdentityMap<>();
  private final String name;
  private final int version;
  private int hash;
  private boolean frozen;

  /**
   * Creates an empty protocol.
   *
   * @param name A name for the game, so two different games never connect to each other by
   *     accident.
   * @param version A number to raise whenever old and new builds of the game should stop being
   *     able to play together.
   */
  public FlixelNetProtocol(@NotNull String name, int version) {
    this.name = name;
    this.version = version;
  }

  /**
   * Adds a kind of message.
   *
   * @param codec The codec for the message, usually the {@code INSTANCE} of a class generated
   *     from {@link NetMessage}.
   * @param <T> The message class.
   * @return This protocol, so calls can be chained.
   * @throws IllegalStateException If a session is already using this protocol.
   * @throws IllegalArgumentException If a message with the same class or name was already added.
   */
  @NotNull
  public <T> FlixelNetProtocol add(@NotNull FlixelNetCodec<T> codec) {
    if (frozen) {
      throw new IllegalStateException("Messages must be added before the protocol is given to a session.");
    }
    if (byType.containsKey(codec.getType())) {
      throw new IllegalArgumentException("Message " + codec.getType().getName() + " was already added.");
    }
    for (int i = 0, n = entries.getSize(); i < n; i++) {
      if (entries.get(i).codec.getName().equals(codec.getName())) {
        throw new IllegalArgumentException("Two messages share the name '" + codec.getName() + "'.");
      }
    }
    Entry<T> entry = new Entry<>(codec);
    entries.add(entry);
    byType.put(codec.getType(), entry);
    return this;
  }

  /**
   * Locks the list of messages and works out each message's number and the protocol's
   * fingerprint.
   *
   * <p>Sessions call this for you. Messages are numbered in name order, not in the order they
   * were added, so both sides agree no matter how their code is arranged. Calling it again does
   * nothing.
   */
  public void freeze() {
    if (frozen) {
      return;
    }
    frozen = true;
    // A simple insertion sort by name: the list is short and this runs once.
    for (int i = 1, n = entries.getSize(); i < n; i++) {
      Entry<?> moving = entries.get(i);
      int j = i - 1;
      while (j >= 0 && entries.get(j).codec.getName().compareTo(moving.codec.getName()) > 0) {
        entries.set(j + 1, entries.get(j));
        j--;
      }
      entries.set(j + 1, moving);
    }
    int h = mix(0x811C9DC5, name);
    h = (h ^ version) * 0x01000193;
    for (int i = 0, n = entries.getSize(); i < n; i++) {
      Entry<?> entry = entries.get(i);
      entry.index = i;
      h = mix(h, entry.codec.getName());
      h = mix(h, entry.codec.getLayout());
    }
    hash = h;
  }

  /**
   * Hands out an empty message of the given class, reusing an old one when possible.
   *
   * @param type The message class.
   * @param <T> The message class.
   * @return A message with every field at its starting value.
   * @throws IllegalArgumentException If the class was never added to this protocol.
   */
  @NotNull
  public <T> T obtain(@NotNull Class<T> type) {
    return entry(type).obtain();
  }

  /**
   * Takes a message back so it can be handed out again.
   *
   * <p>Sessions do this for every message they send or deliver, so games rarely need to. Do not
   * touch a message after freeing it.
   *
   * @param message The message to take back.
   * @throws IllegalArgumentException If the message's class was never added to this protocol.
   */
  public void free(@NotNull Object message) {
    freeUnchecked(entry(message.getClass()), message);
  }

  /**
   * Returns the entry for a message class.
   *
   * @param type The message class.
   * @param <T> The message class.
   * @return The entry, which gives access to the message's number and codec.
   * @throws IllegalArgumentException If the class was never added to this protocol.
   */
  @NotNull
  @SuppressWarnings("unchecked")
  public <T> Entry<T> entry(@NotNull Class<T> type) {
    Entry<?> entry = byType.get(type);
    if (entry == null) {
      throw new IllegalArgumentException(
          type.getName() + " is not part of this protocol. Add its codec with protocol.add(...) first.");
    }
    return (Entry<T>) entry;
  }

  /**
   * Returns the entry with the given number, as assigned by {@link #freeze()}.
   *
   * @param index The message's number, from 0 to {@link #getCount()} minus one.
   * @return The entry, or {@code null} if the number is out of range.
   */
  @Nullable
  public Entry<?> entry(int index) {
    return index >= 0 && index < entries.getSize() ? entries.get(index) : null;
  }

  /**
   * Returns the protocol's fingerprint: a number that is the same on two sides only if their
   * name, version and messages all match.
   *
   * @return The fingerprint.
   * @throws IllegalStateException If {@link #freeze()} has not run yet.
   */
  public int getHash() {
    if (!frozen) {
      throw new IllegalStateException("The protocol has no fingerprint until it is frozen.");
    }
    return hash;
  }

  /**
   * Returns how many kinds of message the protocol has.
   *
   * @return The number of messages.
   */
  public int getCount() {
    return entries.getSize();
  }

  @NotNull
  public String getName() {
    return name;
  }

  public int getVersion() {
    return version;
  }

  public boolean isFrozen() {
    return frozen;
  }

  @SuppressWarnings("unchecked")
  private static <T> void freeUnchecked(Entry<T> entry, Object message) {
    entry.free((T) message);
  }

  /** Mixes a piece of text into a running FNV-1a hash. */
  private static int mix(int h, String text) {
    for (int i = 0, n = text.length(); i < n; i++) {
      h = (h ^ text.charAt(i)) * 0x01000193;
    }
    // A separator, so "ab" + "c" and "a" + "bc" do not hash the same.
    return (h ^ 0xFF) * 0x01000193;
  }

  /**
   * One kind of message inside a protocol: its codec, its number, and its spare messages.
   *
   * @param <T> The message class.
   */
  public static final class Entry<T> {

    private final FlixelNetCodec<T> codec;
    private final FlixelArray<T> spare = new FlixelArray<>();
    private int index = -1;

    private Entry(FlixelNetCodec<T> codec) {
      this.codec = codec;
    }

    /**
     * Hands out an empty message, reusing an old one when possible.
     *
     * @return A message with every field at its starting value.
     */
    @NotNull
    public T obtain() {
      return spare.isEmpty() ? codec.create() : spare.pop();
    }

    /**
     * Takes a message back so it can be handed out again.
     *
     * @param message The message to take back.
     */
    public void free(@NotNull T message) {
      codec.reset(message);
      spare.add(message);
    }

    /**
     * Writes a message into a buffer.
     *
     * @param message The message to write.
     * @param out The buffer to write into.
     */
    public void write(@NotNull T message, @NotNull FlixelNetBuffer out) {
      codec.write(message, out);
    }

    /**
     * Fills a message from a buffer.
     *
     * @param message The message to fill.
     * @param in The buffer to read from.
     */
    public void read(@NotNull T message, @NotNull FlixelNetBuffer in) {
      codec.read(message, in);
    }

    @NotNull
    public FlixelNetCodec<T> getCodec() {
      return codec;
    }

    /**
     * Returns the message's number in its protocol.
     *
     * @return The number, or -1 before the protocol is frozen.
     */
    public int getIndex() {
      return index;
    }
  }
}
