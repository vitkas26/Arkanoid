package com.example.arkanoidchristmas.game

import java.io.Serializable
import kotlin.math.abs
import kotlin.math.min

/** Coordinates are game units, independent of Android pixels and screen density. */
object GameWorld {
    const val WIDTH = 400f
    const val HEIGHT = 700f
    const val BALL_RADIUS = 18f
    const val PADDLE_WIDTH = 100f
    const val PADDLE_HEIGHT = 50f
    const val PADDLE_TOP = 620f
    const val PADDLE_STEP = 24f
    const val BRICK_SCORE = 10
}

data class Point(val x: Float, val y: Float) : Serializable

data class Brick(val left: Float, val top: Float, val width: Float, val height: Float) : Serializable {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

enum class GameStatus { READY, RUNNING, WON, LOST }

data class GameState(
    val ball: Point,
    val velocity: Point,
    val paddleLeft: Float,
    val bricks: List<Brick>,
    val score: Int,
    val status: GameStatus,
) : Serializable

class GameEngine(initialState: GameState = newGame()) {
    var state: GameState = initialState
        private set

    fun start() {
        if (state.status == GameStatus.READY) {
            state = state.copy(status = GameStatus.RUNNING)
        }
    }

    fun restart() {
        state = newGame()
    }

    fun movePaddleTo(centerX: Float) {
        if (state.status == GameStatus.WON || state.status == GameStatus.LOST) return
        val left = (centerX - GameWorld.PADDLE_WIDTH / 2f)
            .coerceIn(0f, GameWorld.WIDTH - GameWorld.PADDLE_WIDTH)
        state = state.copy(
            paddleLeft = left,
            ball = if (state.status == GameStatus.READY) {
                state.ball.copy(x = left + GameWorld.PADDLE_WIDTH / 2f)
            } else state.ball,
        )
    }

    fun movePaddleBy(distance: Float) {
        movePaddleTo(state.paddleLeft + GameWorld.PADDLE_WIDTH / 2f + distance)
    }

    fun update(deltaSeconds: Float) {
        if (state.status != GameStatus.RUNNING || !deltaSeconds.isFinite() || deltaSeconds <= 0f) return

        // A paused or delayed frame must not teleport the ball through the paddle.
        var remaining = deltaSeconds.coerceAtMost(0.1f)
        while (remaining > 0f && state.status == GameStatus.RUNNING) {
            val step = min(remaining, 1f / 120f)
            advance(step)
            remaining -= step
        }
    }

    private fun advance(step: Float) {
        val previous = state.ball
        var x = previous.x + state.velocity.x * step
        var y = previous.y + state.velocity.y * step
        var vx = state.velocity.x
        var vy = state.velocity.y
        val radius = GameWorld.BALL_RADIUS

        if (x < radius) {
            x = radius + (radius - x)
            vx = abs(vx)
        } else if (x > GameWorld.WIDTH - radius) {
            x = GameWorld.WIDTH - radius - (x - (GameWorld.WIDTH - radius))
            vx = -abs(vx)
        }
        if (y < radius) {
            y = radius + (radius - y)
            vy = abs(vy)
        }

        val paddleLeft = state.paddleLeft
        val paddleRight = paddleLeft + GameWorld.PADDLE_WIDTH
        val crossedPaddle = vy > 0f && previous.y + radius <= GameWorld.PADDLE_TOP &&
            y + radius >= GameWorld.PADDLE_TOP &&
            x + radius >= paddleLeft && x - radius <= paddleRight
        if (crossedPaddle) {
            y = GameWorld.PADDLE_TOP - radius
            vy = -abs(vy)
            val offset = ((x - (paddleLeft + GameWorld.PADDLE_WIDTH / 2f)) /
                (GameWorld.PADDLE_WIDTH / 2f)).coerceIn(-1f, 1f)
            vx = offset * 220f
        }

        val hit = state.bricks.firstOrNull { brick ->
            x + radius >= brick.left && x - radius <= brick.right &&
                y + radius >= brick.top && y - radius <= brick.bottom
        }
        if (hit != null) {
            when {
                previous.y + radius <= hit.top -> {
                    y = hit.top - radius
                    vy = -abs(vy)
                }
                previous.y - radius >= hit.bottom -> {
                    y = hit.bottom + radius
                    vy = abs(vy)
                }
                previous.x + radius <= hit.left -> {
                    x = hit.left - radius
                    vx = -abs(vx)
                }
                previous.x - radius >= hit.right -> {
                    x = hit.right + radius
                    vx = abs(vx)
                }
                else -> vy = -vy
            }

            val remainingBricks = state.bricks.toMutableList().apply { remove(hit) }
            state = state.copy(
                ball = Point(x, y),
                velocity = Point(vx, vy),
                bricks = remainingBricks,
                score = state.score + GameWorld.BRICK_SCORE,
                status = if (remainingBricks.isEmpty()) GameStatus.WON else GameStatus.RUNNING,
            )
            return
        }

        state = state.copy(
            ball = Point(x, y),
            velocity = Point(vx, vy),
            status = if (y - radius > GameWorld.HEIGHT) GameStatus.LOST else GameStatus.RUNNING,
        )
    }

    companion object {
        fun newGame(): GameState {
            val bricks = (0 until 2).flatMap { row ->
                (0 until 4).map { column ->
                    Brick(left = 20f + column * 95f, top = 75f + row * 120f, width = 80f, height = 80f)
                }
            }
            return GameState(
                ball = Point(GameWorld.WIDTH / 2f, GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS),
                velocity = Point(130f, -270f),
                paddleLeft = (GameWorld.WIDTH - GameWorld.PADDLE_WIDTH) / 2f,
                bricks = bricks,
                score = 0,
                status = GameStatus.READY,
            )
        }
    }
}
