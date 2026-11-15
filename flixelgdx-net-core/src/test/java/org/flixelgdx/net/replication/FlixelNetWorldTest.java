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
package org.flixelgdx.net.replication;

import org.flixelgdx.net.FlixelNetSession;
import org.flixelgdx.net.message.FlixelNetProtocol;
import org.flixelgdx.net.message.NetMessage;
import org.flixelgdx.net.message.NetRange;
import org.flixelgdx.net.transport.FlixelNetLoopback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlixelNetWorldTest {

  private static final int BALL = 1;
  private static final int RUNNER = 2;
  private static final float FRAME = 1f / 60f;

  private FlixelNetLoopback loopback;
  private FlixelNetSession host;
  private FlixelNetSession client;
  private FlixelNetWorld hostWorld;
  private FlixelNetWorld clientWorld;

  @BeforeEach
  void setUp() {
    loopback = new FlixelNetLoopback();
    host = new FlixelNetSession(protocol());
    client = new FlixelNetSession(protocol());
    hostWorld = world(host);
    clientWorld = world(client);
    host.listen(loopback.getHost(), 0);
    client.join(loopback.newClient(), "");
    pump(5);
  }

  @Test
  void aWorldNeedsItsMessagesInTheProtocol() {
    FlixelNetSession bare = new FlixelNetSession(new FlixelNetProtocol("bare", 1));
    assertThrows(IllegalArgumentException.class, () -> new FlixelNetWorld(bare));
  }

  @Test
  void onlyTheHostSpawns() {
    assertThrows(IllegalStateException.class, () -> clientWorld.spawn(BALL, 0));
    assertThrows(IllegalArgumentException.class, () -> hostWorld.spawn(99, 0));
  }

  @Test
  void spawnedEntityAppearsOnTheClientWhereItWasPlaced() {
    Ball ball = hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    ball.x = 300f;
    pump(3);

    assertEquals(1, clientWorld.getEntityCount());
    Ball copy = (Ball) clientWorld.getEntity(ball.getId());
    assertNotNull(copy);
    assertEquals(300f, copy.x, 0.05f);
    assertEquals(1, copy.spawns);
    assertFalse(copy.isAuthority());
    assertTrue(ball.isAuthority());
  }

  @Test
  void clientFollowsAMovingEntitySmoothly() {
    Ball ball = hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    ball.speed = 120f;
    pump(10);
    Ball copy = (Ball) clientWorld.getEntity(ball.getId());
    float last = copy.x;
    int moved = 0;
    for (int i = 0; i < 60; i++) {
      pump(1);
      assertTrue(copy.x >= last - 0.05f, "the copy never moves backwards");
      if (copy.x > last + 0.5f) {
        moved++;
      }
      last = copy.x;
    }
    // Snapshots arrive on one frame in three; smoothing makes the copy move on nearly all of them.
    assertTrue(moved > 50, "moved on " + moved + " of 60 frames");
    // The copy trails the host by about the interpolation delay.
    float behind = ball.x - copy.x;
    assertTrue(behind > 4f && behind < 30f, "behind by " + behind);
  }

  @Test
  void withoutSmoothingTheCopyJumpsFromSnapshotToSnapshot() {
    clientWorld.setInterpolation(false);
    Ball ball = hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    ball.speed = 120f;
    pump(10);
    Ball copy = (Ball) clientWorld.getEntity(ball.getId());
    float last = copy.x;
    int moved = 0;
    for (int i = 0; i < 60; i++) {
      pump(1);
      if (copy.x > last + 0.5f) {
        moved++;
      }
      last = copy.x;
    }
    assertTrue(moved >= 15 && moved <= 25, "moved on " + moved + " of 60 frames");
  }

  @Test
  void despawnRemovesTheCopy() {
    Ball ball = hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    pump(3);
    Ball copy = (Ball) clientWorld.getEntity(ball.getId());
    hostWorld.despawn(ball);
    pump(3);

    assertFalse(ball.isAlive());
    assertFalse(copy.isAlive());
    assertEquals(1, copy.despawns);
    assertEquals(0, clientWorld.getEntityCount());
    assertNull(clientWorld.getEntity(ball.getId()));
  }

  @Test
  void aLateJoinerSeesWhatIsAlreadyThere() {
    Ball ball = hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    ball.x = 77f;
    pump(3);
    FlixelNetSession late = new FlixelNetSession(protocol());
    FlixelNetWorld lateWorld = world(late);
    late.join(loopback.newClient(), "");
    for (int i = 0; i < 6; i++) {
      pump(1);
      late.update(FRAME);
      lateWorld.update(FRAME);
    }
    assertEquals(1, lateWorld.getEntityCount());
    assertEquals(77f, ((Ball) lateWorld.getEntityAt(0)).x, 0.05f);
  }

  @Test
  void anEntityAtRestCostsAlmostNothing() {
    hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    pump(30);
    long before = host.getPeerAt(0).getBytesSent();
    pump(120);
    long resting = host.getPeerAt(0).getBytesSent() - before;

    Ball mover = (Ball) hostWorld.getEntityAt(0);
    mover.speed = 50f;
    before = host.getPeerAt(0).getBytesSent();
    pump(120);
    long moving = host.getPeerAt(0).getBytesSent() - before;
    assertTrue(resting * 4 < moving, "resting " + resting + " bytes, moving " + moving + " bytes");
  }

  @Test
  void ownedPawnRespondsAtOnceAndAgreesWithTheHost() {
    loopback.setLatency(0.1f);
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(30);
    Runner mine = (Runner) clientWorld.getEntity(runner.getId());
    assertTrue(mine.isOwned());
    assertFalse(runner.isOwned());

    mine.held = true;
    pump(1);
    // The host is 100 ms away, but the pawn has already moved.
    assertTrue(mine.x > 0f);
    assertEquals(0f, runner.x);

    pump(59);
    mine.held = false;
    pump(60);
    // Sixty ticks of input at 240 units a second; both sides end in the same place.
    assertEquals(240f, mine.x, 0.5f);
    assertEquals(240f, runner.x, 0.5f);
    assertEquals(0, mine.corrections);
  }

  @Test
  void theHostHasTheLastWord() {
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(10);
    Runner mine = (Runner) clientWorld.getEntity(runner.getId());
    mine.held = true;
    pump(30);
    mine.held = false;
    pump(10);
    // Something only the host knows about moves the pawn.
    runner.x = 500f;
    pump(20);
    assertEquals(500f, mine.x, 0.5f);
  }

  @Test
  void withoutPredictionThePawnWaitsForTheHost() {
    clientWorld.setPrediction(false);
    loopback.setLatency(0.1f);
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(30);
    Runner mine = (Runner) clientWorld.getEntity(runner.getId());
    mine.held = true;
    pump(3);
    assertEquals(0f, mine.x);
    pump(60);
    assertTrue(mine.x > 100f);
  }

  @Test
  void lostPacketsDoNotLoseInputs() {
    loopback.setSeed(7);
    loopback.setLoss(0.2f);
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(40);
    Runner mine = (Runner) clientWorld.getEntity(runner.getId());
    assertNotNull(mine);
    mine.held = true;
    pump(60);
    mine.held = false;
    loopback.setLoss(0f);
    pump(60);
    // With every input sent three times, losing three in a row is rare; allow a couple of ticks.
    assertEquals(240f, runner.x, 12f);
    assertEquals(runner.x, mine.x, 0.5f);
  }

  @Test
  void aClientCannotSpeedUpBySendingMoreInputs() {
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(10);
    Runner mine = (Runner) clientWorld.getEntity(runner.getId());
    mine.held = true;
    // The client's world runs four ticks for every one of the host's.
    for (int i = 0; i < 120; i++) {
      loopback.advance(FRAME);
      host.update(FRAME);
      hostWorld.update(FRAME);
      client.update(FRAME);
      clientWorld.update(FRAME * 4f);
    }
    float fair = 240f * 2f;
    assertTrue(runner.x < fair * 1.5f, "host moved the pawn " + runner.x);
  }

  @Test
  void hostOwnedPawnIsDrivenOnTheHost() {
    Runner runner = hostWorld.spawn(RUNNER, FlixelNetSession.HOST_ID);
    runner.held = true;
    pump(60);
    assertEquals(240f, runner.x, 5f);
    Runner copy = (Runner) clientWorld.getEntity(runner.getId());
    assertTrue(copy.x > 150f);
    assertFalse(copy.isOwned());
  }

  @Test
  void leavingClearsTheClientWorld() {
    hostWorld.spawn(BALL, FlixelNetSession.HOST_ID);
    Runner runner = hostWorld.spawn(RUNNER, client.getLocalId());
    pump(5);
    assertEquals(2, clientWorld.getEntityCount());
    hostWorld.despawnOwnedBy(client.getLocalId());
    pump(3);
    assertFalse(runner.isAlive());
    assertEquals(1, clientWorld.getEntityCount());
    host.close();
    pump(3);
    assertEquals(0, clientWorld.getEntityCount());
  }

  private void pump(int frames) {
    for (int i = 0; i < frames; i++) {
      loopback.advance(FRAME);
      host.update(FRAME);
      hostWorld.update(FRAME);
      client.update(FRAME);
      clientWorld.update(FRAME);
    }
  }

  private static FlixelNetProtocol protocol() {
    FlixelNetProtocol protocol = new FlixelNetProtocol("world-test", 1)
        .add(FlixelNetWorldTestSpotNetCodec.INSTANCE)
        .add(FlixelNetWorldTestKeysNetCodec.INSTANCE);
    FlixelNetWorld.addMessages(protocol);
    return protocol;
  }

  private static FlixelNetWorld world(FlixelNetSession session) {
    FlixelNetWorld world = new FlixelNetWorld(session);
    world.register(BALL, Spot.class, Ball::new);
    world.register(RUNNER, Spot.class, Keys.class, Runner::new);
    return world;
  }

  @NetMessage
  static class Spot {
    @NetRange(min = 0, max = 4096, bits = 20)
    float x;
  }

  @NetMessage
  static class Keys {
    boolean right;
  }

  static class Ball extends FlixelNetEntity<Spot> {
    float x;
    float speed;
    int spawns;
    int despawns;

    @Override
    protected void capture(Spot out) {
      out.x = x;
    }

    @Override
    protected void apply(Spot state) {
      x = state.x;
    }

    @Override
    protected void step(float dt) {
      x += speed * dt;
    }

    @Override
    protected void onSpawn() {
      spawns++;
    }

    @Override
    protected void onDespawn() {
      despawns++;
    }
  }

  static class Runner extends FlixelNetPawn<Spot, Keys> {
    float x;
    int corrections;
    boolean held;

    @Override
    protected void sample(Keys out) {
      out.right = held;
    }

    @Override
    protected void simulate(Keys input, float dt) {
      if (input.right) {
        x += 240f * dt;
      }
    }

    @Override
    protected void capture(Spot out) {
      out.x = x;
    }

    @Override
    protected void apply(Spot state) {
      x = state.x;
    }
  }
}
