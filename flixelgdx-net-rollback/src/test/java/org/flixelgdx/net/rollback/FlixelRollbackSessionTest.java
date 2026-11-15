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
import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.flixelgdx.net.message.NetRange;
import org.flixelgdx.net.transport.FlixelNetLoopback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelRollbackSessionTest {

  private static final float FRAME = 1f / 60f;

  private FlixelNetLoopback loopback;
  private Side[] sides;

  @BeforeEach
  void setUp() {
    loopback = new FlixelNetLoopback();
  }

  @Test
  void onlyTheHostStartsAndOnlyWithCompany() {
    Side alone = new Side();
    alone.session.listen(new FlixelNetLoopback().getHost(), 0);
    assertThrows(IllegalStateException.class, alone.match::start);
    connect(2);
    assertThrows(IllegalStateException.class, sides[1].match::start);
  }

  @Test
  void everyoneLearnsTheirPlayerNumberAndTheHostsSettings() {
    connect(3);
    sides[0].match.setInputDelay(3);
    sides[0].match.setTickRate(30);
    sides[0].match.start();
    pump(5);
    for (int i = 0; i < 3; i++) {
      assertTrue(sides[i].match.isRunning());
      assertEquals(i, sides[i].match.getLocalPlayer());
      assertEquals(3, sides[i].match.getPlayerCount());
      assertEquals(3, sides[i].match.getInputDelay());
      assertEquals(30, sides[i].match.getTickRate());
      assertEquals(1, sides[i].starts);
    }
    assertThrows(IllegalStateException.class, () -> sides[0].match.setInputDelay(1));
  }

  @Test
  void twoPlayersStayInStepOverASlowConnection() {
    loopback.setLatency(0.08f);
    connect(2);
    sides[0].match.start();
    play(600);
    settle();

    assertSameGame();
    assertTrue(sides[0].match.getRollbacks() > 0, "a slow connection forces some rewinds");
    assertTrue(sides[1].match.getRollbacks() > 0);
    assertTrue(sides[0].match.getRollbackDepth() <= sides[0].match.getMaxPrediction());
    assertTrue(sides[0].match.getChecksumFrame() > 300);
    // Real time was kept: ten seconds of play is close to 600 ticks.
    assertTrue(sides[0].match.getFrame() > 540, "host reached tick " + sides[0].match.getFrame());
  }

  @Test
  void threePlayersStayInStepThroughTheHost() {
    loopback.setLatency(0.03f);
    connect(3);
    sides[0].match.start();
    play(400);
    settle();
    assertSameGame();
    // Each player moved their own piece, which only works if the inputs reached everyone.
    for (Side side : sides) {
      assertNotEquals(0, side.game.x[0]);
      assertNotEquals(0, side.game.x[1]);
      assertNotEquals(0, side.game.x[2]);
    }
  }

  @Test
  void enoughInputDelayMeansNoRewindsAtAll() {
    loopback.setLatency(0.03f);
    connect(2);
    sides[0].match.setInputDelay(8);
    sides[0].match.start();
    play(300);
    settle();
    assertSameGame();
    assertEquals(0, sides[0].match.getRollbacks());
    assertEquals(0, sides[1].match.getRollbacks());
  }

  @Test
  void aComputerNeverGuessesFurtherThanTheLimit() {
    connect(2);
    sides[0].match.setMaxPrediction(4);
    sides[0].match.start();
    pump(10);
    // The client stops updating, as if its connection froze.
    for (int i = 0; i < 120; i++) {
      loopback.advance(FRAME);
      sides[0].update();
    }
    int reached = sides[0].match.getFrame();
    assertTrue(sides[0].match.getStalls() > 90, "the host waited " + sides[0].match.getStalls() + " ticks");
    pump(120);
    settle();
    assertSameGame();
    assertTrue(sides[0].match.getFrame() > reached + 60);
  }

  @Test
  void aPlayerLeavingGoesIdleAtTheSameTickForEveryone() {
    loopback.setLatency(0.03f);
    connect(3);
    sides[0].match.start();
    play(120);
    sides[2].session.close();
    Side gone = sides[2];
    sides = new Side[] { sides[0], sides[1] };
    play(200);
    settle();

    assertSameGame();
    assertEquals(1, sides[0].left);
    assertEquals(1, sides[1].left);
    assertTrue(sides[0].match.isGone(2));
    assertTrue(sides[1].match.isGone(2));
    assertFalse(gone.match.isRunning());
    assertEquals(1, gone.ended);
    // The match went on without them.
    assertTrue(sides[0].match.getFrame() > 250);
  }

  @Test
  void aGameThatIsNotDeterministicIsCaught() {
    connect(2);
    sides[1].game.cheat = true;
    sides[0].match.start();
    play(200);
    assertTrue(sides[0].match.isDesynced());
    assertTrue(sides[1].match.isDesynced());
    assertEquals(1, sides[0].desyncs);
  }

  @Test
  void theSharedRandomIsTheSameForEveryoneAndRewindsWithTheGame() {
    FlixelRollbackRandom a = new FlixelRollbackRandom();
    FlixelRollbackRandom b = new FlixelRollbackRandom();
    a.setState(42L);
    b.setState(42L);
    for (int i = 0; i < 100; i++) {
      assertEquals(a.nextInt(1000), b.nextInt(1000));
      float f = a.nextFloat();
      assertTrue(f >= 0f && f < 1f);
      b.nextFloat();
    }
    long saved = a.getState();
    long next = a.nextLong();
    a.setState(saved);
    assertEquals(next, a.nextLong());
    assertThrows(IllegalArgumentException.class, () -> a.nextInt(0));
  }

  private void connect(int count) {
    sides = new Side[count];
    for (int i = 0; i < count; i++) {
      sides[i] = new Side();
    }
    sides[0].session.listen(loopback.getHost(), 0);
    for (int i = 1; i < count; i++) {
      sides[i].session.join(loopback.newClient(), "");
      // Joining one at a time fixes the order, so player numbers match the array.
      for (int f = 0; f < 20; f++) {
        loopback.advance(FRAME);
        for (int j = 0; j <= i; j++) {
          sides[j].session.update(FRAME);
        }
      }
    }
  }

  /** Plays with every player pressing and releasing their button on a different rhythm. */
  private void play(int frames) {
    for (int f = 0; f < frames; f++) {
      for (int i = 0; i < sides.length; i++) {
        sides[i].game.held = (f / (7 + sides[i].match.getLocalPlayer() * 4)) % 2 == 0;
      }
      pump(1);
    }
  }

  /** Lets go of every button and waits until every input has arrived everywhere. */
  private void settle() {
    for (Side side : sides) {
      side.game.held = false;
    }
    pump(120);
  }

  private void pump(int frames) {
    for (int f = 0; f < frames; f++) {
      loopback.advance(FRAME);
      for (Side side : sides) {
        side.update();
      }
    }
  }

  /** Checks that every computer shows the same game, compared at a tick they have all reached. */
  private void assertSameGame() {
    for (Side side : sides) {
      assertFalse(side.match.isDesynced(), "no desync was reported");
      assertEquals(0, side.desyncs);
    }
    // After settling, every player is idle, so pieces no longer move and only the tick differs.
    for (int i = 1; i < sides.length; i++) {
      for (int p = 0; p < 3; p++) {
        assertEquals(sides[0].game.x[p], sides[i].game.x[p], "piece " + p + " as seen by player " + i);
      }
      assertEquals(sides[0].game.luck, sides[i].game.luck);
    }
  }

  private static FlixelNetProtocol protocol() {
    FlixelNetProtocol protocol = new FlixelNetProtocol("rollback-test", 1)
        .add(FlixelRollbackSessionTestPadNetCodec.INSTANCE);
    FlixelRollbackSession.addMessages(protocol);
    return protocol;
  }

  @NetMessage
  static class Pad {
    boolean right;
    @NetRange(min = 0, max = 3)
    int power;
  }

  /** One computer: a network session, a match, and its own copy of the game. */
  private static final class Side {
    final FlixelNetSession session = new FlixelNetSession(protocol());
    final Game game = new Game();
    final FlixelRollbackSession<Pad> match;
    int starts;
    int desyncs;
    int left;
    int ended;

    Side() {
      match = new FlixelRollbackSession<>(session, Pad.class, game);
      game.match = match;
      match.onStart.add(nothing -> starts++);
      match.onDesync.add(at -> desyncs++);
      match.onPlayerLeft.add(player -> left++);
      match.onEnd.add(nothing -> ended++);
    }

    void update() {
      session.update(FRAME);
      match.update(FRAME);
    }
  }

  /** Each player slides a piece to the right while holding a button; luck adds a random drift. */
  private static final class Game implements FlixelRollbackGame<Pad> {
    final int[] x = new int[3];
    FlixelRollbackSession<Pad> match;
    int luck;
    int moving;
    boolean held;
    boolean cheat;

    @Override
    public void sample(Pad out) {
      out.right = held;
      out.power = held ? 2 : 0;
    }

    @Override
    public void step(FlixelRollbackInputs<Pad> inputs) {
      moving = 0;
      for (int p = 0; p < inputs.getCount(); p++) {
        Pad pad = inputs.get(p);
        if (pad.right) {
          x[p] += 1 + pad.power;
          moving++;
        }
      }
      // Random numbers are only drawn while someone moves, so a settled game holds still.
      if (moving > 0) {
        luck += match.getRandom().nextInt(5);
        if (cheat && inputs.getFrame() == 50) {
          luck += 1;
        }
      }
    }

    @Override
    public void save(FlixelNetBuffer out) {
      out.writeInt(x[0]);
      out.writeInt(x[1]);
      out.writeInt(x[2]);
      out.writeInt(luck);
    }

    @Override
    public void load(FlixelNetBuffer in) {
      x[0] = in.readInt();
      x[1] = in.readInt();
      x[2] = in.readInt();
      luck = in.readInt();
    }
  }
}
