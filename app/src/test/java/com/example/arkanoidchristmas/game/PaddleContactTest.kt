package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact time of a (possibly moving) paddle. Expected numbers are worked out by hand from the
 * contact instant t = (PADDLE_TOP - radius - y) / vy = (602 - y) / vy, not read from the engine.
 */
class PaddleContactTest {
    private val frame = 1f / 120f

    private fun engine(
        ball: Point,
        velocity: Point,
        paddleLeft: Float,
        targetCenter: Float? = null,
        level: Int = 1,
    ): GameEngine = GameEngine(
        GameEngine.newGame(level).copy(
            ball = ball,
            velocity = velocity,
            paddleLeft = paddleLeft,
            bricks = emptyList(),
            status = GameStatus.RUNNING,
        ),
    ).apply { if (targetCenter != null) setPaddleTarget(targetCenter) }

    /** Runs [total] seconds as [parts] equal update() calls. */
    private fun run(parts: Int, total: Float = frame, build: () -> GameEngine): GameState {
        val engine = build()
        repeat(parts) { engine.update(total / parts) }
        return engine.state
    }

    private fun assertSameForAllSplits(
        expectedVx: Float,
        expectedX: Float,
        expectedY: Float,
        tolerance: Float = 0.05f,
        total: Float = frame,
        build: () -> GameEngine,
    ) {
        for (parts in listOf(1, 2, 4)) {
            val state = run(parts, total, build)
            assertEquals("vx, $parts parts", expectedVx, state.velocity.x, tolerance)
            assertEquals("x, $parts parts", expectedX, state.ball.x, tolerance)
            assertEquals("y, $parts parts", expectedY, state.ball.y, tolerance)
            assertTrue("vy up, $parts parts", state.velocity.y < 0f)
        }
    }

    @Test fun reproOneMovingPaddleAnglePointsRightForEverySplit() {
        // Contact at t = 0.27/270 = 0.001 s: paddle left = 100 + 600*0.001 = 100.6, centre 150.6,
        // offset = (153 - 150.6)/50 = 0.048 -> vx = 10.56 (the old end-of-step paddle gave -8.8).
        // Rest 1/120 - 0.001 = 0.0073333 s: x = 153 + 10.56*rest, y = 602 - 270*rest.
        val rest = frame - 0.001f
        assertSameForAllSplits(10.56f, 153f + 10.56f * rest, 602f - 270f * rest) {
            engine(Point(153f, 601.73f), Point(0f, 270f), 100f, targetCenter = 350f)
        }
    }

    @Test fun reproTwoBallBesideMovingPaddleIsHitAndSteeredHardLeft() {
        // At contact the paddle's left edge is 100.6, the ball's right edge 85 + 18 = 103 >= 100.6: hit.
        // (At the end of the step the paddle was at 105 and the old code let the ball through.)
        // offset = (85 - 150.6)/50 = -1.312 -> clamped -1 -> vx = -220.
        val rest = frame - 0.001f
        assertSameForAllSplits(-220f, 85f - 220f * rest, 602f - 270f * rest) {
            engine(Point(85f, 601.73f), Point(0f, 270f), 100f, targetCenter = 350f)
        }
    }

    @Test fun paddleMovingLeftUsesItsPositionAtContact() {
        // left(0.001) = 200 - 0.6 = 199.4, centre 249.4, offset = (200 - 249.4)/50 = -0.988 -> vx = -217.36.
        val rest = frame - 0.001f
        assertSameForAllSplits(-217.36f, 200f - 217.36f * rest, 602f - 270f * rest) {
            engine(Point(200f, 601.73f), Point(0f, 270f), 200f, targetCenter = 50f)
        }
    }

    @Test fun paddleThatReachedItsTargetBeforeContactStopsThere() {
        // Target left edge 100.3 is reached after 0.0005 s < 0.001 s: contact sees 100.3 (centre 150.3),
        // not 100.6 -> offset = (153 - 150.3)/50 = 0.054 -> vx = 11.88.
        val rest = frame - 0.001f
        assertSameForAllSplits(11.88f, 153f + 11.88f * rest, 602f - 270f * rest) {
            engine(Point(153f, 601.73f), Point(0f, 270f), 100f, targetCenter = 150.3f)
        }
    }

    @Test fun contactInALaterSliceOfTheSameFrameGivesTheSameAngle() {
        // y = 602 - 270*0.006: contact 0.006 s into the frame, i.e. in the 2nd of two slices.
        // left(0.006) = 100 + 3.6 = 103.6, centre 153.6, offset = (153 - 153.6)/50 = -0.012 -> vx = -2.64.
        val total = 0.01f
        val rest = total - 0.006f
        assertSameForAllSplits(-2.64f, 153f - 2.64f * rest, 602f - 270f * rest, total = total) {
            engine(Point(153f, 602f - 270f * 0.006f), Point(0f, 270f), 100f, targetCenter = 350f)
        }
    }

    @Test fun staticPaddleCenterEdgeAndMissKeepTopOnlyHitbox() {
        // Centre: vx 0.
        val center = run(1) { engine(Point(200f, 601.73f), Point(0f, 270f), 150f) }
        assertEquals(0f, center.velocity.x, 0.001f)
        assertTrue(center.velocity.y < 0f)
        // Edge: ball right edge exactly past the paddle's left edge (132.1 + 18 = 150.1 >= 150) -> hit, clamped -220.
        val edge = run(1) { engine(Point(132.1f, 601.73f), Point(0f, 270f), 150f) }
        assertEquals(-220f, edge.velocity.x, 0.001f)
        assertTrue(edge.velocity.y < 0f)
        // Miss: 131.9 + 18 = 149.9 < 150 -> keeps falling, no deflection.
        val miss = run(1) { engine(Point(131.9f, 601.73f), Point(0f, 270f), 150f) }
        assertEquals(0f, miss.velocity.x, 0.001f)
        assertEquals(270f, miss.velocity.y, 0.001f)
        assertEquals(601.73f + 270f * frame, miss.ball.y, 0.01f)
    }

    @Test fun wallHitBeforeContactMirrorsPositionAndKeepsBallInside() {
        // Ball at 18.1 flies left at 300: wall at t=0.000333, contact at 0.001 with x = 18 + (18 - 17.8) = 18.2.
        // Paddle left 0, centre 50: offset = (18.2 - 50)/50 = -0.636 -> vx = -139.92; the rest
        // (0.0073333 s) moves it to 18.2 - 1.0261 = 17.1739 -> mirrored to 18.8261, vx = +139.92.
        val state = run(1) { engine(Point(18.1f, 601.73f), Point(-300f, 270f), 0f) }
        assertEquals(139.92f, state.velocity.x, 0.05f)
        assertEquals(18.826f, state.ball.x, 0.02f)
        assertTrue(state.ball.x >= GameWorld.BALL_RADIUS)
        assertTrue(state.velocity.y < 0f)
    }

    @Test fun wallHitAfterContactMirrorsRestOfTheFrame() {
        // Right side, paddle left 300 (centre 350). Ball x 381.5 with vx 0: contact at 0.001 s,
        // offset = (381.5 - 350)/50 = 0.63 -> vx = 138.6; rest moves it to 381.5 + 1.0164 = 382.5164,
        // mirrored at 382 -> 381.4836 with vx = -138.6.
        val state = run(1) { engine(Point(381.5f, 601.73f), Point(0f, 270f), 300f) }
        assertEquals(-138.6f, state.velocity.x, 0.05f)
        assertEquals(381.4836f, state.ball.x, 0.02f)
        assertTrue(state.ball.x <= GameWorld.WIDTH - GameWorld.BALL_RADIUS)
    }

    @Test fun wallReachedExactlyAtContactStaysInsideWithCorrectSign() {
        // x(0.001) = 18.3 - 0.3 = 18.0 exactly at the wall: offset = (18 - 50)/50 = -0.64 -> vx = -140.8;
        // the rest moves it to 18 - 1.0325 = 16.9675 -> mirrored to 19.0325, vx = +140.8.
        val state = run(1) { engine(Point(18.3f, 601.73f), Point(-300f, 270f), 0f) }
        assertEquals(140.8f, state.velocity.x, 0.05f)
        assertEquals(19.0325f, state.ball.x, 0.02f)
        assertTrue(state.ball.x >= GameWorld.BALL_RADIUS)
    }

    @Test fun ballAlreadyBelowPaddlePlaneIsNotLiftedOrDeflected() {
        val state = run(1) { engine(Point(200f, 613f), Point(0f, 270f), 150f) }
        assertEquals(270f, state.velocity.y, 0.001f)
        assertEquals(0f, state.velocity.x, 0.001f)
    }

    @Test fun contactExactlyOnLeftWallWithNoTimeLeftPointsInwards() {
        // 1/128 s is exact in binary: contact t = (602 - 599.890625)/270 = 1/128, rest = 0.
        // x(t) = 19.5625 - 200/128 = 18.0 (on the wall); paddle left 0, offset = (18 - 50)/50 = -0.64,
        // so the raw vx is -140.8 (outward) and must become +140.8 with x kept at 18.
        val total = 1f / 128f
        assertSameForAllSplits(140.8f, 18f, 602f - 0f, total = total) {
            engine(Point(19.5625f, 599.890625f), Point(-200f, 270f), 0f)
        }
        val state = run(1, total) { engine(Point(19.5625f, 599.890625f), Point(-200f, 270f), 0f) }
        assertTrue(state.ball.x >= GameWorld.BALL_RADIUS)
    }

    @Test fun contactExactlyOnRightWallWithNoTimeLeftPointsInwards() {
        // Mirror image: x(t) = 380.4375 + 200/128 = 382.0; paddle left 300 (centre 350),
        // offset = (382 - 350)/50 = 0.64 -> raw vx +140.8 (outward) -> must be -140.8, x = 382.
        val total = 1f / 128f
        assertSameForAllSplits(-140.8f, 382f, 602f - 0f, total = total) {
            engine(Point(380.4375f, 599.890625f), Point(200f, 270f), 300f)
        }
        val state = run(1, total) { engine(Point(380.4375f, 599.890625f), Point(200f, 270f), 300f) }
        assertTrue(state.ball.x <= GameWorld.WIDTH - GameWorld.BALL_RADIUS)
    }

    @Test fun ballAlreadyHeadingInwardOnTheWallAfterContactIsNotFlipped() {
        // Right wall, ball right of nothing: the paddle centre lies beyond the ball, so offset < 0 and
        // the new vx already points inwards at x = 382 (rest = 0) -- it must not be mirrored again.
        // Paddle left 340 (centre 390): offset = (382 - 390)/50 = -0.16 -> vx = -35.2 stays -35.2.
        val total = 1f / 128f
        val state = run(1, total) { engine(Point(380.4375f, 599.890625f), Point(200f, 270f), 340f) }
        assertEquals(-35.2f, state.velocity.x, 0.05f)
        assertEquals(382f, state.ball.x, 0.01f)
    }

    // ---- Level 4: ball speed factor 1.3 (vy 351, vx = offset * 220 * 1.3), paddle speed unchanged ----

    private fun level4(ball: Point, vx: Float, paddleLeft: Float, target: Float? = null) =
        engine(ball, Point(vx, 351f), paddleLeft, target, level = 4)

    @Test fun levelFourReproOneScalesAngleButNotPaddleTravel() {
        // Contact t = (602 - 601.649)/351 = 0.001 s, paddle left 100.6 (still 600/s), centre 150.6:
        // offset = 0.048 -> vx = 0.048 * 220 * 1.3 = 13.728; vy keeps its magnitude 351.
        val rest = frame - 0.001f
        for (parts in listOf(1, 2, 4)) {
            val state = run(parts) { level4(Point(153f, 601.649f), 0f, 100f, target = 600f) }
            assertEquals("vx $parts", 13.728f, state.velocity.x, 0.05f)
            assertEquals("vy $parts", -351f, state.velocity.y, 0.01f)
            assertEquals("x $parts", 153f + 13.728f * rest, state.ball.x, 0.05f)
            assertEquals("y $parts", 602f - 351f * rest, state.ball.y, 0.05f)
        }
    }

    @Test fun levelFourReproTwoStillHitsBesideMovingPaddleWithScaledClamp() {
        // Ball right edge 103 >= paddle left 100.6 at contact; offset -1.312 clamps to -1 -> vx = -220 * 1.3 = -286.
        val rest = frame - 0.001f
        for (parts in listOf(1, 2, 4)) {
            val state = run(parts) { level4(Point(85f, 601.649f), 0f, 100f, target = 600f) }
            assertEquals("vx $parts", -286f, state.velocity.x, 0.05f)
            assertEquals("vy $parts", -351f, state.velocity.y, 0.01f)
            assertEquals("x $parts", 85f - 286f * rest, state.ball.x, 0.05f)
        }
    }

    @Test fun levelFourWallEndpointsPointInwardsWithScaledAngle() {
        // tc = 2.7421875/351 = 1/128 exactly, rest 0. Left: x = 18, offset -0.64 -> vx -183.04 -> +183.04.
        val total = 1f / 128f
        for (parts in listOf(1, 2, 4)) {
            val left = run(parts, total) { level4(Point(19.5625f, 599.2578125f), -200f, 0f) }
            assertEquals("left vx $parts", 183.04f, left.velocity.x, 0.05f)
            assertEquals("left x $parts", 18f, left.ball.x, 0.05f)
            assertTrue(left.ball.x >= GameWorld.BALL_RADIUS)
            val right = run(parts, total) { level4(Point(380.4375f, 599.2578125f), 200f, 300f) }
            assertEquals("right vx $parts", -183.04f, right.velocity.x, 0.05f)
            assertEquals("right x $parts", 382f, right.ball.x, 0.05f)
            assertTrue(right.ball.x <= GameWorld.WIDTH - GameWorld.BALL_RADIUS)
        }
    }

    @Test fun levelFourWallBeforeContactMirrorsWithScaledAngle() {
        // As the level-1 case but vy 351 (contact still at 0.001 s): x(tc) = 18.2, offset -0.636,
        // vx = -0.636 * 286 = -181.896, rest moves x to 18.2 - 1.3339 = 16.8661 -> mirrored 19.1339, vx +181.896.
        val state = run(1) { level4(Point(18.1f, 601.649f), -300f, 0f) }
        assertEquals(181.896f, state.velocity.x, 0.05f)
        assertEquals(19.134f, state.ball.x, 0.02f)
    }

    @Test fun repeatedPaddleHitsKeepTheLevelSpeedWithoutAccumulating() {
        // Straight up and down on the centre of a static paddle: every bounce must keep |vy| = 351.
        val engine = level4(Point(200f, 601.649f), 0f, 150f)
        var contacts = 0
        var previousVy = engine.state.velocity.y
        repeat(900) {
            engine.update(1f / 60f)
            val vy = engine.state.velocity.y
            if (vy < 0f && previousVy > 0f) contacts++
            assertEquals(351f, kotlin.math.abs(vy), 0.01f)
            assertEquals(GameStatus.RUNNING, engine.state.status)
            previousVy = vy
        }
        assertTrue("expected several paddle contacts, got $contacts", contacts >= 3)
    }

    @Test fun lostLifeOnLevelFourRestoresThatLevelsStartVelocityOnce() {
        val engine = engine(Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f), Point(0f, 351f), 150f, level = 4)
        engine.update(0.02f)
        assertEquals(GameStatus.READY, engine.state.status)
        assertEquals(169f, engine.state.velocity.x, 0.001f)
        assertEquals(-351f, engine.state.velocity.y, 0.001f)
        engine.start()
        engine.update(0f)
        assertEquals(-351f, engine.state.velocity.y, 0.001f)
    }
}
