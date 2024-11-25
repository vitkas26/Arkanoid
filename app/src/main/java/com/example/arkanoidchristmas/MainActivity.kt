package com.example.arkanoidchristmas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.arkanoidchristmas.databinding.ActivityMainBinding
import com.example.arkanoidchristmas.model.Candy

class MainActivity : AppCompatActivity() {
    private val elementsOnContainer = mutableListOf<Candy>()
    private var score = 0
    private lateinit var bindingMainActivity: ActivityMainBinding

    @SuppressLint("ClickableViewAccessibility", "SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        bindingMainActivity = ActivityMainBinding.inflate(layoutInflater)
        val view = bindingMainActivity.root
        setContentView(view)

        bindingMainActivity.right.setOnTouchListener { _, _ ->
            move(direction = Direction.RIGHT)
            return@setOnTouchListener true
        }
        bindingMainActivity.left.setOnTouchListener { _, _ ->
            move(direction = Direction.LEFT)
            return@setOnTouchListener true
        }

        val button = findViewById<Button>(R.id.button)
        button.setOnClickListener {
            bindingMainActivity.container.removeView(button)
            fillCandyContainer()
            throwBall()
        }
    }

    private fun fillCandyContainer() {
        val startingPosition = Pair(50, 250)

        var leftPosition = startingPosition.first
        var topPosition = startingPosition.second

        for (i in 0..7) {
            if (leftPosition < 1050 && topPosition == 250) {
                blockContainer(leftPosition, topPosition)
                leftPosition += 250
                if (leftPosition == 1050) topPosition = 80
            } else if (topPosition == 80) {
                leftPosition -= 250
                topPosition = 80
                blockContainer(leftPosition, topPosition)
            }
        }
    }

    private fun blockContainer(leftMargin: Int, topMargin: Int) {
        val block = ImageView(bindingMainActivity.container.context)
        block.setImageResource(R.drawable.block)
        val layoutParams = FrameLayout.LayoutParams(250, 250)
        val viewId = View.generateViewId()
        block.id = viewId
        block.layoutParams = layoutParams
        layoutParams.topMargin = topMargin
        layoutParams.leftMargin += leftMargin
        bindingMainActivity.container.addView(block)
        elementsOnContainer.add(Candy(viewId, topMargin, leftMargin))
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {

        when (keyCode) {
            KEYCODE_DPAD_RIGHT -> move(Direction.RIGHT)
            KEYCODE_DPAD_LEFT -> move(Direction.LEFT)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun throwBall() {
        var topDistance = 25
        var leftDistance = 0
        var playGame = true

        val candyContainer = this.bindingMainActivity.container
        val ballPosition = bindingMainActivity.myBall.layoutParams as FrameLayout.LayoutParams
        val stick = bindingMainActivity.myStick.layoutParams as FrameLayout.LayoutParams

        val intentWin = Intent(this, Win::class.java)
        val intentGameOver = Intent(this, GameOver::class.java)

        bindingMainActivity.textView3.visibility = View.VISIBLE
        bindingMainActivity.textView4.visibility = View.VISIBLE

        Thread {
            while (playGame) {
                ballPosition.topMargin += topDistance
                ballPosition.leftMargin += leftDistance
                Thread.sleep(30)
                (bindingMainActivity.container.context as Activity).runOnUiThread {
                    bindingMainActivity.container.removeView(bindingMainActivity.myBall)
                    bindingMainActivity.container.addView(bindingMainActivity.myBall)
                }
                println("${ballPosition.topMargin} / ${ballPosition.leftMargin}, stick -> ${stick.topMargin} / ${stick.leftMargin}")
                elementsOnContainer.forEach {
                    when {
                        whenColission(ballPosition, it) -> {
                            runOnUiThread { onCandyCollision(candyContainer, it) }
                            val directionResult =
                                distinctBallDirectionAfterCollision(topDistance, leftDistance)

                            topDistance = directionResult.first
                            leftDistance = directionResult.second
                        }

                        whenBallIsGoingDown(ballPosition) -> {
                            topDistance = 25
                            leftDistance = distinctLeftDirectionWhenBallGoesDown(leftDistance)
                        }

                        whenBallCollideWithRightBorder(ballPosition) -> {
                            leftDistance =
                                distinctLeftDirectionOnRightBorderColllision(leftDistance)
                        }

                        whenCollideLeftBorder(ballPosition) -> {
                            leftDistance = distinctLeftDistanceOnLeftBorderCollision(leftDistance)
                        }

                        whenBallGoesOutOfScreen(ballPosition) -> {
                            playGame = false
                            println("game over")
                            startActivity(intentGameOver)
                            finish()
                        }

                        checkStickCenterCollision(ballPosition, stick) -> {
                            topDistance = -25
                            leftDistance = 0
                        }

                        checkStickLeftCollision(ballPosition, stick) -> {
                            topDistance = -25
                            leftDistance = -15
                        }

                        checkStickRightCollision(ballPosition, stick) -> {
                            topDistance = -25
                            leftDistance = 15
                        }
                    }
                }
                playGame = checkWin(playGame, intentWin)
            }
        }.start()
    }

    private fun checkWin(playGame: Boolean, intentWin: Intent): Boolean {
        var gameStatus = playGame
        if (elementsOnContainer.isEmpty()) {
            gameStatus = false
            intentWin.putExtra("value", score.toString())
            startActivity(intentWin)
            finish()
        } else
            println("a few more")
        return gameStatus
    }

    private fun checkStickRightCollision(
        ballPosition: FrameLayout.LayoutParams,
        stick: FrameLayout.LayoutParams
    ) =
        ballPosition.topMargin in stick.topMargin - 100..stick.topMargin + 120 && ballPosition.leftMargin in stick.leftMargin..stick.leftMargin + 250

    private fun checkStickLeftCollision(
        ballPosition: FrameLayout.LayoutParams,
        stick: FrameLayout.LayoutParams
    ) =
        ballPosition.topMargin in stick.topMargin - 100..stick.topMargin + 120 && ballPosition.leftMargin in stick.leftMargin - 50..stick.leftMargin + 33

    private fun checkStickCenterCollision(
        ballPosition: FrameLayout.LayoutParams,
        stick: FrameLayout.LayoutParams
    ) = ballPosition.topMargin in stick.topMargin - 100..stick.topMargin + 100
            && ballPosition.leftMargin in stick.leftMargin + 33..stick.leftMargin + 120

    private fun whenBallGoesOutOfScreen(ballPosition: FrameLayout.LayoutParams) =
        ballPosition.topMargin > 1584 || ballPosition.leftMargin > 1000

    private fun distinctLeftDistanceOnLeftBorderCollision(leftDistance: Int): Int {
        var newLeftDistance = leftDistance
        if (newLeftDistance > 0) {
            newLeftDistance *= -1
            println(newLeftDistance)
        }
        if (newLeftDistance < 0) {
            newLeftDistance *= -1
        } else
            newLeftDistance = 0
        return newLeftDistance
    }

    private fun distinctLeftDirectionOnRightBorderColllision(leftDistance: Int): Int {
        var newLeftDistance = leftDistance
        when {
            newLeftDistance > 0 -> {
                newLeftDistance *= -1
                println(newLeftDistance)
            }

            else -> println("suka")
        }
        return newLeftDistance
    }

    private fun whenCollideLeftBorder(startPoint: FrameLayout.LayoutParams) =
        startPoint.leftMargin < 0

    private fun whenBallCollideWithRightBorder(startPoint: FrameLayout.LayoutParams) =
        startPoint.leftMargin > 980

    private fun distinctLeftDirectionWhenBallGoesDown(leftDistance: Int): Int {
        var newLeftDistance = leftDistance
        when {
            newLeftDistance > 0 -> {
                newLeftDistance *= 1
                println(newLeftDistance)
            }

            newLeftDistance < 0 -> {
                newLeftDistance *= 1
                println(newLeftDistance)
            }

            else -> newLeftDistance = 0
        }
        return newLeftDistance
    }

    private fun whenBallIsGoingDown(startPoint: FrameLayout.LayoutParams) = startPoint.topMargin < 0

    private fun whenColission(
        startPoint: FrameLayout.LayoutParams,
        it: Candy
    ) = startPoint.topMargin in it.topMargin - 70..it.topMargin + 180 &&
            startPoint.leftMargin in it.leftMargin - 70..it.leftMargin + 180

    private fun distinctBallDirectionAfterCollision(
        topDistance: Int,
        leftDistance: Int
    ): Pair<Int, Int> {
        var newTopDistance = topDistance
        var newLeftDistance = leftDistance

        if (newTopDistance > 0) {
            newTopDistance *= -1
            println(newTopDistance)
        } else if (newTopDistance < 0) {
            newTopDistance *= -1
        }

        when {
            newLeftDistance > 0 -> {
                newLeftDistance *= -1
                println(newLeftDistance)
            }

            newLeftDistance < 0 -> {
                newLeftDistance *= -1
                println(newLeftDistance)
            }

            else -> newLeftDistance = 0
        }
        return Pair(newTopDistance, newLeftDistance)
    }

    private fun onCandyCollision(candyContainer: FrameLayout, it: Candy) {
        bindingMainActivity.container.removeView(candyContainer.findViewById(it.viewId))
        elementsOnContainer.remove(it)
        score += 10
        bindingMainActivity.textView4.text = score.toString()
    }

    private fun move(direction: Direction) {
        val layoutParams = bindingMainActivity.myStick.layoutParams as FrameLayout.LayoutParams
        when (direction) {
            Direction.RIGHT -> {
                bindingMainActivity.myStick.rotation = 0f
                if (layoutParams.leftMargin < 800) {
                    (bindingMainActivity.myStick.layoutParams as FrameLayout.LayoutParams).leftMargin += 25
                }
            }

            Direction.LEFT -> {
                bindingMainActivity.myStick.rotation = 0f
                if (layoutParams.leftMargin - 20 > 0) {
                    (bindingMainActivity.myStick.layoutParams as FrameLayout.LayoutParams).leftMargin -= 25
                }
            }
        }
        bindingMainActivity.container.removeView(bindingMainActivity.myStick)
        bindingMainActivity.container.addView(bindingMainActivity.myStick)
    }
}
