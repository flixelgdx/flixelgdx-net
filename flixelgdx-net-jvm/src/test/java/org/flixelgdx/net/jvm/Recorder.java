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

import org.flixelgdx.net.transport.FlixelNetChannel;
import org.flixelgdx.net.transport.FlixelNetCloseReason;
import org.flixelgdx.net.transport.FlixelNetTransport;
import org.flixelgdx.net.transport.FlixelNetTransportListener;

/** A listener for tests that writes down everything it is told as readable text. */
final class Recorder implements FlixelNetTransportListener {

  private final StringBuilder log = new StringBuilder();
  int bytes;

  @Override
  public void onOpen(int connection) {
    log.append("open ").append(connection).append('\n');
  }

  @Override
  public void onClose(int connection, FlixelNetCloseReason reason) {
    log.append("close ").append(connection).append(' ').append(reason).append('\n');
  }

  @Override
  public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
    bytes += length;
    log.append("data ").append(connection).append(' ').append(channel).append(' ');
    if (length > 16) {
      log.append(length).append(" bytes");
    } else {
      for (int i = 0; i < length; i++) {
        log.append(data[offset + i]).append(i + 1 < length ? "," : "");
      }
    }
    log.append('\n');
  }

  String take() {
    String text = log.toString();
    log.setLength(0);
    return text;
  }

  /**
   * Polls both transports until the two logs together contain every expected piece of text, or
   * gives up after a few seconds. Real sockets take real time, so tests cannot just step a clock.
   */
  static void await(FlixelNetTransport a, Recorder aLog, FlixelNetTransport b, Recorder bLog, String... expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + 8_000_000_000L;
    while (System.nanoTime() < deadline) {
      a.poll(aLog);
      b.poll(bLog);
      String all = aLog.log + "|" + bLog.log;
      boolean done = true;
      for (String piece : expected) {
        done &= all.contains(piece);
      }
      if (done) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("Timed out. Host heard:\n" + aLog.log + "Client heard:\n" + bLog.log);
  }
}
