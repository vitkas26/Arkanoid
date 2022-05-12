package com.example.arkanoidchristmas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.arkanoidchristmas.databinding.ActivityMainBinding

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

            var left = 50
            var top = 250
            throwBall(25, 0)

            for (i in 0..7) {
                if (left < 1050 && top == 250) {
                    blockContainer(left, top)
                    left += 250
                    if (left == 1050) top = 80
                } else if (top == 80) {
                    left -= 250
                    top = 80
                    blockContainer(left, top)
                }
            }
        }
    }

    data class Candy(
        val viewId: Int,
        var topMargin: Int,
        var leftMargin: Int
    )

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

    private fun throwBall(top: Int, left: Int) {
        var top = top
        var threadCheck = 0
        var left = 0
        val activity = bindingMainActivity.container.context as Activity
        val startPoint = bindingMainActivity.myBall.layoutParams as FrameLayout.LayoutParams
        val stick = bindingMainActivity.myStick.layoutParams as FrameLayout.LayoutParams

        val intentWin = Intent(this, Win::class.java)
        val intentGameOver = Intent(this, GameOver::class.java)

        bindingMainActivity.textView3.visibility = View.VISIBLE
        bindingMainActivity.textView4.visibility = View.VISIBLE

        Thread(Runnable {
            while (threadCheck == 0) {
                startPoint.topMargin = startPoint.topMargin + top
                startPoint.leftMargin = startPoint.leftMargin + left
                Thread.sleep(30)
                (bindingMainActivity.container.context as Activity).runOnUiThread {
                    bindingMainActivity.container.removeView(bindingMainActivity.myBall)
                    bindingMainActivity.container.addView(bindingMainActivity.myBall)
                }
                println("${startPoint.topMargin} / ${startPoint.leftMargin}, stick -> ${stick.topMargin} / ${stick.leftMargin}")
                elementsOnContainer.forEach {
                    when {
                        startPoint.topMargin in it.topMargin - 70..it.topMargin + 180 &&
                                startPoint.leftMargin in it.leftMargin - 70..it.leftMargin + 180
                        -> {
                            (bindingMainActivity.container.context as Activity).runOnUiThread {
                                bindingMainActivity.container.removeView(activity.findViewById(it.viewId))
                                elementsOnContainer.remove(it)
                                score += 10
                                bindingMainActivity.textView4.text = score.toString()
                            }

                            if (top > 0) {
                                top *= -1
                                println(top)
                            } else if (top < 0) {
                                top *= -1
                            }
                            when {
                                left > 0 -> {
                                    left *= -1
                                    println(left)
                                }
                                left < 0 -> {
                                    left *= -1
                                    println(left)
                                }
                                else -> left = 0
                            }
                        }
                        startPoint.topMargin < 0 -> {
                            top = 25
                            when {
                                left > 0 -> {
                                    left *= 1
                                    println(left)
                                }
                                left < 0 -> {
                                    left *= 1
                                    println(left)
                                }
                                else -> left = 0
                            }
                        }
                        startPoint.leftMargin > 980 -> {

                            when {
                                left > 0 -> {
                                    left *= -1
                                    println(left)
                                }
//                                    left<0 -> {
//                                        left *= 1
//                                        println(left)
//                                    }
                                else -> println("suka")
                            }
                        }
                        startPoint.leftMargin < 0 -> {

                            if (left > 0) {
                                left *= -1
                                println(left)
                            }
                            if (left < 0) {
                                left *= -1
                            } else
                                left = 0
                        }
                        startPoint.topMargin > 1584 || startPoint.leftMargin > 1000 -> {
                            threadCheck = 1
                            println("game over")
                            startActivity(intentGameOver)
                            finish()
                        }
                        startPoint.topMargin in stick.topMargin - 100..stick.topMargin + 100 && startPoint.leftMargin in stick.leftMargin + 33..stick.leftMargin + 120 -> {
                            top = -25
                            left = 0
                        }

                        startPoint.topMargin in stick.topMargin - 100..stick.topMargin + 120 && startPoint.leftMargin in stick.leftMargin - 50..stick.leftMargin + 33 -> {
                            top = -25
                            left = -15
                        }
                        startPoint.topMargin in stick.topMargin - 100..stick.topMargin + 120 && startPoint.leftMargin in stick.leftMargin..stick.leftMargin + 250 -> {
                            top = -25
                            left = 15
                        }
                    }
                }
                if (elementsOnContainer.isEmpty()) {
                    threadCheck = 1
                    intentWin.putExtra("value", score.toString())
                    startActivity(intentWin)
                    finish()
                } else
                    println("a few more")
            }
        }).start()
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
