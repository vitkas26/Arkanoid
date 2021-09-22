package com.example.arkanoidchristmas

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.marginRight
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    private val elementsOnContainer = mutableListOf<Candy>()
    public var score = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main)

    right.setOnTouchListener{_,event->move(direction = Direction.RIGHT)
    return@setOnTouchListener true
    }
        left.setOnTouchListener{_,event->move(direction = Direction.LEFT)
            return@setOnTouchListener true
        }

        val button = findViewById<Button>(R.id.button)
        button.setOnClickListener {
            container.removeView(button)

            var left = 50
            var top = 250
            throwBall(25,0)

            for(i in 0..7) {
                if(left <1050&&top==250){
                    blockContainer(left, top)
                    left += 250
                    if (left==1050)top=80
                }
                else if(top == 80){
                    left-=250
                    top =80
                    blockContainer(left, top)
                }
            }
        }
        }
data class Candy(
    val viewId:Int,
    var topMargin: Int,
    var leftMargin: Int
)
    private fun blockContainer(leftMargin:Int, topMargin:Int){
        val block = ImageView(container.context)
        block.setImageResource(R.drawable.block)
        val layoutParams = FrameLayout.LayoutParams(250,250)
        val viewId=View.generateViewId()
        block.id = viewId
        block.layoutParams=layoutParams
        layoutParams.topMargin=topMargin
        layoutParams.leftMargin+=leftMargin
        container.addView(block)
        elementsOnContainer.add(Candy(viewId,topMargin,leftMargin))
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {

        when (keyCode){
            KEYCODE_DPAD_RIGHT -> move(Direction.RIGHT)
            KEYCODE_DPAD_LEFT -> move(Direction.LEFT)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun throwBall(top:Int, left:Int) {
        var top = top
        var threadCheck = 0
        var left = 0
        val activity = container.context as Activity
        val startPoint = myBall.layoutParams as FrameLayout.LayoutParams
        val stick = myStick.layoutParams as FrameLayout.LayoutParams

        val intent = Intent(this,win::class.java)
        val intentgameover = Intent(this,gameover::class.java)

        textView3.visibility = View.VISIBLE
        textView4.visibility = View.VISIBLE

            Thread(Runnable {
                while (threadCheck == 0) {
                    startPoint.topMargin = startPoint.topMargin + top
                    startPoint.leftMargin = startPoint.leftMargin + left
                    Thread.sleep(30)
                    (container.context as Activity).runOnUiThread {
                        container.removeView(myBall)
                        container.addView(myBall)
                    }
                    println("${startPoint.topMargin} / ${startPoint.leftMargin}, stick -> ${stick.topMargin} / ${stick.leftMargin}")
                    elementsOnContainer.forEach {
                        when {
                            startPoint.topMargin in it.topMargin-80..it.topMargin + 200 &&
                                    startPoint.leftMargin in it.leftMargin-80..it.leftMargin + 200
                            -> {
                                (container.context as Activity).runOnUiThread {
                                    container.removeView(activity.findViewById(it.viewId))
                                    elementsOnContainer.remove(it)
                                    score += 10
                                    textView4.text=score.toString()
                                }

                            if (top>0) {
                                    top *= -1
                                        println(top)
                                    }
                                   else if (top<0) {
                                        top*=-1
                                    }
                                when {
                                    left>0 -> {
                                        left *= -1
                                        println(left)
                                    }
                                    left<0 -> {
                                        left *= -1
                                        println(left)
                                    }
                                    else -> left = 0
                                }
                            }
                            startPoint.topMargin < 0 -> {
                                top = 25
                                when {
                                    left>0 -> {
                                        left *= 1
                                        println(left)
                                    }
                                    left<0 -> {
                                        left *= 1
                                        println(left)
                                    }
                                    else -> left = 0
                                }
                            }
                            startPoint.leftMargin > 980 -> {

                                when {
                                    left>0 -> {
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

                                if (left>0){
                                    left *= -1
                                    println(left)
                                }
                                if (left<0) {
                                    left *= -1
                                } else
                                    left = 0
                            }
                            startPoint.topMargin > 1584 || startPoint.leftMargin > 1000-> {
                                threadCheck = 1
                                println("game over")
                            startActivity(intentgameover)
                            }
                            startPoint.topMargin in stick.topMargin-100..stick.topMargin + 100 && startPoint.leftMargin in stick.leftMargin+33..stick.leftMargin + 120 -> {
                                top = -25
                                left = 0
                            }

                            startPoint.topMargin in stick.topMargin-100..stick.topMargin + 120 && startPoint.leftMargin in stick.leftMargin-50..stick.leftMargin + 33 -> {
                                top = -25
                                left = -15
                            }
                            startPoint.topMargin in stick.topMargin-100..stick.topMargin + 120 && startPoint.leftMargin in stick.leftMargin..stick.leftMargin + 250 -> {
                                top = -25
                                left = 15
                            }
                        }
                    }
                    if (elementsOnContainer.isEmpty())
                    {
                        threadCheck = 1
                        intent.putExtra("value", score.toString())
                        startActivity(intent)
                        finish()
                    }
                    else
                        println("a few more")
                }
            }).start()
        }

    private fun move(direction:Direction) {
            val layoutParams = myStick.layoutParams as FrameLayout.LayoutParams
        when(direction){
            Direction.RIGHT -> {
                myStick.rotation = 0f
                if (layoutParams.leftMargin < 800) {
                    (myStick.layoutParams as FrameLayout.LayoutParams).leftMargin += 25
                }
            }
            Direction.LEFT -> {
                myStick.rotation = 0f
                if (layoutParams.leftMargin -20 > 0) {
                    (myStick.layoutParams as FrameLayout.LayoutParams).leftMargin -= 25
                }
            }
        }
container.removeView(myStick)
container.addView(myStick)
    }
}
