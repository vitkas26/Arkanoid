package com.example.arkanoidchristmas

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
import com.example.arkanoidchristmas.game.GameEngine
import com.example.arkanoidchristmas.game.GameStatus
import com.example.arkanoidchristmas.game.GameWorld
import com.example.arkanoidchristmas.game.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

            val touch = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, field.right + 50f, field.centerY(), 0)
            gameView.dispatchTouchEvent(touch)
            touch.recycle()
            assertEquals(GameWorld.WIDTH - GameWorld.PADDLE_WIDTH, gameView.engine.state.paddleLeft, 0.01f)

            activity.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, null)
            assertEquals(
                GameWorld.WIDTH - GameWorld.PADDLE_WIDTH - GameWorld.PADDLE_STEP,
                gameView.engine.state.paddleLeft,
                0.01f,
            )
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
            assertTrue(activity.findViewById<TextView>(R.id.statusMessage).text.contains("80"))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statusOverlay).visibility)

            activity.findViewById<Button>(R.id.actionButton).performClick()
            assertEquals(GameStatus.RUNNING, gameView.engine.state.status)
            assertEquals(0, gameView.engine.state.score)
            assertEquals(8, gameView.engine.state.bricks.size)

            activity.restoreGameState(GameEngine.newGame().copy(status = GameStatus.LOST, score = 20))
            assertTrue(activity.findViewById<TextView>(R.id.statusMessage).text.contains("20"))
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
}
