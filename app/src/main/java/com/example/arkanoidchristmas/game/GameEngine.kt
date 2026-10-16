package com.example.arkanoidchristmas.game

import java.io.Serializable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Coordinates are game units, independent of Android pixels and screen density. */
object GameWorld {
    const val WIDTH = 400f
    const val HEIGHT = 700f
    const val BALL_RADIUS = 18f
    const val PADDLE_WIDTH = 100f
    const val PADDLE_HEIGHT = 50f
    const val PADDLE_TOP = 620f
    const val PADDLE_STEP = 24f
    /** Maximum speed of the paddle when following a touch target, in game units per second. */
    const val PADDLE_SPEED = 600f
    const val BRICK_SCORE = 10
    /** Lives per attempt (shared by all levels of the attempt, never refilled). */
    const val MAX_LIVES = 3
    /** Bonus per remaining life when the last available level is cleared. */
    const val LIFE_BONUS = 20
    val START_VELOCITY = Point(130f, -270f)
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
    val level: Int = Levels.first.number,
    /** Sum of the scores of levels already cleared in this attempt. */
    val carried: Int = 0,
    /** True once the finished attempt has been saved to the leaderboard. */
    val saved: Boolean = false,
    val lives: Int = GameWorld.MAX_LIVES,
    /** READY after a lost life: the ball is back on the paddle and waits for "Continue". */
    val resume: Boolean = false,
) : Serializable {
    /** Base score of the attempt so far (no life bonus). */
    val attemptScore: Int get() = carried + score

    /** Life bonus: only for a win of the last available level. */
    val lifeBonus: Int
        get() = if (status == GameStatus.WON && !Levels.hasNext(level)) lives * GameWorld.LIFE_BONUS else 0

    /** What goes to the leaderboard: base score plus life bonus (zero unless the attempt was completed). */
    val finalScore: Int get() = attemptScore + lifeBonus

    /** The attempt ends on any loss, or on a win of the last implemented level. */
    val attemptOver: Boolean
        get() = status == GameStatus.LOST || (status == GameStatus.WON && !Levels.hasNext(level))
}

class GameEngine(initialState: GameState = newGame()) {
    var state: GameState = initialState
        private set

    /** Left edge the paddle is travelling to (touch input); not part of [GameState]. */
    private var paddleTargetLeft: Float? = null

    fun start() {
        if (state.status == GameStatus.READY) {
            state = state.copy(status = GameStatus.RUNNING, resume = false)
        }
    }

    fun restart() {
        paddleTargetLeft = null
        state = newGame(state.level)
    }

    /** After a win, moves to the next implemented level (score 0, READY); otherwise does nothing. */
    fun nextLevel() {
        if (state.status != GameStatus.WON || !Levels.hasNext(state.level)) return
        paddleTargetLeft = null
        state = newGame(state.level + 1).copy(carried = state.attemptScore, lives = state.lives)
    }

    /** Leaves the attempt: back to a fresh READY level 1 with 3 lives and nothing carried. */
    fun exitToFirstLevel() {
        paddleTargetLeft = null
        state = newGame()
    }

    /**
     * Marks the attempt as saved; false if it was already saved, or is unfinished and [early]
     * (an early exit that saves the base score) is not requested.
     */
    fun markSaved(early: Boolean = false): Boolean {
        if (state.saved || (!state.attemptOver && !early)) return false
        state = state.copy(saved = true)
        return true
    }

    /**
     * Touch input: while RUNNING the paddle travels towards [centerX] at no more than
     * [GameWorld.PADDLE_SPEED] during [update] and keeps going after the finger is lifted.
     * Before the first launch the paddle still follows directly so the ball can be aimed.
     */
    fun setPaddleTarget(centerX: Float) {
        if (!centerX.isFinite()) return
        when (state.status) {
            GameStatus.READY -> movePaddleTo(centerX)
            GameStatus.RUNNING -> paddleTargetLeft = (centerX - GameWorld.PADDLE_WIDTH / 2f)
                .coerceIn(0f, GameWorld.WIDTH - GameWorld.PADDLE_WIDTH)
            GameStatus.WON, GameStatus.LOST -> Unit
        }
    }

    /** Direct (instant) move for keys and service code; cancels any touch target. */
    fun movePaddleTo(centerX: Float) {
        if (state.status == GameStatus.WON || state.status == GameStatus.LOST) return
        paddleTargetLeft = null
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
            val paddleStart = state.paddleLeft
            stepPaddle(step)
            advance(step, paddleStart, state.paddleLeft)
            remaining -= step
        }
    }

    private fun stepPaddle(step: Float) {
        val target = paddleTargetLeft ?: return
        val distance = target - state.paddleLeft
        val maxMove = GameWorld.PADDLE_SPEED * step
        val left = if (abs(distance) <= maxMove) {
            paddleTargetLeft = null
            target
        } else {
            state.paddleLeft + sign(distance) * maxMove
        }
        state = state.copy(paddleLeft = left)
    }

    /** Paddle left edge [t] seconds into a sub-step: full speed towards the end position, then stopped. */
    private fun paddleLeftAt(t: Float, paddleStart: Float, paddleEnd: Float): Float {
        val distance = paddleEnd - paddleStart
        return paddleStart + sign(distance) * min(GameWorld.PADDLE_SPEED * t, abs(distance))
    }

    /** Ball x [t] seconds into a sub-step, with the side walls mirroring the straight path. */
    private fun ballXAt(startX: Float, vx: Float, t: Float): Float {
        val radius = GameWorld.BALL_RADIUS
        val x = startX + vx * t
        return when {
            x < radius -> radius + (radius - x)
            x > GameWorld.WIDTH - radius -> GameWorld.WIDTH - radius - (x - (GameWorld.WIDTH - radius))
            else -> x
        }
    }

    private fun advance(step: Float, paddleStart: Float, paddleEnd: Float) {
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

        // The paddle is hit where and when the ball reaches its top plane, not at the end of the
        // sub-step: ball and (possibly still travelling) paddle are both taken at that instant.
        val startVy = state.velocity.y
        if (startVy > 0f && previous.y + radius <= GameWorld.PADDLE_TOP) {
            val contactTime = (GameWorld.PADDLE_TOP - radius - previous.y) / startVy
            if (contactTime <= step) {
                val contactX = ballXAt(previous.x, state.velocity.x, contactTime)
                val paddleLeft = paddleLeftAt(contactTime, paddleStart, paddleEnd)
                if (contactX + radius >= paddleLeft && contactX - radius <= paddleLeft + GameWorld.PADDLE_WIDTH) {
                    val offset = ((contactX - (paddleLeft + GameWorld.PADDLE_WIDTH / 2f)) /
                        (GameWorld.PADDLE_WIDTH / 2f)).coerceIn(-1f, 1f)
                    // Ball speed grows per level; vy keeps its current magnitude, so nothing accumulates.
                    vx = offset * 220f * Levels.speedFactor(state.level)
                    vy = -abs(startVy)
                    // Spend the rest of the sub-step moving away from the paddle.
                    val rest = step - contactTime
                    x = contactX + vx * rest
                    y = GameWorld.PADDLE_TOP - radius + vy * rest
                    // Only a ball at/over the wall AND still heading out is mirrored: exactly on the
                    // boundary (rest = 0) the new direction must point inwards, never be left outward.
                    if (x <= radius && vx < 0f) {
                        x = radius + (radius - x)
                        vx = abs(vx)
                    } else if (x >= GameWorld.WIDTH - radius && vx > 0f) {
                        x = GameWorld.WIDTH - radius - (x - (GameWorld.WIDTH - radius))
                        vx = -abs(vx)
                    }
                }
            }
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

        if (y - radius > GameWorld.HEIGHT) {
            loseLife()
            return
        }
        state = state.copy(ball = Point(x, y), velocity = Point(vx, vy))
    }

    /** The ball fell: last life ends the attempt, otherwise it waits on the paddle for "Continue". */
    private fun loseLife() {
        val lives = state.lives - 1
        paddleTargetLeft = null
        state = if (lives <= 0) {
            state.copy(lives = 0, status = GameStatus.LOST)
        } else {
            state.copy(
                lives = lives,
                status = GameStatus.READY,
                resume = true,
                ball = Point(
                    state.paddleLeft + GameWorld.PADDLE_WIDTH / 2f,
                    GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS,
                ),
                velocity = Levels.startVelocity(state.level),
            )
        }
    }

    companion object {
        fun newGame(level: Int = Levels.first.number): GameState {
            val definition = Levels.byNumber(level)
            return GameState(
                ball = Point(GameWorld.WIDTH / 2f, GameWorld.PADDLE_TOP - GameWorld.BALL_RADIUS),
                velocity = Levels.startVelocity(definition.number),
                paddleLeft = (GameWorld.WIDTH - GameWorld.PADDLE_WIDTH) / 2f,
                bricks = definition.bricks,
                score = 0,
                status = GameStatus.READY,
                level = definition.number,
            )
        }
    }
}
