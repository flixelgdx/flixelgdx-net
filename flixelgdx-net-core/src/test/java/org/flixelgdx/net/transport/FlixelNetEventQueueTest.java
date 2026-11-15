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
package org.flixelgdx.net.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlixelNetEventQueueTest {

  @Test
  void eventsComeOutInTheOrderTheyWentIn() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    RecordingListener listener = new RecordingListener();
    queue.pushOpen(3);
    queue.pushData(3, FlixelNetChannel.RELIABLE, new byte[] { 0, 1, 2, 3 }, 1, 2);
    queue.pushData(3, FlixelNetChannel.UNRELIABLE, new byte[0], 0, 0);
    queue.pushClose(3, FlixelNetCloseReason.TIMEOUT);

    assertEquals(4, queue.getSize());
    assertEquals(4, queue.drain(listener));
    assertEquals("open 3\ndata 3 RELIABLE 1,2\ndata 3 UNRELIABLE \nclose 3 TIMEOUT\n", listener.take());
    assertEquals(0, queue.getSize());
    assertEquals(0, queue.drain(listener));
  }

  @Test
  void eventsWaitForTheirTimeAndNeverOvertake() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    RecordingListener listener = new RecordingListener();
    queue.pushData(1, FlixelNetChannel.RELIABLE, new byte[] { 1 }, 0, 1, 1.0);
    queue.pushData(1, FlixelNetChannel.RELIABLE, new byte[] { 2 }, 0, 1, 0.5);
    queue.pushData(1, FlixelNetChannel.RELIABLE, new byte[] { 3 }, 0, 1, 2.0);

    assertEquals(0, queue.drain(listener, 0.9));
    assertEquals(2, queue.drain(listener, 1.0));
    assertEquals("data 1 RELIABLE 1\ndata 1 RELIABLE 2\n", listener.take());

    // What is left has been moved to the front; new packets still line up behind it.
    queue.pushData(1, FlixelNetChannel.RELIABLE, new byte[] { 4, 5 }, 0, 2, 2.0);
    assertEquals(2, queue.drain(listener, 2.0));
    assertEquals("data 1 RELIABLE 3\ndata 1 RELIABLE 4,5\n", listener.take());
  }

  @Test
  void queueGrowsPastItsStartingSize() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    byte[] packet = new byte[100];
    for (int i = 0; i < 200; i++) {
      packet[0] = (byte) i;
      queue.pushData(i, FlixelNetChannel.RELIABLE, packet, 0, packet.length);
    }
    int[] next = { 0 };
    queue.drain(new FlixelNetTransportListener() {
      @Override
      public void onOpen(int connection) {}

      @Override
      public void onClose(int connection, FlixelNetCloseReason reason) {}

      @Override
      public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {
        assertEquals(next[0], connection);
        assertEquals((byte) next[0], data[offset]);
        assertEquals(100, length);
        next[0]++;
      }
    });
    assertEquals(200, next[0]);
  }

  @Test
  void eventsPushedWhileDrainingAreDeliveredInTheSameDrain() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    StringBuilder log = new StringBuilder();
    queue.pushOpen(1);
    queue.drain(new FlixelNetTransportListener() {
      @Override
      public void onOpen(int connection) {
        log.append("open;");
        queue.pushClose(connection, FlixelNetCloseReason.LOCAL);
        assertThrows(IllegalStateException.class, () -> queue.drain(this));
      }

      @Override
      public void onClose(int connection, FlixelNetCloseReason reason) {
        log.append("close;");
      }

      @Override
      public void onData(int connection, FlixelNetChannel channel, byte[] data, int offset, int length) {}
    });
    assertEquals("open;close;", log.toString());
  }

  @Test
  void clearDropsEverything() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    RecordingListener listener = new RecordingListener();
    queue.pushOpen(1, 5.0);
    queue.clear();
    queue.pushOpen(2);
    assertEquals(1, queue.drain(listener, 0.0));
    assertEquals("open 2\n", listener.take());
  }

  @Test
  void badRangeIsRejected() {
    FlixelNetEventQueue queue = new FlixelNetEventQueue();
    assertThrows(IndexOutOfBoundsException.class,
        () -> queue.pushData(1, FlixelNetChannel.RELIABLE, new byte[4], 2, 3));
  }
}
