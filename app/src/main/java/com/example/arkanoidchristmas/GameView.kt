package com.example.arkanoidchristmas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
    private val atlas = bitmap(R.drawable.sprite_atlas)
    private val destination = RectF()

    // Opaque bounds (alpha >= 16) of each sprite inside the 1254x1254 atlas.
    private val ballSource = Rect(440, 95, 814, 470)
    private val brickSource = Rect(269, 558, 984, 849)
    private val paddleSource = Rect(84, 942, 1169, 1179)
    private val paddleHeight =
        GameWorld.PADDLE_WIDTH * paddleSource.height() / paddleSource.width()
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

        drawSprite(canvas, background, null, 0f, 0f, GameWorld.WIDTH, GameWorld.HEIGHT)
        engine.state.bricks.forEach { brick ->
            drawSprite(canvas, atlas, brickSource, brick.left, brick.top, brick.width, brick.height)
        }
        drawSprite(
            canvas,
            atlas,
            paddleSource,
            engine.state.paddleLeft,
            GameWorld.PADDLE_TOP,
            GameWorld.PADDLE_WIDTH,
            paddleHeight,
        )
        val radius = GameWorld.BALL_RADIUS
        drawSprite(
            canvas,
            atlas,
            ballSource,
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
                engine.setPaddleTarget(worldX)
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
        source: Rect?,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        destination.set(left, top, left + width, top + height)
        canvas.drawBitmap(bitmap, source, destination, paint)
    }

    private fun bitmap(id: Int): Bitmap = requireNotNull(BitmapFactory.decodeResource(resources, id)) {
        "Missing game image resource: $id"
    }
}
