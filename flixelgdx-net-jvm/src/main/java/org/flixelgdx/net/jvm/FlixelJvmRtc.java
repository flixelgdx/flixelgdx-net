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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.CRC32;

/**
 * The doorway to the native WebRTC library: a thin, static wrapper with no logic of its own.
 *
 * <p>WebRTC is how a browser and a game can exchange packets that are allowed to get lost
 * instead of holding everything up. Browsers have it built in; on desktop, Android and servers
 * it comes from a small native library, which this class finds, loads and calls.
 *
 * <p>Games do not use this class. {@link FlixelJvmWebTransport} does, and it is the place to
 * look to understand how the pieces fit. This class is public so that tools and tests can reach
 * the library directly.
 *
 * <h2>How it is used</h2>
 *
 * <p>Each {@link #create(String[], int, boolean)} makes one connection to one other computer and
 * returns a number, its handle. Nothing is ever reported through a callback. Instead, everything
 * that happens on any connection (a description or candidate that must be passed to the other
 * side, a channel opening, a message arriving) waits in one queue inside the library until
 * {@link #poll(byte[])} takes it out, so all of it can be handled on the game thread.
 *
 * <h2>Finding the library</h2>
 *
 * <p>The library is looked for in three places, in order: the file named by the system property
 * {@code flixel.net.rtc.library}; the system's own library search (which is how Android finds
 * it inside the app); and the {@code flixelgdx-net-natives-<platform>} jar on the classpath, from
 * which it is copied to the temporary folder. If it is found in none of them,
 * {@link #isAvailable()} returns {@code false} and nothing else in this class may be called.
 *
 * <p>This file deliberately uses nothing outside the JDK, so it can be compiled by itself.
 */
public final class FlixelJvmRtc {

  /** An event carrying the text {@code "type\nsdp"} to pass on to the other side. */
  public static final int EVENT_DESCRIPTION = 1;

  /** An event carrying the text {@code "mid\ncandidate"} to pass on to the other side. */
  public static final int EVENT_CANDIDATE = 2;

  /** An event carrying one byte: the connection's new state, one of the {@code STATE_} values. */
  public static final int EVENT_STATE = 3;

  /** An event carrying one byte: the channel that is now open. */
  public static final int EVENT_OPEN = 4;

  /** An event carrying one byte: the channel that is now closed. */
  public static final int EVENT_CLOSED = 5;

  /** An event carrying a message that arrived on the reliable channel. */
  public static final int EVENT_RELIABLE = 6;

  /** An event carrying a message that arrived on the unreliable channel. */
  public static final int EVENT_UNRELIABLE = 7;

  /** The channel that delivers everything, in order. */
  public static final int CHANNEL_RELIABLE = 0;

  /** The channel that never waits and never resends. */
  public static final int CHANNEL_UNRELIABLE = 1;

  /** The connection is established. */
  public static final int STATE_CONNECTED = 2;

  /** The connection was interrupted and may come back. */
  public static final int STATE_DISCONNECTED = 3;

  /** The connection could not be established or broke for good. */
  public static final int STATE_FAILED = 4;

  /** The connection was closed. */
  public static final int STATE_CLOSED = 5;

  private static final String LIBRARY = "flixelnetrtc";

  private static String problem;
  private static boolean tried;
  private static boolean loaded;

  private FlixelJvmRtc() {}

  /**
   * Returns whether the native library is loaded, loading it on the first call.
   *
   * @return {@code true} if WebRTC can be used.
   */
  public static synchronized boolean isAvailable() {
    if (!tried) {
      tried = true;
      loaded = load();
    }
    return loaded;
  }

  /**
   * Returns why the native library could not be loaded.
   *
   * @return A description of the problem, or {@code null} if loading worked or was never tried.
   */
  public static synchronized String getProblem() {
    return loaded ? null : problem;
  }

  /**
   * Creates a connection to one other computer.
   *
   * @param iceServers The helper servers to use, each written like
   *     {@code "stun:host:port"} or {@code "turn:user:password@host:port"}. May be empty.
   * @param port A UDP port every connection should share, or 0 to let the system pick.
   * @param offer {@code true} for the side that starts the exchange and creates the channels,
   *     {@code false} for the side that answers.
   * @return The new connection's handle, or a negative number if it could not be created.
   */
  public static int create(String[] iceServers, int port, boolean offer) {
    return nCreate(iceServers, port, offer);
  }

  /**
   * Hands over the description the other side sent.
   *
   * @param handle The connection's handle.
   * @param sdp The description.
   * @param type {@code "offer"} or {@code "answer"}.
   * @return Zero or more on success, a negative number on failure.
   */
  public static int setRemoteDescription(int handle, String sdp, String type) {
    return nSetRemoteDescription(handle, sdp, type);
  }

  /**
   * Hands over a candidate the other side sent.
   *
   * @param handle The connection's handle.
   * @param candidate The candidate.
   * @param mid The media id that came with it.
   * @return Zero or more on success, a negative number on failure.
   */
  public static int addRemoteCandidate(int handle, String candidate, String mid) {
    return nAddRemoteCandidate(handle, candidate, mid);
  }

  /**
   * Sends a message on one of a connection's two channels.
   *
   * @param handle The connection's handle.
   * @param channel {@link #CHANNEL_RELIABLE} or {@link #CHANNEL_UNRELIABLE}.
   * @param data The array holding the message.
   * @param offset The index of the first byte of the message in {@code data}.
   * @param length How many bytes the message has.
   * @return Zero or more on success, a negative number on failure.
   */
  public static int send(int handle, int channel, byte[] data, int offset, int length) {
    return nSend(handle, channel, data, offset, length);
  }

  /**
   * Returns how many bytes are still waiting to be sent on a channel.
   *
   * @param handle The connection's handle.
   * @param channel {@link #CHANNEL_RELIABLE} or {@link #CHANNEL_UNRELIABLE}.
   * @return The number of waiting bytes.
   */
  public static int buffered(int handle, int channel) {
    return nBuffered(handle, channel);
  }

  /**
   * Takes the oldest waiting event of any connection out of the library.
   *
   * <p>The result packs three numbers; read them with {@link #eventType(long)},
   * {@link #eventHandle(long)} and {@link #eventLength(long)}. The event's bytes are copied to
   * the start of {@code into}.
   *
   * @param into The array to copy the event's bytes into.
   * @return The packed event; zero if nothing is waiting; or a negative number whose size is how
   *     large {@code into} must be for the event that is waiting, which stays in the queue.
   */
  public static long poll(byte[] into) {
    return nPoll(into);
  }

  /**
   * Closes a connection and forgets everything that was waiting for it.
   *
   * @param handle The connection's handle. Closing one that is already closed does nothing.
   */
  public static void close(int handle) {
    nClose(handle);
  }

  /**
   * Makes the native library print what it is doing, for tracking down connection problems.
   *
   * @param level From 0 (nothing) through 2 (errors), 3 (warnings), 4 (information) and 5
   *     (debugging) to 6 (everything).
   */
  public static void setLogLevel(int level) {
    nSetLogLevel(level);
  }

  /**
   * Returns the kind of an event returned by {@link #poll(byte[])}.
   *
   * @param event The packed event.
   * @return One of the {@code EVENT_} values.
   */
  public static int eventType(long event) {
    return (int) (event >>> 56);
  }

  /**
   * Returns the handle of the connection an event belongs to.
   *
   * @param event The packed event.
   * @return The connection's handle.
   */
  public static int eventHandle(long event) {
    return (int) ((event >>> 24) & 0x3FFFFFFF);
  }

  /**
   * Returns how many bytes an event copied into the array given to {@link #poll(byte[])}.
   *
   * @param event The packed event.
   * @return The number of bytes.
   */
  public static int eventLength(long event) {
    return (int) (event & 0xFFFFFF);
  }

  private static boolean load() {
    String explicit = System.getProperty("flixel.net.rtc.library");
    try {
      if (explicit != null) {
        System.load(Paths.get(explicit).toAbsolutePath().toString());
        return true;
      }
    } catch (UnsatisfiedLinkError | RuntimeException e) {
      problem = "Could not load " + explicit + ": " + e.getMessage();
      return false;
    }
    try {
      System.loadLibrary(LIBRARY);
      return true;
    } catch (UnsatisfiedLinkError | RuntimeException e) {
      // Not installed system-wide or inside an Android app; look for the natives jar next.
    }
    String target = target();
    if (target == null) {
      problem = "There is no native WebRTC library for this platform (" + System.getProperty("os.name") + " "
          + System.getProperty("os.arch") + ").";
      return false;
    }
    String file = target.startsWith("windows") ? LIBRARY + ".dll"
        : target.startsWith("macos") ? "lib" + LIBRARY + ".dylib" : "lib" + LIBRARY + ".so";
    String resource = "/natives/" + target + "/" + file;
    try (InputStream in = FlixelJvmRtc.class.getResourceAsStream(resource)) {
      if (in == null) {
        problem = "The native WebRTC library is not on the classpath. Add the flixelgdx-net-natives-" + target
            + " dependency to use WebRTC.";
        return false;
      }
      byte[] bytes = in.readAllBytes();
      CRC32 crc = new CRC32();
      crc.update(bytes);
      // The folder is named after the library's contents, so different versions never collide
      // and an unchanged library is only copied out once.
      Path folder =
          Paths.get(System.getProperty("java.io.tmpdir"), "flixelgdx-net-" + Long.toHexString(crc.getValue()));
      Path library = folder.resolve(file);
      if (!Files.isRegularFile(library) || Files.size(library) != bytes.length) {
        Files.createDirectories(folder);
        Files.write(library, bytes);
      }
      System.load(library.toAbsolutePath().toString());
      return true;
    } catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
      problem = "Could not load the native WebRTC library: " + e.getMessage();
      return false;
    }
  }

  /** Returns the name of this platform as the natives jars spell it, or null if unsupported. */
  private static String target() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    String system = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos"
        : os.contains("linux") ? "linux" : null;
    String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64"
        : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
    return system == null || cpu == null ? null : system + "-" + cpu;
  }

  private static native int nCreate(String[] iceServers, int port, boolean offer);

  private static native int nSetRemoteDescription(int handle, String sdp, String type);

  private static native int nAddRemoteCandidate(int handle, String candidate, String mid);

  private static native int nSend(int handle, int channel, byte[] data, int offset, int length);

  private static native int nBuffered(int handle, int channel);

  private static native long nPoll(byte[] into);

  private static native void nClose(int handle);

  private static native void nSetLogLevel(int level);
}
