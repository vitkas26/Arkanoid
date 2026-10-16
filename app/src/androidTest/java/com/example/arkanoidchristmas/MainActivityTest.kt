package com.example.arkanoidchristmas

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.arkanoidchristmas.game.GameEngine
import com.example.arkanoidchristmas.game.GameState
import com.example.arkanoidchristmas.game.GameStatus
import com.example.arkanoidchristmas.game.GameWorld
import com.example.arkanoidchristmas.game.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /** Points the leaderboard at a scratch prefs file and clears only that file; real results stay untouched. */
    private val scratchLeaderboard = object : ExternalResource() {
        override fun before() {
            MainActivity.leaderboardPrefsName = TEST_PREFS
            clear()
        }
        override fun after() {
            clear()
            MainActivity.leaderboardPrefsName = "leaderboard"
        }
        private fun clear() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences(TEST_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(scratchLeaderboard).around(activityRule)

    @Test fun startAndDragKeepPaddleInsideVisibleField() {
        onView(withId(R.id.actionButton)).check(matches(isDisplayed()))
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            val field = gameView.playfieldBounds()
            assertTrue(field.width() > 0f && field.height() > 0f)
            assertTrue(field.left >= 0f && field.right <= gameView.width.toFloat())
            assertTrue(field.top >= 0f && field.bottom <= gameView.height.toFloat())

            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(GameStatus.RUNNING, gameView.engine.state.status)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusOverlay).visibility)

            val initialLeft = gameView.engine.state.paddleLeft
            dispatchTouch(gameView, MotionEvent.ACTION_DOWN, field.right + 50f, field.centerY())
            // The touch only sets a target: no instant jump.
            assertEquals(initialLeft, gameView.engine.state.paddleLeft, 0.01f)
        }
        // Far outside the field: the paddle must arrive at the clamped right edge, not beyond.
        awaitPaddleLeft(GameWorld.WIDTH - GameWorld.PADDLE_WIDTH)
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, null)
            assertEquals(
                GameWorld.WIDTH - GameWorld.PADDLE_WIDTH - GameWorld.PADDLE_STEP,
                gameView.engine.state.paddleLeft,
                0.01f,
            )
        }
    }

    @Test fun touchMovesPaddleGraduallyWithinSpeedLimitAndContinuesAfterRelease() {
        val maxLeft = GameWorld.WIDTH - GameWorld.PADDLE_WIDTH
        activityRule.scenario.onActivity { activity ->
            // Motionless ball so only the paddle moves; paddle starts at the left edge.
            restoreRunning(
                activity,
                GameEngine.newGame().copy(
                    status = GameStatus.RUNNING,
                    velocity = Point(0f, 0f),
                    paddleLeft = 0f,
                ),
            )
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            val field = gameView.playfieldBounds()
            dispatchTouch(gameView, MotionEvent.ACTION_DOWN, field.right, field.centerY())
            // Finger is lifted at once; the paddle must still travel to the target.
            dispatchTouch(gameView, MotionEvent.ACTION_UP, field.right, field.centerY())
            assertEquals(0f, gameView.engine.state.paddleLeft, 0.01f)
        }

        data class Sample(val nanos: Long, val left: Float)
        val samples = mutableListOf(Sample(System.nanoTime(), 0f))
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline && samples.last().left < maxLeft) {
            Thread.sleep(20)
            activityRule.scenario.onActivity {
                samples += Sample(System.nanoTime(), it.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft)
            }
        }

        assertEquals("paddle never reached the target", maxLeft, samples.last().left, 0.01f)
        assertTrue("paddle did not pass through intermediate positions", samples.any { it.left > 1f && it.left < maxLeft - 1f })
        for ((previous, current) in samples.zipWithNext()) {
            assertTrue("paddle moved backwards", current.left >= previous.left - 0.01f)
            val seconds = (current.nanos - previous.nanos) / 1e9f
            // One frame of slack for the sampling jitter between frame time and wall time.
            assertTrue(
                "speed limit exceeded: ${current.left - previous.left} in $seconds s",
                current.left - previous.left <= GameWorld.PADDLE_SPEED * (seconds + 0.034f),
            )
        }
        val totalSeconds = (samples.last().nanos - samples.first().nanos) / 1e9f
        assertTrue("full travel too fast: $totalSeconds s", totalSeconds >= maxLeft / GameWorld.PADDLE_SPEED - 0.05f)
    }

    @Test fun pauseFreezesPaddleTravelAndResumeContinuesToTarget() {
        val maxLeft = GameWorld.WIDTH - GameWorld.PADDLE_WIDTH
        activityRule.scenario.onActivity { activity ->
            restoreRunning(
                activity,
                GameEngine.newGame().copy(status = GameStatus.RUNNING, velocity = Point(0f, 0f), paddleLeft = 0f),
            )
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            dispatchTouch(gameView, MotionEvent.ACTION_DOWN, gameView.playfieldBounds().right, gameView.playfieldBounds().centerY())
        }
        awaitIntermediatePaddle(maxLeft)
        var frozen = 0f
        activityRule.scenario.onActivity { activity ->
            activity.pauseGame()
            frozen = activity.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft
            assertTrue("paused too early/late: $frozen", frozen > 0f && frozen < maxLeft)
        }
        Thread.sleep(400)
        activityRule.scenario.onActivity { activity ->
            assertEquals(frozen, activity.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft, 0f)
            activity.findViewById<Button>(R.id.actionButton).performClick()
        }
        awaitPaddleLeft(maxLeft)
    }

    @Test fun recreationDoesNotJumpPaddleToOldTouchTarget() {
        val maxLeft = GameWorld.WIDTH - GameWorld.PADDLE_WIDTH
        activityRule.scenario.onActivity { activity ->
            restoreRunning(
                activity,
                GameEngine.newGame().copy(status = GameStatus.RUNNING, velocity = Point(0f, 0f), paddleLeft = 20f),
            )
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            dispatchTouch(gameView, MotionEvent.ACTION_DOWN, gameView.playfieldBounds().right, gameView.playfieldBounds().centerY())
        }
        awaitIntermediatePaddle(maxLeft)
        // Pause first so the saved state is exactly the one we record (no frame can race it).
        var savedLeft = 0f
        activityRule.scenario.onActivity { activity ->
            activity.pauseGame()
            savedLeft = activity.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft
            assertTrue("not in flight: $savedLeft", savedLeft > 20f && savedLeft < maxLeft)
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            assertEquals(savedLeft, activity.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft, 0f)
            activity.findViewById<Button>(R.id.actionButton).performClick()
        }
        // After resuming, the old touch target must be gone: the paddle stays where it was saved.
        Thread.sleep(400)
        activityRule.scenario.onActivity { activity ->
            assertEquals(savedLeft, activity.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft, 0.01f)
        }
    }

    @Test fun playfieldFitsTallAndWideViewportsWithoutDistortion() {
        activityRule.scenario.onActivity { activity ->
            for ((width, height) in listOf(400 to 700, 700 to 400, 300 to 1000)) {
                val gameView = GameView(activity)
                gameView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                gameView.layout(0, 0, width, height)
                val field = gameView.playfieldBounds()
                assertTrue(field.left >= -0.01f && field.top >= -0.01f)
                assertTrue(field.right <= width + 0.01f && field.bottom <= height + 0.01f)
                assertEquals(GameWorld.WIDTH / GameWorld.HEIGHT, field.width() / field.height(), 0.001f)
            }
        }
    }

    @Test fun winAndLossShowScoreWinContinuesAndLossOffersRestartDialog() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            val win = GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80)
            activity.restoreGameState(win)
            assertEquals("80", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals(activity.getString(R.string.win_title, 1), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals(activity.getString(R.string.win_button_next), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)

            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(GameStatus.RUNNING, gameView.engine.state.status)
            assertEquals(0, gameView.engine.state.score)
            assertEquals(2, gameView.engine.state.level)
            assertEquals(12, gameView.engine.state.bricks.size)

            activity.restoreGameState(GameEngine.newGame().copy(status = GameStatus.LOST, score = 20))
            assertEquals("20", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals(activity.getString(R.string.lose_title), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals("Начать заново", activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            // No duplicate secondary restart button on a final screen.
            assertEquals(View.GONE, activity.findViewById<View>(R.id.exitButton).visibility)
            // The main button asks for confirmation instead of retrying the level with refilled lives.
            val lost = gameView.engine.state
            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertTrue(requireNotNull(activity.exitDialog).isShowing)
            assertEquals(lost, gameView.engine.state)
        }
    }

    @Test fun recreationRestoresRunningGameAndPaddlePosition() {
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(
                GameEngine.newGame().copy(
                    status = GameStatus.RUNNING,
                    velocity = Point(0f, 0f),
                    paddleLeft = 0f,
                    score = 30,
                ),
            )
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(GameStatus.RUNNING, state.status)
            assertEquals(0f, state.paddleLeft, 0.01f)
            assertEquals(30, state.score)
            assertEquals(1, state.level)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusOverlay).visibility)
        }
    }

    @Test fun readyHudAndPauseShowLevelOneOfFiveAndName() {
        activityRule.scenario.onActivity { activity ->
            val label = activity.getString(R.string.level_label, 1, 5, activity.getString(R.string.level_name_1))
            assertEquals("Уровень 1 из 5 · Первый снег", label)
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains(label))
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains(label))
            activity.restoreGameState(
                GameEngine.newGame().copy(status = GameStatus.RUNNING, velocity = Point(0f, -50f)),
            )
            activity.pauseGame()
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains(label))
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains(label))
        }
    }

    @Test fun winShowsLevelClearedActualScoreAndNoNextButton() {
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(winState().copy(score = 80))
            assertEquals("Уровень 1 пройден", activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Уровень 1 из 5 · Первый снег"))
            assertEquals("80", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals("Следующий уровень", activity.findViewById<Button>(R.id.actionButton).text.toString())
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(2, state.level)
            assertEquals(GameEngine.newGame(2).bricks, state.bricks)
            assertEquals(0, state.score)
            // The running level 2 shows its own name and an independent score in the HUD.
            val hud = activity.findViewById<TextView>(R.id.scoreText).text.toString()
            assertTrue(hud, hud.contains("Уровень 2 из 5 · Ледяная крепость") && hud.contains("Счёт: 0"))
        }
    }

    @Test fun levelTwoWinMovesToLevelThreeAndLossOffersOnlyRestartDialog() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.restoreGameState(
                level2State(GameStatus.WON, bricks = emptyList(), score = 120).copy(carried = 80, lives = 2),
            )
            val label = "Уровень 2 из 5 · Ледяная крепость"
            assertEquals("Уровень 2 пройден", activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains(label))
            assertEquals("120", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals("Следующий уровень", activity.findViewById<Button>(R.id.actionButton).text.toString())
            // Level 2 is intermediate: no save, no bonus line.
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertTrue(!activity.findViewById<TextView>(R.id.statusCaption).text.contains("бонус"))
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val state = gameView.engine.state
            assertEquals(3, state.level)
            assertEquals(GameStatus.RUNNING, state.status)
            assertEquals(16, state.bricks.size)
            assertEquals(0, state.score)
            assertEquals(200, state.carried)
            assertEquals(2, state.lives)
            val hud = activity.findViewById<TextView>(R.id.scoreText).text.toString()
            assertTrue(hud, hud.contains("Уровень 3 из 5 · Снежный бастион") && hud.contains("♥♥") && !hud.contains("♥♥♥"))

            // A loss on level 2 ends the attempt: only the shared restart dialog, no retry.
            activity.restoreGameState(level2State(GameStatus.LOST, score = 40))
            assertEquals("Начать заново", activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.exitButton).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
            val lost = gameView.engine.state
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val dialog = requireNotNull(activity.exitDialog)
            assertTrue(dialog.isShowing)
            dialog.findViewById<Button>(R.id.exitCancel).performClick()
            assertEquals(lost, gameView.engine.state)
            activity.findViewById<Button>(R.id.actionButton).performClick()
            activity.exitDialog!!.findViewById<Button>(R.id.exitConfirm).performClick()
            assertEquals(GameEngine.newGame(), gameView.engine.state)
            assertEquals(3, gameView.engine.state.lives)
            assertEquals(8, gameView.engine.state.bricks.size)
        }
    }

    @Test fun levelFiveWinAndLossOfferRestartDialogWithSaveAndLeaderboardKept() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            for (status in listOf(GameStatus.WON, GameStatus.LOST)) {
                activity.restoreGameState(level5State(status, bricks = emptyList(), score = 240).copy(carried = 560))
                // Big number of a finished attempt = finalScore: 800 (+60 life bonus only for the win).
                assertEquals(
                    if (status == GameStatus.WON) "860" else "800",
                    activity.findViewById<TextView>(R.id.scoreValue).text.toString(),
                )
                assertEquals("Начать заново", activity.findViewById<Button>(R.id.actionButton).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.exitButton).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.leaderboardButton).visibility)
                val before = gameView.engine.state
                activity.findViewById<Button>(R.id.actionButton).performClick()
                activity.exitDialog!!.findViewById<Button>(R.id.exitCancel).performClick()
                assertEquals(before, gameView.engine.state)
                activity.findViewById<Button>(R.id.actionButton).performClick()
                activity.exitDialog!!.findViewById<Button>(R.id.exitConfirm).performClick()
                assertEquals(GameEngine.newGame(), gameView.engine.state)
                // Reset never writes to the leaderboard.
                assertEquals(activity.getString(R.string.leaderboard_empty), activity.leaderboardText())
            }
        }
    }

    @Test fun levelThreeWinMovesToLevelFourKeepingCarryLivesAndScalingSpeed() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.restoreGameState(
                level3State(GameStatus.WON, bricks = emptyList(), score = 160).copy(carried = 200, lives = 2),
            )
            // Level 3 is intermediate: next-level button, no save, no bonus line.
            assertEquals("Следующий уровень", activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertTrue(!activity.findViewById<TextView>(R.id.statusCaption).text.contains("бонус"))
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val state = gameView.engine.state
            assertEquals(4, state.level)
            assertEquals(GameStatus.RUNNING, state.status)
            assertEquals(20, state.bricks.size)
            assertEquals(360, state.carried)
            assertEquals(0, state.score)
            assertEquals(2, state.lives)
            // Launch speed of level 4 is 1.3 x (130, -270); the paddle keeps its own speed.
            assertEquals(169f, state.velocity.x, 0.01f)
            assertEquals(-351f, state.velocity.y, 0.01f)
            val hud = activity.findViewById<TextView>(R.id.scoreText).text.toString()
            assertTrue(hud, hud.contains("Уровень 4 из 5 · Морозная цитадель") && hud.contains("♥♥") && !hud.contains("♥♥♥"))
        }
    }

    @Test fun levelFourReadyAndPauseShowSpeedAndRecreationDoesNotRescaleIt() {
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(GameEngine.newGame(4))
            val ready = activity.findViewById<TextView>(R.id.statusCaption).text.toString()
            assertTrue(ready, ready.contains("Морозная цитадель") && ready.contains("Скорость мяча: 130%"))
            activity.restoreGameState(GameEngine.newGame(2))
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Скорость мяча: 110%"))
            activity.restoreGameState(GameEngine.newGame())
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Скорость мяча: 100%"))
            activity.restoreGameState(level4State(GameStatus.RUNNING, score = 30).copy(velocity = Point(100f, -351f)))
            activity.pauseGame()
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Скорость мяча: 130%"))
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(4, state.level)
            assertEquals(Point(100f, -351f), state.velocity)
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Скорость мяча: 130%"))
        }
    }

    @Test fun recreationKeepsLevelThreeStateAndLevelFiveFinalWin() {
        val partial = GameEngine.newGame(3).bricks.drop(5)
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(
                level3State(GameStatus.RUNNING, bricks = partial, score = 50).copy(carried = 200, lives = 2),
            )
            activity.pauseGame()
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(3, state.level)
            assertEquals(partial, state.bricks)
            assertEquals(50, state.score)
            assertEquals(200, state.carried)
            assertEquals(2, state.lives)
            assertEquals(Point(0f, -50f), state.velocity)
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains("Уровень 3 из 5 · Снежный бастион"))
            activity.restoreGameState(level5State(GameStatus.WON, bricks = emptyList(), score = 240).copy(carried = 560, lives = 2))
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            assertEquals(5, activity.findViewById<GameView>(R.id.gameView).engine.state.level)
            assertEquals("Уровень 5 пройден", activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals("840", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals("Начать заново", activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Очки 800 + бонус за жизни 40 = итог 840"))
        }
    }

    @Test fun levelTwoReadyAndPauseShowNumberAndName() {
        activityRule.scenario.onActivity { activity ->
            val label = "Уровень 2 из 5 · Ледяная крепость"
            activity.restoreGameState(GameEngine.newGame(2))
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains(label))
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains(label))
            activity.restoreGameState(level2State(GameStatus.RUNNING))
            activity.pauseGame()
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains(label))
        }
    }

    @Test fun recreationKeepsLevelTwoScoreBricksPauseAndEndStates() {
        val partial = GameEngine.newGame(2).bricks.drop(3)
        var frozen: GameState? = null
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(level2State(GameStatus.RUNNING, bricks = partial, score = 30))
            activity.pauseGame()
            frozen = activity.findViewById<GameView>(R.id.gameView).engine.state
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(frozen, state)
            assertEquals(2, state.level)
            assertEquals(partial, state.bricks)
            assertEquals(30, state.score)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains("Уровень 2 из 5"))
            activity.restoreGameState(level2State(GameStatus.WON, bricks = emptyList(), score = 120))
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            assertEquals(2, activity.findViewById<GameView>(R.id.gameView).engine.state.level)
            assertEquals("Уровень 2 пройден", activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals("120", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals("Следующий уровень", activity.findViewById<Button>(R.id.actionButton).text.toString())
        }
    }

    @Test fun leaderboardIsAvailableOnReadyAndEndCardsButSaveOnlyWhenAttemptEnds() {
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.leaderboardButton).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            activity.restoreGameState(winState())
            // Level 1 win is not the end of the attempt: no save button yet, "next level" instead.
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.leaderboardButton).visibility)
            activity.restoreGameState(loseState())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
            // Level 2 win is intermediate too: still no save button.
            activity.restoreGameState(level2State(GameStatus.WON, bricks = emptyList(), score = 120))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            // Levels 3 and 4 wins are intermediate too; only level 5 ends the attempt.
            activity.restoreGameState(level3State(GameStatus.WON, bricks = emptyList(), score = 160))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            activity.restoreGameState(level4State(GameStatus.WON, bricks = emptyList(), score = 200))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            activity.restoreGameState(level5State(GameStatus.WON, bricks = emptyList(), score = 240))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
        }
    }

    @Test fun savingAttemptUsesCarriedTotalOnceAndSurvivesRecreationAndRestart() {
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(winState())
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val engine = activity.findViewById<GameView>(R.id.gameView).engine
            assertEquals(80, engine.state.carried)
            // HUD shows only the current level's score.
            assertTrue(activity.findViewById<TextView>(R.id.scoreText).text.contains("Счёт: 0"))
            // Levels 2 -> 3 -> 4 -> 5 keep the base score and the lives; level 5 is the final one.
            activity.restoreGameState(engine.state.copy(status = GameStatus.WON, bricks = emptyList(), score = 120))
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val third = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(3, third.level)
            assertEquals(200, third.carried)
            assertEquals(3, third.lives)
            activity.restoreGameState(third.copy(status = GameStatus.WON, bricks = emptyList(), score = 160))
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val fourth = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(4, fourth.level)
            assertEquals(360, fourth.carried)
            assertEquals(3, fourth.lives)
            activity.restoreGameState(fourth.copy(status = GameStatus.WON, bricks = emptyList(), score = 200))
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val fifth = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(5, fifth.level)
            assertEquals(560, fifth.carried)
            assertEquals(3, fifth.lives)
            activity.restoreGameState(fifth.copy(status = GameStatus.WON, bricks = emptyList(), score = 240))
            // Window, breakdown and (below) the leaderboard all show the same 860.
            assertEquals("860", activity.findViewById<TextView>(R.id.scoreValue).text.toString())

            activity.findViewById<Button>(R.id.saveButton).performClick()
            val dialog = requireNotNull(activity.nameDialog)
            val input = dialog.findViewById<android.widget.EditText>(R.id.nameInput)!!
            assertEquals("Игрок", input.text.toString())
            input.setText("   ")
            assertTrue(
                dialog.findViewById<TextView>(R.id.nameSummary).text.toString(),
                dialog.findViewById<TextView>(R.id.nameSummary).text.contains("Очки 800 + бонус за жизни 60 = итог 860"),
            )
            dialog.findViewById<Button>(R.id.nameConfirm).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertEquals("1. Игрок — 860 оч., уровень 5", activity.leaderboardText())
            assertTrue(requireNotNull(activity.leaderboardDialog).isShowing)
            // A repeated save for the same attempt is ignored.
            assertEquals(false, activity.saveResult("Другой"))
            assertEquals("1. Игрок — 860 оч., уровень 5", activity.leaderboardText())
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertEquals(false, activity.saveResult("Дубль"))
            assertEquals("1. Игрок — 860 оч., уровень 5", activity.leaderboardText())
            // "Начать заново" on the final card confirms, then starts a fresh attempt: carried 0, unsaved.
            activity.findViewById<Button>(R.id.actionButton).performClick()
            activity.exitDialog!!.findViewById<Button>(R.id.exitConfirm).performClick()
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(0, state.carried)
            assertEquals(false, state.saved)
            activity.restoreGameState(state.copy(status = GameStatus.LOST, score = 30))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
            assertTrue(activity.saveResult("  Аня  "))
            assertEquals(
                "1. Игрок — 860 оч., уровень 5\n2. Аня — 30 оч., уровень 1",
                activity.leaderboardText(),
            )
            activity.leaderboardDialog?.dismiss()
            activity.findViewById<Button>(R.id.leaderboardButton).performClick()
        }
        // Screenshot of the filled scratch leaderboard for visual acceptance (test prefs only).
        screenshotNow("leaderboard.png")
    }

    @Test fun lostLifeWaitsForContinueKeepsProgressAndSurvivesRecreation() {
        activityRule.scenario.onActivity { activity ->
            restoreRunning(
                activity,
                GameEngine.newGame(2).copy(
                    status = GameStatus.RUNNING, carried = 80, score = 30,
                    ball = Point(20f, GameWorld.HEIGHT + GameWorld.BALL_RADIUS - 1f),
                    velocity = Point(0f, 200f),
                ),
            )
        }
        var waiting: GameState? = null
        val deadline = System.nanoTime() + 3_000_000_000L
        while (waiting == null && System.nanoTime() < deadline) {
            activityRule.scenario.onActivity {
                val state = it.findViewById<GameView>(R.id.gameView).engine.state
                if (state.status == GameStatus.READY) waiting = state
            }
            Thread.sleep(20)
        }
        val lostLife = requireNotNull(waiting) { "ball never fell" }
        assertEquals(2, lostLife.lives)
        assertTrue(lostLife.resume)
        assertEquals(30, lostLife.score)
        assertEquals(80, lostLife.carried)
        assertEquals(12, lostLife.bricks.size)
        activityRule.scenario.onActivity { activity ->
            val hud = activity.findViewById<TextView>(R.id.scoreText).text.toString()
            assertTrue(hud, hud.contains("Счёт: 30") && hud.contains("♥♥") && !hud.contains("♥♥♥"))
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(activity.getString(R.string.life_lost_title), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            // Recreation neither restores the life nor takes another one.
            assertEquals(lostLife, activity.findViewById<GameView>(R.id.gameView).engine.state)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val state = activity.findViewById<GameView>(R.id.gameView).engine.state
            assertEquals(GameStatus.RUNNING, state.status)
            assertEquals(2, state.lives)
            assertEquals(false, state.resume)
        }
    }

    @Test fun restartCancelKeepsPausedStateAndConfirmGoesToFreshFirstLevel() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.restoreGameState(level2State(GameStatus.RUNNING, score = 30).copy(carried = 80, lives = 2))
            activity.pauseGame()
            val before = gameView.engine.state
            activity.findViewById<Button>(R.id.exitButton).performClick()
            val dialog = requireNotNull(activity.exitDialog)
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<TextView>(R.id.exitMessage).text.contains("Рейтинг не изменится"))
            dialog.findViewById<Button>(R.id.exitCancel).performClick()
            assertEquals(false, dialog.isShowing)
            assertEquals(before, gameView.engine.state)
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())

            activity.findViewById<Button>(R.id.exitButton).performClick()
            activity.exitDialog!!.findViewById<Button>(R.id.exitConfirm).performClick()
            assertEquals(GameEngine.newGame(), gameView.engine.state)
            assertEquals(3, gameView.engine.state.lives)
            assertEquals(8, gameView.engine.state.bricks.size)
            assertEquals(GameStatus.READY, gameView.engine.state.status)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.exitButton).visibility)
            assertEquals(activity.getString(R.string.leaderboard_empty), activity.leaderboardText())
        }
    }

    @Test fun restartNeverSavesAndLeavesExistingLeaderboardUntouched() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.restoreGameState(loseState().copy(score = 50))
            assertTrue(activity.saveResult("Аня"))
            val saved = activity.leaderboardText()

            activity.restoreGameState(level2State(GameStatus.RUNNING, score = 30).copy(carried = 80, lives = 2))
            activity.pauseGame()
            activity.findViewById<Button>(R.id.exitButton).performClick()
            val dialog = activity.exitDialog!!
            assertTrue(dialog.findViewById<TextView>(R.id.exitMessage).text.contains("Рейтинг не изменится"))
            dialog.findViewById<Button>(R.id.exitConfirm).performClick()
            assertEquals(GameEngine.newGame(), gameView.engine.state)
            assertEquals(saved, activity.leaderboardText())
        }
    }

    @Test fun finalWinWindowBreakdownAndRatingAllShowFinalScore() {
        activityRule.scenario.onActivity { activity ->
            // carried 560 + current 240, 2 lives left: base 800 + bonus 40 = 840.
            activity.restoreGameState(
                level5State(GameStatus.WON, bricks = emptyList(), score = 240).copy(carried = 560, lives = 2),
            )
            assertEquals("840", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            val caption = activity.findViewById<TextView>(R.id.statusCaption).text.toString()
            assertTrue(caption, caption.contains("Очки 800 + бонус за жизни 40 = итог 840"))
            // The main button is the restart; the secondary one would only duplicate it.
            assertEquals(View.GONE, activity.findViewById<View>(R.id.exitButton).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveButton).visibility)
            activity.findViewById<Button>(R.id.saveButton).performClick()
            assertTrue(activity.nameDialog!!.findViewById<TextView>(R.id.nameSummary).text.contains("итог 840"))
            activity.nameDialog!!.findViewById<Button>(R.id.nameConfirm).performClick()
            assertEquals("1. Игрок — 840 оч., уровень 5", activity.leaderboardText())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.saveButton).visibility)
        }
    }

    @Test fun lostLateLevelWindowBreakdownAndRatingShowCarriedPlusCurrentWithoutBonus() {
        activityRule.scenario.onActivity { activity ->
            // Lost on level 4 with carried 360 and 90 points on that level: 450, never a bonus.
            activity.restoreGameState(
                level4State(GameStatus.LOST, score = 90).copy(carried = 360, lives = 0),
            )
            assertEquals("450", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            val caption = activity.findViewById<TextView>(R.id.statusCaption).text.toString()
            assertTrue(caption, caption.contains("Очки 450 + бонус за жизни 0 = итог 450"))
            assertTrue(activity.saveResult("Игрок"))
            assertEquals("1. Игрок — 450 оч., уровень 4", activity.leaderboardText())
        }
    }

    @Test fun intermediateWinsShowTheirOwnLevelScoreNotTheCarriedSum() {
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(level4State(GameStatus.WON, bricks = emptyList(), score = 200).copy(carried = 360))
            assertEquals("200", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertTrue(!activity.findViewById<TextView>(R.id.statusCaption).text.contains("итог"))
            assertEquals("Следующий уровень", activity.findViewById<Button>(R.id.actionButton).text.toString())
        }
    }

    @Test fun levelFourWinMovesToLevelFiveKeepingCarryLivesAndScalingSpeed() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            activity.restoreGameState(
                level4State(GameStatus.WON, bricks = emptyList(), score = 200).copy(carried = 360, lives = 2),
            )
            activity.findViewById<Button>(R.id.actionButton).performClick()
            val state = gameView.engine.state
            assertEquals(5, state.level)
            assertEquals(GameStatus.RUNNING, state.status)
            assertEquals(24, state.bricks.size)
            assertEquals(560, state.carried)
            assertEquals(2, state.lives)
            assertEquals(182f, state.velocity.x, 0.01f)
            assertEquals(-378f, state.velocity.y, 0.01f)
            val hud = activity.findViewById<TextView>(R.id.scoreText).text.toString()
            assertTrue(hud, hud.contains("Уровень 5 из 5 · Рождественское сердце") && hud.contains("♥♥") && !hud.contains("♥♥♥"))
            activity.restoreGameState(GameEngine.newGame(5))
            assertTrue(activity.findViewById<TextView>(R.id.statusCaption).text.contains("Скорость мяча: 140%"))
        }
    }

    /** Saves lives-style screenshots to filesDir: leaderboard-filled, name-dialog, pause-restart, win-bonus. */
    @Test fun captureLivesStyleScreenshots() {
        activityRule.scenario.onActivity { activity ->
            val names = listOf("Аня" to 260, "Александра-Мария" to 240, "Борис" to 190, "Вика" to 170,
                "Гоша" to 150, "Даша" to 120, "Егор" to 100, "Женя" to 80, "Зоя" to 60, "Игорь" to 40, "Кира" to 20)
            for ((name, score) in names) {
                activity.restoreGameState(loseState().copy(score = score))
                activity.saveResult(name)
            }
            activity.restoreGameState(GameEngine.newGame())
            activity.findViewById<Button>(R.id.leaderboardButton).performClick()
        }
        screenshotNow("style-leaderboard.png")
        activityRule.scenario.onActivity { it.leaderboardDialog?.dismiss() }

        saveScreenshot(
            level5State(GameStatus.WON, bricks = emptyList(), score = 240).copy(carried = 560, lives = 2),
            "style-win-bonus.png",
        )
        activityRule.scenario.onActivity { it.findViewById<Button>(R.id.saveButton).performClick() }
        screenshotNow("style-name-dialog.png")
        activityRule.scenario.onActivity { it.nameDialog?.dismiss() }

        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(level2State(GameStatus.RUNNING, score = 30).copy(carried = 80, lives = 2))
            activity.pauseGame()
            activity.findViewById<Button>(R.id.exitButton).performClick()
        }
        screenshotNow("style-pause-restart.png")
        activityRule.scenario.onActivity { it.exitDialog?.dismiss() }
    }

    /** After the layout is idle, saves the whole display (dialog windows included) to filesDir. */
    private fun screenshotNow(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot failed" }
        try {
            val file = File(instrumentation.targetContext.filesDir, fileName)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 0L)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun newDialogsKeepAllActionsReachableInSmallAndWideViewports() {
        // Dialogs are separate windows, so the viewport is simulated by capping the card's height
        // (240dp is the "wide" landscape height, 320dp the small phone); the card must then scroll.
        val dialogs = listOf<Triple<String, Int, (MainActivity) -> android.app.Dialog>>(
            Triple("leaderboard", R.id.leaderboardClose, { a ->
                a.restoreGameState(loseState().copy(score = 40))
                a.saveResult("Игрок с очень длинным именем для проверки")
                a.findViewById<Button>(R.id.leaderboardButton).performClick()
                a.leaderboardDialog!!
            }),
            Triple("name", R.id.nameCancel, { a ->
                a.restoreGameState(loseState())
                a.findViewById<Button>(R.id.saveButton).performClick()
                a.nameDialog!!
            }),
            Triple("exit", R.id.exitCancel, { a ->
                a.restoreGameState(level2State(GameStatus.RUNNING, score = 30).copy(carried = 80))
                a.pauseGame()
                a.findViewById<Button>(R.id.exitButton).performClick()
                a.exitDialog!!
            }),
        )
        for ((name, lastButton, open) in dialogs) {
            for (capDp in listOf(240, 320)) {
                var dialog: android.app.Dialog? = null
                activityRule.scenario.onActivity { activity ->
                    dialog = open(activity)
                    val density = activity.resources.displayMetrics.density
                    dialog!!.findViewById<MaxHeightScrollView>(R.id.dialogScroll).maxHeightPx = (capDp * density).toInt()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                activityRule.scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    val scroll = dialog!!.findViewById<MaxHeightScrollView>(R.id.dialogScroll)
                    val label = "$name @${capDp}dp"
                    assertTrue("$label: dialog not shown", dialog!!.isShowing)
                    assertTrue("$label: card taller than viewport", scroll.height <= (capDp * density).toInt())
                    val content = scroll.getChildAt(0)
                    scroll.scrollTo(0, content.height)
                    val button = dialog!!.findViewById<Button>(lastButton)
                    val rect = Rect(0, 0, button.width, button.height)
                    scroll.offsetDescendantRectToMyCoords(button, rect)
                    assertTrue(
                        "$label: last action not reachable (button ${rect.top}..${rect.bottom}, scrollY ${scroll.scrollY}, height ${scroll.height})",
                        rect.top - scroll.scrollY >= 0 && rect.bottom - scroll.scrollY <= scroll.height,
                    )
                    assertTrue("$label: button too small", button.height >= 48 * density)
                    dialog!!.dismiss()
                }
            }
        }
    }

    @Test fun emptyLeaderboardShowsPlaceholderAndLeaderboardButtonOpensDialog() {
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<Button>(R.id.leaderboardButton).performClick()
            assertTrue(requireNotNull(activity.leaderboardDialog).isShowing)
            assertEquals(activity.getString(R.string.leaderboard_empty), activity.leaderboardText())
        }
    }

    @Test fun readyShowsStartCardWithoutScore() {
        activityRule.scenario.onActivity { activity ->
            assertEquals(GameStatus.READY, activity.findViewById<GameView>(R.id.gameView).engine.state.status)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            assertEquals(activity.getString(R.string.start), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.scoreValue).visibility)
        }
    }

    @Test fun recreationKeepsWinAndLossCards() {
        val cases = listOf(
            Triple(GameStatus.WON, 80, "Уровень 1 пройден"),
            Triple(GameStatus.LOST, 20, "Не повезло"),
        )
        for ((status, score, title) in cases) {
            activityRule.scenario.onActivity { activity ->
                activity.restoreGameState(
                    GameEngine.newGame().copy(
                        status = status,
                        score = score,
                        bricks = if (status == GameStatus.WON) emptyList() else GameEngine.newGame().bricks,
                    ),
                )
            }
            activityRule.scenario.recreate()
            activityRule.scenario.onActivity { activity ->
                assertEquals(status, activity.findViewById<GameView>(R.id.gameView).engine.state.status)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
                assertEquals(score.toString(), activity.findViewById<TextView>(R.id.scoreValue).text.toString())
                assertEquals(title, activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            }
        }
    }

    @Test fun endCardButtonIsReachableAndClickableInSmallAndWideViewports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activityBefore: MainActivity? = null
        activityRule.scenario.onActivity { activityBefore = it }

        for (viewport in listOf("small", "wide")) {
            for (state in listOf(winState(), loseState())) {
                val label = "$viewport ${state.status}"
                runAndAwaitFrame { activity ->
                    resizeRoot(activity, viewport)
                    activity.restoreGameState(state)
                }
                // Bring the button into view inside the ScrollView, then wait for that frame too.
                runAndAwaitFrame { activity ->
                    val button = activity.findViewById<Button>(R.id.actionButton)
                    button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                }

                var center = IntArray(2)
                activityRule.scenario.onActivity { activity ->
                    val button = activity.findViewById<Button>(R.id.actionButton)
                    val overlay = activity.findViewById<View>(R.id.statusOverlay)
                    val density = activity.resources.displayMetrics.density
                    assertTrue("$label: button too small", button.height >= 48 * density)

                    val visible = Rect()
                    assertTrue("$label: button not visible", button.getGlobalVisibleRect(visible))
                    assertEquals("$label: button width clipped", button.width, visible.width())
                    assertEquals("$label: button height clipped", button.height, visible.height())
                    val overlayVisible = Rect()
                    overlay.getGlobalVisibleRect(overlayVisible)
                    assertTrue("$label: button outside overlay", overlayVisible.contains(visible))
                    val screenLocation = IntArray(2)
                    button.getLocationOnScreen(screenLocation)
                    center = intArrayOf(screenLocation[0] + button.width / 2, screenLocation[1] + button.height / 2)
                }

                tap(instrumentation, center[0], center[1])
                instrumentation.waitForIdleSync()

                if (state.status == GameStatus.LOST) {
                    // The final button opens the restart dialog; its confirm button must be reachable too.
                    activityRule.scenario.onActivity { activity ->
                        assertTrue("$label: restart dialog missing", activity.exitDialog?.isShowing == true)
                        assertEquals("$label: status", GameStatus.LOST, activity.findViewById<GameView>(R.id.gameView).engine.state.status)
                        activity.exitDialog!!.dismiss()
                    }
                    continue
                }
                activityRule.scenario.onActivity { activity ->
                    val engineState = activity.findViewById<GameView>(R.id.gameView).engine.state
                    assertTrue("$label: activity replaced", activity === activityBefore)
                    assertEquals("$label: status", GameStatus.RUNNING, engineState.status)
                    assertEquals("$label: score", 0, engineState.score)
                    assertEquals("$label: bricks", 12, engineState.bricks.size)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.statusOverlay).visibility)
                }
            }
        }
    }

    @Test fun pauseFreezesRunningGameAndResumesFromSameState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(
                GameEngine.newGame().copy(
                    status = GameStatus.RUNNING,
                    ball = Point(200f, 400f),
                    velocity = Point(0f, -50f),
                    score = 30,
                ),
            )
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pauseButton).visibility)
            activity.findViewById<Button>(R.id.pauseButton).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.pauseButton).visibility)
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals("30", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
        }
        var frozen: GameState? = null
        activityRule.scenario.onActivity { frozen = it.findViewById<GameView>(R.id.gameView).engine.state }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            assertEquals(frozen, activity.findViewById<GameView>(R.id.gameView).engine.state)
            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pauseButton).visibility)
            // Resuming itself must not change the game (no restart, no time jump).
            assertEquals(frozen, activity.findViewById<GameView>(R.id.gameView).engine.state)
        }
        var moved = false
        repeat(30) {
            if (moved) return@repeat
            Thread.sleep(100)
            activityRule.scenario.onActivity {
                val state = it.findViewById<GameView>(R.id.gameView).engine.state
                moved = state.ball != frozen!!.ball
                assertEquals(GameStatus.RUNNING, state.status)
                assertEquals(30, state.score)
            }
        }
        assertTrue("Ball did not move after resume", moved)
    }

    @Test fun recreationKeepsPause() {
        var frozen: GameState? = null
        activityRule.scenario.onActivity { activity ->
            activity.restoreGameState(
                GameEngine.newGame().copy(status = GameStatus.RUNNING, velocity = Point(0f, -50f), score = 30),
            )
            activity.pauseGame()
            frozen = activity.findViewById<GameView>(R.id.gameView).engine.state
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            assertEquals(frozen, activity.findViewById<GameView>(R.id.gameView).engine.state)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            assertEquals(activity.getString(R.string.resume), activity.findViewById<Button>(R.id.actionButton).text.toString())
        }
    }

    /** Saves screenshots to filesDir for manual inspection: end-win.png and end-lose.png. */
    @Test fun captureEndScreenScreenshots() {
        saveScreenshot(winState(), "end-win.png")
        saveScreenshot(loseState(), "end-lose.png")
    }

    /** Saves level-two-ready.png and level-two-win.png to filesDir for manual inspection. */
    @Test fun captureLevelTwoScreenshots() {
        saveScreenshot(GameEngine.newGame(2), "level-two-ready.png")
        saveScreenshot(level2State(GameStatus.WON, bricks = emptyList(), score = 120), "level-two-win.png")
    }

    /** Saves level-three-ready.png and level-three-win.png (intermediate win, no bonus) to filesDir. */
    @Test fun captureLevelThreeScreenshots() {
        saveScreenshot(GameEngine.newGame(3), "level-three-ready.png")
        saveScreenshot(
            level3State(GameStatus.WON, bricks = emptyList(), score = 160).copy(carried = 200, lives = 2),
            "level-three-win.png",
        )
    }

    /** Saves level-four-ready.png and level-four-win.png (intermediate win, no bonus) to filesDir. */
    @Test fun captureLevelFourScreenshots() {
        saveScreenshot(GameEngine.newGame(4), "level-four-ready.png")
        saveScreenshot(
            level4State(GameStatus.WON, bricks = emptyList(), score = 200).copy(carried = 360, lives = 2),
            "level-four-win.png",
        )
    }

    /** Saves level-five-ready.png and level-five-win.png (final win: 840 with 2 lives) to filesDir. */
    @Test fun captureLevelFiveScreenshots() {
        saveScreenshot(GameEngine.newGame(5), "level-five-ready.png")
        saveScreenshot(
            level5State(GameStatus.WON, bricks = emptyList(), score = 240).copy(carried = 560, lives = 2),
            "level-five-win.png",
        )
    }

    private fun level5State(status: GameStatus, bricks: List<com.example.arkanoidchristmas.game.Brick> = GameEngine.newGame(5).bricks, score: Int = 0) =
        GameEngine.newGame(5).copy(status = status, bricks = bricks, score = score, velocity = Point(0f, -50f))

    private fun level4State(status: GameStatus, bricks: List<com.example.arkanoidchristmas.game.Brick> = GameEngine.newGame(4).bricks, score: Int = 0) =
        GameEngine.newGame(4).copy(status = status, bricks = bricks, score = score, velocity = Point(0f, -50f))

    private fun level3State(status: GameStatus, bricks: List<com.example.arkanoidchristmas.game.Brick> = GameEngine.newGame(3).bricks, score: Int = 0) =
        GameEngine.newGame(3).copy(status = status, bricks = bricks, score = score, velocity = Point(0f, -50f))

    private fun level2State(status: GameStatus, bricks: List<com.example.arkanoidchristmas.game.Brick> = GameEngine.newGame(2).bricks, score: Int = 0) =
        GameEngine.newGame(2).copy(status = status, bricks = bricks, score = score, velocity = Point(0f, -50f))

    private fun winState() = GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80)

    private fun loseState() = GameEngine.newGame().copy(status = GameStatus.LOST, score = 20)

    private fun saveScreenshot(state: GameState, fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runAndAwaitFrame { it.restoreGameState(state) }

        // Check the displayed card border, not just render-thread submission: the display
        // screenshot can otherwise still contain the preceding end screen.
        val marker = IntArray(2)
        var expectedColor = 0
        activityRule.scenario.onActivity { activity ->
            val card = activity.findViewById<View>(R.id.statusCard)
            card.getLocationOnScreen(marker)
            marker[0] += card.width / 2
            marker[1] += (activity.resources.displayMetrics.density * 1.5f).toInt()
            expectedColor = activity.getColor(
                if (state.status == GameStatus.LOST) R.color.lose_coral else R.color.win_gold,
            )
        }
        val deadline = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot failed" }
            try {
                if (bitmap.getPixel(marker[0], marker[1]) == expectedColor) {
                    val file = File(instrumentation.targetContext.filesDir, fileName)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    assertTrue(file.length() > 0L)
                    return
                }
            } finally {
                bitmap.recycle()
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Display did not show ${state.status} card within 3 s")
    }

    /**
     * Runs [action] on the UI thread, forces a layout pass and waits (5 s timeout) for the next
     * frame. API 29+ waits for the frame to be committed to the render thread; minSdk 23..28 has no
     * such callback, so there it waits for the draw pass plus one posted UI-thread turn.
     */
    private fun runAndAwaitFrame(action: (MainActivity) -> Unit) {
        val latch = CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            action(activity)
            val decor = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29) {
                decor.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }
            } else {
                decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    private var fired = false
                    override fun onDraw() {
                        if (fired) return
                        fired = true
                        decor.post {
                            decor.viewTreeObserver.removeOnDrawListener(this)
                            latch.countDown()
                        }
                    }
                })
            }
            decor.requestLayout()
            decor.invalidate()
        }
        assertTrue("No frame within 5 s", latch.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Shrinks the root layout: "small" is 320x360dp, "wide" is full window width but only 240dp tall. */
    private fun resizeRoot(activity: MainActivity, viewport: String) {
        val density = activity.resources.displayMetrics.density
        val window = activity.window.decorView
        val (width, height) = when (viewport) {
            "small" -> minOf((320 * density).toInt(), window.width) to (360 * density).toInt()
            else -> window.width to (240 * density).toInt()
        }
        val root = activity.findViewById<View>(R.id.root)
        root.layoutParams = FrameLayout.LayoutParams(width, minOf(height, window.height))
    }

    /** Real touch injection through the instrumentation, not performClick(). */
    private fun tap(instrumentation: Instrumentation, x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(down, time, action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun dispatchTouch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Polls (up to 3 s) until the paddle reaches [left]; fails if it never arrives. */
    private fun awaitPaddleLeft(left: Float) {
        var current = Float.NaN
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            activityRule.scenario.onActivity { current = it.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft }
            if (kotlin.math.abs(current - left) <= 0.01f) return
            Thread.sleep(20)
        }
        assertEquals("paddle did not reach the target", left, current, 0.01f)
    }

    /**
     * [MainActivity.restoreGameState] stops the frame loop. Restart it through the existing
     * pause/resume path (no production hook): pause, then click the resume button.
     */
    private fun restoreRunning(activity: MainActivity, state: GameState) {
        activity.restoreGameState(state)
        activity.pauseGame()
        activity.findViewById<Button>(R.id.actionButton).performClick()
    }

    /** Polls (up to 3 s) until the paddle is strictly between its start and the far edge. */
    private fun awaitIntermediatePaddle(maxLeft: Float) {
        var current = Float.NaN
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            activityRule.scenario.onActivity { current = it.findViewById<GameView>(R.id.gameView).engine.state.paddleLeft }
            if (current > 30f && current < maxLeft - 30f) return
            Thread.sleep(10)
        }
        assertTrue("paddle never travelled (left=$current)", false)
    }

    private companion object {
        const val TEST_PREFS = "leaderboard_instrumentation_test"
    }
}
