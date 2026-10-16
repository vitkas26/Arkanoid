package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivesTest {
    private fun fallingBall(state: GameState = GameEngine.newGame()) = GameEngine(
        state.copy(
            status = GameStatus.RUNNING,
            ball = Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f),
            velocity = Point(0f, 200f),
        ),
    )

    @Test fun newAttemptHasThreeLives() {
        assertEquals(3, GameEngine.newGame().lives)
        assertEquals(3, GameEngine.newGame(2).lives)
        assertFalse(GameEngine.newGame().resume)
    }

    @Test fun lostLifeWaitsForContinueAndKeepsProgress() {
        val bricks = Levels.second.bricks.drop(2)
        val engine = fallingBall(
            GameEngine.newGame(2).copy(bricks = bricks, score = 30, carried = 80, paddleLeft = 40f),
        )
        engine.setPaddleTarget(300f)
        engine.update(0.02f)

        val state = engine.state
        assertEquals(GameStatus.READY, state.status)
        assertTrue(state.resume)
        assertEquals(2, state.lives)
        assertEquals(bricks, state.bricks)
        assertEquals(30, state.score)
        assertEquals(80, state.carried)
        assertEquals(2, state.level)
        // Level 2 launches at 1.1 x (130, -270) = (143, -297): the lost life restores that, not the base.
        assertEquals(143f, state.velocity.x, 0.001f)
        assertEquals(-297f, state.velocity.y, 0.001f)
        // The paddle was still travelling to the touch target when the ball fell.
        val left = state.paddleLeft
        assertTrue(left > 40f && left < 300f - GameWorld.PADDLE_WIDTH / 2f)
        assertEquals(Point(left + GameWorld.PADDLE_WIDTH / 2f, GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS), state.ball)

        // The old touch target is gone: after "Continue" the paddle stays where it was.
        engine.start()
        assertFalse(engine.state.resume)
        assertEquals(GameStatus.RUNNING, engine.state.status)
        engine.update(0.05f)
        assertEquals(left, engine.state.paddleLeft, 0.001f)
    }

    @Test fun waitingForContinueDoesNotMoveOrLoseMoreLives() {
        val engine = fallingBall()
        engine.update(0.02f)
        val waiting = engine.state
        engine.update(1f)
        assertEquals(waiting, engine.state)
        assertEquals(2, engine.state.lives)
    }

    @Test fun threeMissesEndTheAttempt() {
        var state = GameEngine.newGame()
        repeat(3) { i ->
            val engine = fallingBall(state)
            engine.update(0.02f)
            state = engine.state
            assertEquals(2 - i, state.lives)
            assertEquals(if (i < 2) GameStatus.READY else GameStatus.LOST, state.status)
        }
        assertTrue(state.attemptOver)
    }

    @Test fun livesCarryOverToLevelTwoWithoutRefill() {
        val engine = GameEngine(
            GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80, lives = 2),
        )
        engine.nextLevel()
        assertEquals(2, engine.state.lives)
        assertEquals(80, engine.state.carried)
    }

    @Test fun restartAndExitGiveFreshAttemptWithThreeLives() {
        val lost = GameEngine.newGame(2).copy(status = GameStatus.LOST, lives = 0, carried = 80, score = 30)
        val engine = GameEngine(lost)
        engine.restart()
        assertEquals(GameEngine.newGame(2), engine.state)

        val mid = GameEngine(
            GameEngine.newGame(2).copy(status = GameStatus.RUNNING, lives = 1, carried = 80, score = 30,
                bricks = Levels.second.bricks.drop(4), saved = true),
        )
        mid.exitToFirstLevel()
        assertEquals(GameEngine.newGame(), mid.state)
        assertEquals(8, mid.state.bricks.size)
        assertEquals(3, mid.state.lives)
        assertEquals(0, mid.state.carried)
    }

    @Test fun lifeBonusOnlyForWinOfLastLevel() {
        val won5 = GameEngine.newGame(5).copy(status = GameStatus.WON, bricks = emptyList(), carried = 560, score = 240, lives = 3)
        assertEquals(800, won5.attemptScore)
        assertEquals(60, won5.lifeBonus)
        assertEquals(860, won5.finalScore)
        assertEquals(40, won5.copy(lives = 2).lifeBonus)
        // The documented example: carried 560 + current 240 with 2 lives = 800 + 40.
        assertEquals(840, won5.copy(lives = 2).finalScore)

        // Levels 3 and 4 are intermediate now: no bonus.
        val won4 = GameEngine.newGame(4).copy(status = GameStatus.WON, bricks = emptyList(), carried = 360, score = 200, lives = 3)
        assertEquals(0, won4.lifeBonus)
        assertEquals(560, won4.finalScore)
        val won3 = GameEngine.newGame(3).copy(status = GameStatus.WON, bricks = emptyList(), carried = 200, score = 160, lives = 3)
        assertEquals(0, won3.lifeBonus)
        assertEquals(360, won3.finalScore)

        // Intermediate wins never carry a bonus.
        val won2 = GameEngine.newGame(2).copy(status = GameStatus.WON, bricks = emptyList(), carried = 80, score = 120, lives = 2)
        assertEquals(0, won2.lifeBonus)
        assertEquals(200, won2.finalScore)
        val won1 = GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80, lives = 3)
        assertEquals(0, won1.lifeBonus)
        assertEquals(80, won1.finalScore)

        val lost = GameEngine.newGame(5).copy(status = GameStatus.LOST, lives = 0, carried = 560, score = 30)
        assertEquals(0, lost.lifeBonus)
        assertEquals(590, lost.finalScore)

        val early = GameEngine.newGame(2).copy(status = GameStatus.RUNNING, lives = 3, carried = 80, score = 30)
        assertEquals(0, early.lifeBonus)
        assertEquals(110, early.finalScore)
    }

    @Test fun carriedScoreNeverIncludesBonus() {
        val engine = GameEngine(
            GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80, lives = 3),
        )
        engine.nextLevel()
        assertEquals(80, engine.state.carried)
        assertEquals(80, engine.state.finalScore)
    }

    @Test fun earlySaveIsAllowedOnceAndNormalSaveStillNeedsFinishedAttempt() {
        val running = GameEngine(GameEngine.newGame().copy(status = GameStatus.RUNNING, score = 30))
        assertFalse(running.markSaved())
        assertTrue(running.markSaved(early = true))
        assertFalse(running.markSaved(early = true))
        assertTrue(running.state.saved)
    }
}
