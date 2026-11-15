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

/** Why a peer is no longer part of a session, or why joining a session failed. */
public enum FlixelNetDisconnectReason {

  /** The peer left on purpose. */
  LEFT,

  /** The host removed the peer. */
  KICKED,

  /** The host shut the session down. */
  SHUTDOWN,

  /** Nothing was heard from the peer for too long, for example because it lost its connection. */
  TIMEOUT,

  /** Nothing was listening at the address that was joined. */
  REFUSED,

  /**
   * The two sides are running builds that cannot talk to each other: their protocols have a
   * different name, version, or set of messages.
   */
  VERSION_MISMATCH,

  /** The host already has as many peers as it allows. */
  FULL,

  /** The connection broke, or the peer kept sending data that could not be understood. */
  ERROR
}
