package com.example.arkanoidchristmas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.arkanoidchristmas.game.GameEngine
import com.example.arkanoidchristmas.game.GameWorld
import kotlin.math.min

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var engine: GameEngine = GameEngine()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val background = bitmap(R.drawable.bg)
    private val block = bitmap(R.drawable.block)
    private val ball = bitmap(R.drawable.ball)
    private val paddle = bitmap(R.drawable.stick)
    private val destination = RectF()
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    init {
        isFocusable = true
        contentDescription = context.getString(R.string.game_field_description)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        scale = min(w / GameWorld.WIDTH, h / GameWorld.HEIGHT)
        originX = (w - GameWorld.WIDTH * scale) / 2f
        originY = (h - GameWorld.HEIGHT * scale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)

        drawSprite(canvas, background, 0f, 0f, GameWorld.WIDTH, GameWorld.HEIGHT)
        engine.state.bricks.forEach { brick ->
            drawSprite(canvas, block, brick.left, brick.top, brick.width, brick.height)
        }
        drawSprite(
            canvas,
            paddle,
            engine.state.paddleLeft,
            GameWorld.PADDLE_TOP,
            GameWorld.PADDLE_WIDTH,
            GameWorld.PADDLE_HEIGHT,
        )
        val radius = GameWorld.BALL_RADIUS
        drawSprite(
            canvas,
            ball,
            engine.state.ball.x - radius,
            engine.state.ball.y - radius,
            radius * 2f,
            radius * 2f,
        )
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val worldX = (event.x - originX) / scale
                engine.movePaddleTo(worldX)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Visible playfield in this View's pixel coordinates; useful for UI verification. */
    fun playfieldBounds(): RectF = RectF(
        originX,
        originY,
        originX + GameWorld.WIDTH * scale,
        originY + GameWorld.HEIGHT * scale,
    )

    private fun drawSprite(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        destination.set(left, top, left + width, top + height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    private fun bitmap(id: Int): Bitmap = requireNotNull(BitmapFactory.decodeResource(resources, id)) {
        "Missing game image resource: $id"
    }
}
