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
import org.flixelgdx.backend.FlixelGameRunner;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.locks.LockSupport;

/**
 * Runs a game with no window, no drawing and no sound, at a steady number of updates per second.
 *
 * <p>A normal runner is like a cinema: it opens a screen, shows a frame, and waits for the screen
 * to be ready for the next one. A dedicated server has nobody to show anything to. This runner is
 * the same film projector with the lamp switched off: the game's states and objects update
 * exactly as they would on a player's computer, and nothing is ever drawn.
 *
 * <p>Most games do not use this class directly; {@link FlixelServerLauncher} sets everything up
 * in one line. Use it directly to choose when the loop stops:
 *
 * <pre>{@code
 * FlixelHeadlessRunner runner = new FlixelHeadlessRunner(30);
 * FlixelJvmNet.install();
 * Flixel.start(new MyGame(), runner); // Returns after runner.stop() is called.
 * }</pre>
 *
 * <p>Graphics, sound and input stay the do-nothing versions the framework starts with, so a
 * state that loads textures or plays sounds does not crash here; those calls just have no
 * effect. Server code should still skip work that only matters on screen.
 */
public class FlixelHeadlessRunner implements FlixelGameRunner {

  /** How many updates run per second unless a different rate is given. */
  public static final int DEFAULT_RATE = 60;

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private long frames;
  private Thread loopThread;
  private int rate;
  private volatile boolean stopRequested;
  private volatile boolean running;

  /** Creates a runner that updates the game {@value #DEFAULT_RATE} times a second. */
  public FlixelHeadlessRunner() {
    this(DEFAULT_RATE);
  }

  /**
   * Creates a runner with a chosen update rate.
   *
   * @param rate Updates per second, from 1 to 1000.
   */
  public FlixelHeadlessRunner(int rate) {
    setRate(rate);
  }

  /**
   * Creates the game, updates it at a steady rate until {@link #stop()} is called, and then
   * destroys it. This is called by {@link Flixel#start}, on the thread that becomes the game
   * thread.
   *
   * @param game The game to run.
   */
  @Override
  public void run(@NotNull FlixelGame game) {
    loopThread = Thread.currentThread();
    running = true;
    try {
      game.create();
      long previous = System.nanoTime();
      long next = previous;
      while (!stopRequested) {
        long step = NANOS_PER_SECOND / rate;
        next += step;
        long now = System.nanoTime();
        // After a long stall (a paused process, a slow disk) do not try to catch up by running
        // many updates back to back; start counting again from now.
        if (now - next > step * 4) {
          next = now;
        }
        while (now < next && !stopRequested) {
          LockSupport.parkNanos(next - now);
          now = System.nanoTime();
        }
        float delta = (now - previous) / (float) NANOS_PER_SECOND;
        previous = now;
        game.update(game.advanceTime(delta));
        game.endFrame();
        frames++;
      }
    } finally {
      try {
        game.destroy();
      } finally {
        running = false;
        synchronized (this) {
          notifyAll();
        }
      }
    }
  }

  /**
   * Asks the loop to finish. The current update completes, the game is destroyed, and
   * {@link #run} returns. Safe to call from any thread, and from inside the game.
   */
  public void stop() {
    stopRequested = true;
    Thread thread = loopThread;
    if (thread != null) {
      LockSupport.unpark(thread);
    }
  }

  /**
   * Asks the loop to finish and waits until the game has been destroyed.
   *
   * <p>Do not call this from the game thread; it would wait for itself. From inside the game,
   * use {@link #stop()}.
   *
   * @param millis The longest time to wait, in milliseconds.
   * @return Whether the loop finished in time.
   */
  public boolean stopAndWait(long millis) {
    stop();
    long deadline = System.currentTimeMillis() + millis;
    synchronized (this) {
      while (running) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
          return false;
        }
        try {
          wait(left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns whether the loop is running.
   *
   * @return {@code true} from the moment {@link #run} starts until the game has been destroyed.
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * Returns how many updates have run so far.
   *
   * @return The number of completed updates.
   */
  public long getFrames() {
    return frames;
  }

  public int getRate() {
    return rate;
  }

  /**
   * Changes how many times a second the game updates. Takes effect on the next update.
   *
   * @param rate Updates per second, from 1 to 1000.
   */
  public void setRate(int rate) {
    if (rate < 1 || rate > 1000) {
      throw new IllegalArgumentException("Update rate must be from 1 to 1000, got " + rate + ".");
    }
    this.rate = rate;
  }
}
