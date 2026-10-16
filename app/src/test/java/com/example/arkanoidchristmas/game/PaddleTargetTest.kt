package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleTargetTest {
    private val maxLeft = GameWorld.WIDTH - GameWorld.PADDLE_WIDTH
    private fun centerFor(left: Float) = left + GameWorld.PADDLE_WIDTH / 2f

    @Test fun touchTargetDoesNotMovePaddleInstantly() {
        val engine = running(paddleLeft = 0f)
        val before = engine.state
        engine.setPaddleTarget(centerFor(maxLeft))
        assertEquals(before, engine.state)
    }

    @Test fun paddleMovesAtMostMaxSpeedTowardTarget() {
        val engine = running(paddleLeft = 0f)
        engine.setPaddleTarget(centerFor(maxLeft))
        engine.update(0.05f)
        assertEquals(GameWorld.PADDLE_SPEED * 0.05f, engine.state.paddleLeft, 0.01f)

        // Fine-grained frames cannot beat the speed limit either.
        repeat(10) { engine.update(1f / 60f) }
        val expected = GameWorld.PADDLE_SPEED * (0.05f + 10f / 60f)
        assertEquals(expected, engine.state.paddleLeft, 0.05f)
    }

    @Test fun movesToTargetWithoutOvershootAndThenStops() {
        val engine = running(paddleLeft = 100f)
        engine.setPaddleTarget(centerFor(105f))
        engine.update(0.1f)
        assertEquals(105f, engine.state.paddleLeft, 0f)
        engine.update(0.1f)
        assertEquals(105f, engine.state.paddleLeft, 0f)

        val left = running(paddleLeft = 100f)
        left.setPaddleTarget(centerFor(40f))
        repeat(60) { left.update(1f / 60f) }
        assertEquals(40f, left.state.paddleLeft, 0f)
    }

    @Test fun targetOutsideFieldIsClampedAndFullTravelTakesAboutHalfSecond() {
        val engine = running(paddleLeft = 0f)
        engine.setPaddleTarget(10_000f)
        repeat(29) { engine.update(1f / 60f) }
        assertTrue(engine.state.paddleLeft < maxLeft)
        repeat(10) { engine.update(1f / 60f) }
        assertEquals(maxLeft, engine.state.paddleLeft, 0f)

        engine.setPaddleTarget(-10_000f)
        repeat(60) { engine.update(1f / 60f) }
        assertEquals(0f, engine.state.paddleLeft, 0f)
    }

    @Test fun newTargetChangesDirectionAndMovementContinuesWithoutFurtherInput() {
        val engine = running(paddleLeft = 100f)
        engine.setPaddleTarget(centerFor(300f))
        engine.update(0.1f)
        val afterFirst = engine.state.paddleLeft
        assertTrue(afterFirst > 100f)

        engine.setPaddleTarget(centerFor(0f))
        engine.update(0.05f)
        assertTrue(engine.state.paddleLeft < afterFirst)

        repeat(60) { engine.update(1f / 60f) }
        assertEquals(0f, engine.state.paddleLeft, 0f)
    }

    @Test fun invalidAndHugeDeltasNeverTeleportPaddle() {
        for (delta in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val engine = running(paddleLeft = 0f)
            engine.setPaddleTarget(centerFor(maxLeft))
            engine.update(delta)
            assertEquals("delta=$delta", 0f, engine.state.paddleLeft, 0f)
        }
        val engine = running(paddleLeft = 0f)
        engine.setPaddleTarget(centerFor(maxLeft))
        engine.update(10f)
        // A delayed frame is capped at 0.1 s, i.e. 60 units.
        assertEquals(GameWorld.PADDLE_SPEED * 0.1f, engine.state.paddleLeft, 0.05f)
        engine.setPaddleTarget(Float.NaN)
        engine.update(0.01f)
        assertTrue(engine.state.paddleLeft > 60f)
    }

    @Test fun finishedGameIgnoresTargetsAndStaysFrozen() {
        for (status in listOf(GameStatus.WON, GameStatus.LOST)) {
            val engine = GameEngine(running(paddleLeft = 100f).state.copy(status = status))
            val before = engine.state
            engine.setPaddleTarget(centerFor(0f))
            engine.update(0.1f)
            assertEquals(status.name, before, engine.state)
        }
    }

    @Test fun targetSetWhileRunningDoesNotMoveFinishedGame() {
        val engine = running(
            paddleLeft = 100f,
            ball = Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f),
            lives = 1,
        )
        engine.setPaddleTarget(centerFor(maxLeft))
        engine.update(0.02f)
        assertEquals(GameStatus.LOST, engine.state.status)
        val lost = engine.state
        engine.update(0.1f)
        assertEquals(lost, engine.state)
    }

    @Test fun restartClearsTarget() {
        val engine = running(paddleLeft = 0f)
        engine.setPaddleTarget(centerFor(maxLeft))
        engine.restart()
        engine.start()
        engine.update(0.1f)
        assertEquals(GameEngine.newGame().paddleLeft, engine.state.paddleLeft, 0f)
    }

    @Test fun directMovesAndKeysCancelTouchTarget() {
        val engine = running(paddleLeft = 100f)
        engine.setPaddleTarget(centerFor(maxLeft))
        engine.movePaddleBy(-GameWorld.PADDLE_STEP)
        assertEquals(100f - GameWorld.PADDLE_STEP, engine.state.paddleLeft, 0.001f)
        engine.update(0.1f)
        assertEquals(100f - GameWorld.PADDLE_STEP, engine.state.paddleLeft, 0.001f)
    }

    @Test fun readyStateStillAimsDirectlyAndCarriesBall() {
        val engine = GameEngine()
        engine.setPaddleTarget(centerFor(0f))
        assertEquals(0f, engine.state.paddleLeft, 0f)
        assertEquals(GameWorld.PADDLE_WIDTH / 2f, engine.state.ball.x, 0f)
    }

    private fun running(
        paddleLeft: Float,
        ball: Point = Point(200f, 400f),
        lives: Int = GameWorld.MAX_LIVES,
    ): GameEngine = GameEngine(
        GameEngine.newGame().copy(
            lives = lives,
            ball = ball,
            velocity = if (ball.y > GameWorld.HEIGHT) Point(0f, 200f) else Point(0f, 0f),
            paddleLeft = paddleLeft,
            status = GameStatus.RUNNING,
        ),
    )
}
