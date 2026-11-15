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
package org.flixelgdx.net.server;

import org.flixelgdx.Flixel;
import org.flixelgdx.FlixelGame;
import org.flixelgdx.net.jvm.FlixelJvmNet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Starts a game as a dedicated server: no window, just the game loop and the network.
 *
 * <p>A dedicated server is the same game running on a computer nobody plays on, whose only job
 * is to host. Its launcher is one line, like every other platform's:
 *
 * <pre>{@code
 * public class ServerLauncher {
 *   public static void main(String[] args) {
 *     FlixelServerLauncher.launch(new MyGame());
 *   }
 * }
 * }</pre>
 *
 * <p>The game can ask {@link #isServer()} to decide what to do when it starts, for example to
 * go straight to hosting instead of showing a menu.
 *
 * <p>Stopping the process the usual way (Ctrl+C in a terminal, {@code docker stop}, or a
 * service manager's stop command) shuts the game down cleanly: the current update finishes and
 * the game is destroyed, so players are told the session ended instead of timing out.
 */
public final class FlixelServerLauncher {

  /** How long a shutdown waits for the game to finish, in milliseconds. */
  private static final long SHUTDOWN_WAIT = 5000L;

  private static FlixelHeadlessRunner runner;

  private FlixelServerLauncher() {}

  /**
   * Runs a game as a dedicated server at {@value FlixelHeadlessRunner#DEFAULT_RATE} updates a
   * second. Returns when the server stops.
   *
   * @param game The game to run.
   */
  public static void launch(@NotNull FlixelGame game) {
    launch(game, FlixelHeadlessRunner.DEFAULT_RATE);
  }

  /**
   * Runs a game as a dedicated server. Returns when the server stops.
   *
   * @param game The game to run.
   * @param rate Updates per second, from 1 to 1000. Use the rate the game runs at for players.
   */
  public static void launch(@NotNull FlixelGame game, int rate) {
    FlixelHeadlessRunner headless = new FlixelHeadlessRunner(rate);
    runner = headless;
    // A server has no window to lose focus, and must never stop answering the network.
    Flixel.autoPause = false;
    FlixelJvmNet.install();
    Thread hook = new Thread(() -> headless.stopAndWait(SHUTDOWN_WAIT), "flixel-server-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    Flixel.start(game, headless);
  }

  /**
   * Stops the server that {@link #launch} started. The current update finishes first. Does
   * nothing when no server is running.
   */
  public static void stop() {
    FlixelHeadlessRunner headless = runner;
    if (headless != null) {
      headless.stop();
    }
  }

  /**
   * Returns whether this program was started as a dedicated server.
   *
   * @return {@code true} once {@link #launch} has been called.
   */
  public static boolean isServer() {
    return runner != null;
  }

  /**
   * Returns the runner that drives the server's game loop.
   *
   * @return The runner, or {@code null} when this program is not a dedicated server.
   */
  @Nullable
  public static FlixelHeadlessRunner getRunner() {
    return runner;
  }
}
