package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {
    @Test fun newGameStartsReadyWithEightBricks() {
        val state = GameEngine().state

        assertEquals(GameStatus.READY, state.status)
        assertEquals(8, state.bricks.size)
        assertEquals(0, state.score)
        assertEquals(GameWorld.WIDTH / 2f, state.ball.x)
    }

    @Test fun gameDoesNotMoveBeforeStartOrAfterFinish() {
        val engine = GameEngine()
        val initial = engine.state
        engine.update(0.1f)
        assertEquals(initial, engine.state)

        engine.start()
        engine.update(0.01f)
        assertFalse(engine.state.ball == initial.ball)

        val won = engine.state.copy(status = GameStatus.WON)
        val finishedEngine = GameEngine(won)
        finishedEngine.update(1f)
        finishedEngine.start()
        assertEquals(won, finishedEngine.state)
    }

    @Test fun paddleStaysWithinFieldAndCarriesReadyBall() {
        val engine = GameEngine()
        engine.movePaddleTo(-100f)
        assertEquals(0f, engine.state.paddleLeft)
        assertEquals(GameWorld.PADDLE_WIDTH / 2f, engine.state.ball.x)

        engine.movePaddleTo(1000f)
        assertEquals(GameWorld.WIDTH - GameWorld.PADDLE_WIDTH, engine.state.paddleLeft)
        assertEquals(GameWorld.WIDTH - GameWorld.PADDLE_WIDTH / 2f, engine.state.ball.x)
    }

    @Test fun ballReflectsFromLeftRightAndTopBoundaries() {
        val left = running(Point(GameWorld.BALL_RADIUS + 1f, 300f), Point(-200f, 0f))
        left.update(0.02f)
        assertTrue(left.state.velocity.x > 0f)
        assertTrue(left.state.ball.x >= GameWorld.BALL_RADIUS)

        val right = running(Point(GameWorld.WIDTH - GameWorld.BALL_RADIUS - 1f, 300f), Point(200f, 0f))
        right.update(0.02f)
        assertTrue(right.state.velocity.x < 0f)
        assertTrue(right.state.ball.x <= GameWorld.WIDTH - GameWorld.BALL_RADIUS)

        val top = running(Point(200f, GameWorld.BALL_RADIUS + 1f), Point(0f, -200f))
        top.update(0.02f)
        assertTrue(top.state.velocity.y > 0f)
        assertTrue(top.state.ball.y >= GameWorld.BALL_RADIUS)
    }

    @Test fun centerPaddleHitGoesStraightUp() {
        val engine = paddleHitAt(200f)

        assertTrue(engine.state.velocity.y < 0f)
        assertEquals(0f, engine.state.velocity.x, 0.001f)
        assertTrue(engine.state.ball.y <= GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS)
    }

    @Test fun edgePaddleHitsSteerBall() {
        assertTrue(paddleHitAt(165f).state.velocity.x < 0f)
        assertTrue(paddleHitAt(235f).state.velocity.x > 0f)
    }

    @Test fun missedPaddleCausesLossAndStaysTerminal() {
        val engine = running(
            Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f),
            Point(0f, 200f),
        )
        engine.update(0.02f)

        assertEquals(GameStatus.LOST, engine.state.status)
        val lost = engine.state
        engine.update(0.1f)
        assertEquals(lost, engine.state)
    }

    @Test fun brickHitRemovesOneBrickScoresOnceAndReflects() {
        val engine = brickHitEngine(GameEngine.newGame().bricks)
        engine.update(0.02f)

        assertEquals(7, engine.state.bricks.size)
        assertEquals(GameWorld.BRICK_SCORE, engine.state.score)
        assertTrue(engine.state.velocity.y > 0f)
        assertEquals(GameStatus.RUNNING, engine.state.status)
    }

    @Test fun lastBrickEndsGameWithWin() {
        val engine = brickHitEngine(GameEngine.newGame().bricks.take(1))
        engine.update(0.02f)

        assertEquals(GameStatus.WON, engine.state.status)
        assertTrue(engine.state.bricks.isEmpty())
        assertEquals(GameWorld.BRICK_SCORE, engine.state.score)
    }

    @Test fun oneFrameCannotRemoveTwoAdjacentBricks() {
        val bricks = GameEngine.newGame().bricks.take(2)
        val xBetweenBricks = (bricks[0].right + bricks[1].left) / 2f
        val engine = running(
            Point(xBetweenBricks, bricks[0].bottom + GameWorld.BALL_RADIUS + 1f),
            Point(0f, -200f),
            bricks,
        )
        engine.update(0.02f)

        assertEquals(1, engine.state.bricks.size)
        assertEquals(GameWorld.BRICK_SCORE, engine.state.score)
    }

    @Test fun delayedFrameHasBoundedMovement() {
        val engine = running(Point(200f, 400f), Point(0f, -200f))
        engine.update(10f)
        assertTrue(engine.state.ball.y >= 380f)
        assertTrue(engine.state.ball.y < 400f)
    }

    @Test fun restartClearsScoreAndRestoresBricks() {
        val engine = brickHitEngine(GameEngine.newGame().bricks)
        engine.update(0.02f)
        engine.restart()

        assertEquals(GameEngine.newGame(), engine.state)
    }

    private fun paddleHitAt(x: Float): GameEngine = running(
        Point(x, GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS - 1f),
        Point(0f, 200f),
    ).apply { update(0.02f) }

    private fun brickHitEngine(bricks: List<Brick>): GameEngine = running(
        Point(bricks.first().left + bricks.first().width / 2f,
            bricks.first().bottom + GameWorld.BALL_RADIUS + 1f),
        Point(0f, -200f),
        bricks,
    )

    private fun running(
        ball: Point,
        velocity: Point,
        bricks: List<Brick> = GameEngine.newGame().bricks,
    ): GameEngine = GameEngine(
        GameEngine.newGame().copy(
            ball = ball,
            velocity = velocity,
            bricks = bricks,
            status = GameStatus.RUNNING,
        ),
    )
}
