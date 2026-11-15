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
package org.flixelgdx.net.jvm;

import org.flixelgdx.net.transport.FlixelNetDatagramSocket;
import org.flixelgdx.net.transport.FlixelNetDatagramTransport;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * A real UDP socket for desktop, Android and servers.
 *
 * <p>UDP is the "throw a small packet and hope" service of the internet. This class does nothing
 * clever: a background thread waits for packets and puts them on a shelf, and the game thread
 * takes them off the shelf when {@link FlixelNetDatagramTransport} polls. Everything that makes
 * UDP usable for a game lives in the transport, not here.
 *
 * <p>The shelf has a fixed number of places. If the game stops polling for so long that it fills
 * up, new packets are dropped, which the transport already knows how to recover from.
 *
 * <p>Addresses are written as {@code "host:port"}, for example {@code "192.168.1.20:7777"},
 * {@code "example.com:7777"} or {@code "[::1]:7777"}. Turning a host name into an address asks
 * the network and can take a moment; a numeric address does not.
 */
public final class FlixelJvmDatagramSocket implements FlixelNetDatagramSocket<SocketAddress> {

  private static final int SHELF = 512;
  private static final int MAX_PACKET = FlixelNetDatagramTransport.MAX_DATAGRAM;

  private final Object lock = new Object();
  private final byte[][] shelfData = new byte[SHELF][];
  private final int[] shelfLength = new int[SHELF];
  private final SocketAddress[] shelfSender = new SocketAddress[SHELF];
  private final ByteBuffer outgoing = ByteBuffer.allocate(MAX_PACKET);
  private volatile DatagramChannel channel;
  private SocketAddress sender;
  private int head;
  private int count;

  /** Creates a socket that is not open yet. */
  public FlixelJvmDatagramSocket() {}

  @Override
  public void open(int port) {
    if (channel != null) {
      throw new IllegalStateException("The socket is already open.");
    }
    try {
      DatagramChannel opened = DatagramChannel.open();
      try {
        opened.bind(new InetSocketAddress(port));
      } catch (IOException e) {
        opened.close();
        throw e;
      }
      synchronized (lock) {
        head = 0;
        count = 0;
      }
      channel = opened;
      Thread thread = new Thread(() -> receiveLoop(opened), "FlixelNet UDP");
      thread.setDaemon(true);
      thread.start();
    } catch (IOException e) {
      throw new IllegalStateException("Could not open a UDP socket on port " + port + ": " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    DatagramChannel closing = channel;
    channel = null;
    if (closing != null) {
      try {
        closing.close();
      } catch (IOException e) {
        // Nothing useful can be done about a socket that fails to close.
      }
    }
    synchronized (lock) {
      for (int i = 0; i < SHELF; i++) {
        shelfSender[i] = null;
      }
      head = 0;
      count = 0;
    }
  }

  @Override
  public @NotNull SocketAddress resolve(@NotNull String address) {
    String text = address.startsWith("udp://") ? address.substring(6) : address;
    int colon = text.lastIndexOf(':');
    if (colon <= 0 || colon == text.length() - 1) {
      throw new IllegalArgumentException("Address must look like host:port, got '" + address + "'.");
    }
    String host = text.substring(0, colon);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int port;
    try {
      port = Integer.parseInt(text.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Address must end in a port number, got '" + address + "'.");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Port must be from 1 to 65535, got " + port + ".");
    }
    InetSocketAddress resolved = new InetSocketAddress(host, port);
    if (resolved.isUnresolved()) {
      throw new IllegalArgumentException("Could not find the host '" + host + "'.");
    }
    return resolved;
  }

  @Override
  public void send(@NotNull SocketAddress to, byte @NotNull [] data, int offset, int length) {
    DatagramChannel open = channel;
    if (open == null || length > MAX_PACKET) {
      return;
    }
    outgoing.clear();
    outgoing.put(data, offset, length);
    outgoing.flip();
    try {
      open.send(outgoing, to);
    } catch (IOException | RuntimeException e) {
      // A packet that cannot be sent is the same as one lost on the way, which the transport
      // already handles.
    }
  }

  @Override
  public int receive(byte @NotNull [] into) {
    synchronized (lock) {
      if (count == 0) {
        return -1;
      }
      int length = Math.min(into.length, shelfLength[head]);
      System.arraycopy(shelfData[head], 0, into, 0, length);
      sender = shelfSender[head];
      shelfSender[head] = null;
      head = (head + 1) % SHELF;
      count--;
      return length;
    }
  }

  @Override
  public @NotNull SocketAddress getSender() {
    return sender;
  }

  /**
   * Returns the port the socket is receiving on.
   *
   * @return The port, or -1 if the socket is not open.
   */
  public int getPort() {
    DatagramChannel open = channel;
    try {
      return open != null && open.getLocalAddress() instanceof InetSocketAddress local ? local.getPort() : -1;
    } catch (IOException e) {
      return -1;
    }
  }

  /** Runs on the background thread: waits for packets and shelves them for the game thread. */
  private void receiveLoop(DatagramChannel open) {
    ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET);
    while (open.isOpen()) {
      SocketAddress from;
      try {
        buffer.clear();
        from = open.receive(buffer);
      } catch (IOException | RuntimeException e) {
        if (!open.isOpen()) {
          return;
        }
        // Some systems report "the last packet you sent bounced" here. It says nothing about
        // this socket, so keep going.
        continue;
      }
      if (from == null) {
        continue;
      }
      buffer.flip();
      synchronized (lock) {
        if (count == SHELF || channel != open) {
          continue;
        }
        int slot = (head + count) % SHELF;
        if (shelfData[slot] == null) {
          shelfData[slot] = new byte[MAX_PACKET];
        }
        shelfLength[slot] = buffer.remaining();
        buffer.get(shelfData[slot], 0, shelfLength[slot]);
        shelfSender[slot] = from;
        count++;
      }
    }
  }
}
