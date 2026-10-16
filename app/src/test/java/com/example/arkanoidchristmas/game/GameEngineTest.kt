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

    @Test fun missedPaddleOnLastLifeCausesLossAndStaysTerminal() {
        val engine = running(
            Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f),
            Point(0f, 200f),
            lives = 1,
        )
        engine.update(0.02f)

        assertEquals(GameStatus.LOST, engine.state.status)
        assertEquals(0, engine.state.lives)
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
        val engine = brickHitEngine(listOf(GameEngine.newGame().bricks.last()))
        engine.update(0.02f)

        assertEquals(GameStatus.WON, engine.state.status)
        assertTrue(engine.state.bricks.isEmpty())
        assertEquals(GameWorld.BRICK_SCORE, engine.state.score)
    }

    @Test fun oneFrameCannotRemoveTwoAdjacentBricks() {
        // Two bricks of the same row with the smallest gap between them (level 1 is a tree, so the
        // first two list entries are no longer neighbours).
        val all = GameEngine.newGame().bricks
        val first = all.first { a -> all.any { b -> b.top == a.top && b.left > a.left } }
        val bricks = listOf(first, all.filter { it.top == first.top && it.left > first.left }.minByOrNull { it.left }!!)
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

    @Test fun runningGameIgnoresNonPositiveAndNonFiniteFrames() {
        val deltas = listOf(0f, -0.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

        // Each delta gets a fresh engine and all mismatches are reported together.
        val changed = deltas.filter { delta ->
            val engine = running(Point(200f, 400f), Point(130f, -270f))
            val before = engine.state
            engine.update(delta)
            engine.state != before
        }

        assertEquals(emptyList<Float>(), changed)
    }

    @Test fun finishedGameIgnoresPaddleMovement() {
        val moves = listOf<Pair<String, (GameEngine) -> Unit>>(
            "movePaddleTo(left)" to { it.movePaddleTo(0f) },
            "movePaddleTo(right)" to { it.movePaddleTo(GameWorld.WIDTH) },
            "movePaddleBy(+step)" to { it.movePaddleBy(GameWorld.PADDLE_STEP) },
            "movePaddleBy(-step)" to { it.movePaddleBy(-GameWorld.PADDLE_STEP) },
        )

        val changed = listOf(GameStatus.WON, GameStatus.LOST).flatMap { status ->
            moves.mapNotNull { (name, move) ->
                val engine = GameEngine(
                    running(Point(120f, 300f), Point(130f, -270f)).state
                        .copy(paddleLeft = 120f, status = status),
                )
                val before = engine.state
                move(engine)
                if (engine.state != before) "$status $name" else null
            }
        }

        assertEquals(emptyList<String>(), changed)
    }

    private fun paddleHitAt(x: Float): GameEngine = running(
        Point(x, GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS - 1f),
        Point(0f, 200f),
    ).apply { update(0.02f) }

    /** Ball just below the lowest brick of the first column, so only that brick can be hit. */
    private fun brickHitEngine(bricks: List<Brick>): GameEngine {
        val target = bricks.maxByOrNull { it.bottom }!!
        return running(
            Point(target.left + target.width / 2f, target.bottom + GameWorld.BALL_RADIUS + 1f),
            Point(0f, -200f),
            bricks,
        )
    }

    private fun running(
        ball: Point,
        velocity: Point,
        bricks: List<Brick> = GameEngine.newGame().bricks,
        lives: Int = GameWorld.MAX_LIVES,
    ): GameEngine = GameEngine(
        GameEngine.newGame().copy(
            lives = lives,
            ball = ball,
            velocity = velocity,
            bricks = bricks,
            status = GameStatus.RUNNING,
        ),
    )
}
