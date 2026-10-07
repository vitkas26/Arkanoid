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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

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

    @Test fun winAndLossShowScoreAndRestartWithoutNewActivity() {
        activityRule.scenario.onActivity { activity ->
            val gameView = activity.findViewById<GameView>(R.id.gameView)
            val win = GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80)
            activity.restoreGameState(win)
            assertEquals("80", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals(activity.getString(R.string.win_title), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals(activity.getString(R.string.win_button), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)

            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(GameStatus.RUNNING, gameView.engine.state.status)
            assertEquals(0, gameView.engine.state.score)
            assertEquals(8, gameView.engine.state.bricks.size)

            activity.restoreGameState(GameEngine.newGame().copy(status = GameStatus.LOST, score = 20))
            assertEquals("20", activity.findViewById<TextView>(R.id.scoreValue).text.toString())
            assertEquals(activity.getString(R.string.lose_title), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertEquals(activity.getString(R.string.lose_button), activity.findViewById<Button>(R.id.actionButton).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)
            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(GameStatus.RUNNING, gameView.engine.state.status)
            assertEquals(0, gameView.engine.state.score)
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
            assertEquals(View.GONE, activity.findViewById<View>(R.id.statusOverlay).visibility)
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
            Triple(GameStatus.WON, 80, R.string.win_title),
            Triple(GameStatus.LOST, 20, R.string.lose_title),
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
                assertEquals(activity.getString(title), activity.findViewById<TextView>(R.id.statusMessage).text.toString())
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

                activityRule.scenario.onActivity { activity ->
                    val engineState = activity.findViewById<GameView>(R.id.gameView).engine.state
                    assertTrue("$label: activity replaced", activity === activityBefore)
                    assertEquals("$label: status", GameStatus.RUNNING, engineState.status)
                    assertEquals("$label: score", 0, engineState.score)
                    assertEquals("$label: bricks", 8, engineState.bricks.size)
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
                if (state.status == GameStatus.WON) R.color.win_gold else R.color.lose_coral,
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
}
