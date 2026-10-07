package com.example.arkanoidchristmas

import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.arkanoidchristmas.databinding.ActivityMainBinding
import com.example.arkanoidchristmas.game.GameEngine
import com.example.arkanoidchristmas.game.GameState
import com.example.arkanoidchristmas.game.GameStatus
import com.example.arkanoidchristmas.game.GameWorld

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: GameEngine
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var shownStatus: GameStatus? = null
    private var shownScore = -1
    private var shownPaused = false
    private var paused = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameScheduled) return
            if (lastFrameNanos != 0L) {
                val elapsed = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                engine.update(elapsed)
            }
            lastFrameNanos = frameTimeNanos
            binding.gameView.invalidate()
            renderState()
            if (engine.state.status == GameStatus.RUNNING) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                frameScheduled = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restoredState = if (Build.VERSION.SDK_INT >= 33) {
            savedInstanceState?.getSerializable(STATE_KEY, GameState::class.java)
        } else {
            @Suppress("DEPRECATION")
            savedInstanceState?.getSerializable(STATE_KEY) as? GameState
        }
        restoreGameState(
            restoredState ?: GameEngine.newGame(),
            paused = savedInstanceState?.getBoolean(PAUSED_KEY, false) ?: false,
        )
        binding.actionButton.setOnClickListener {
            when (engine.state.status) {
                GameStatus.READY -> engine.start()
                GameStatus.WON, GameStatus.LOST -> {
                    engine.restart()
                    engine.start()
                }
                GameStatus.RUNNING -> paused = false
            }
            renderState()
            binding.gameView.invalidate()
            startFrameLoop()
        }
        binding.pauseButton.setOnClickListener { pauseGame() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** Freezes a running game in place; the engine state is untouched and resumes from it. */
    internal fun pauseGame() {
        if (!::engine.isInitialized || engine.state.status != GameStatus.RUNNING || paused) return
        paused = true
        stopFrameLoop()
        renderState()
    }

    internal fun restoreGameState(state: GameState, paused: Boolean = false) {
        stopFrameLoop()
        this.paused = paused && state.status == GameStatus.RUNNING
        engine = GameEngine(state)
        binding.gameView.engine = engine
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (::engine.isInitialized) startFrameLoop()
    }

    override fun onPause() {
        stopFrameLoop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable(STATE_KEY, engine.state)
        outState.putBoolean(PAUSED_KEY, paused)
        super.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (paused) return super.onKeyDown(keyCode, event)
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> -1f
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> 1f
            else -> return super.onKeyDown(keyCode, event)
        }
        engine.movePaddleBy(direction * GameWorld.PADDLE_STEP)
        binding.gameView.invalidate()
        return true
    }

    private fun startFrameLoop() {
        if (engine.state.status != GameStatus.RUNNING || paused || frameScheduled) return
        lastFrameNanos = 0L
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameScheduled = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun renderState() {
        val state = engine.state
        // Called on every frame: skip all view work unless something visible changed.
        if (state.status == shownStatus && state.score == shownScore && paused == shownPaused) return
        shownStatus = state.status
        shownScore = state.score
        shownPaused = paused

        binding.scoreText.text = getString(R.string.score_value, state.score)
        binding.statusOverlay.visibility =
            if (state.status == GameStatus.RUNNING && !paused) View.GONE else View.VISIBLE
        binding.pauseButton.visibility =
            if (state.status == GameStatus.RUNNING && !paused) View.VISIBLE else View.GONE
        when (state.status) {
            GameStatus.READY -> showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = R.string.game_title,
                caption = R.string.ready_caption,
                buttonText = R.string.start,
                icon = null,
                stars = false,
                score = null,
            )
            GameStatus.WON -> showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = R.string.win_title,
                caption = R.string.win_caption,
                buttonText = R.string.win_button,
                icon = R.drawable.ic_trophy to R.string.win_icon_description,
                stars = true,
                score = state.score,
            )
            GameStatus.LOST -> showCard(
                background = R.drawable.end_card_lose,
                button = R.drawable.end_button_lose,
                title = R.string.lose_title,
                caption = R.string.lose_caption,
                buttonText = R.string.lose_button,
                icon = R.drawable.ic_retry to R.string.lose_icon_description,
                stars = false,
                score = state.score,
            )
            GameStatus.RUNNING -> if (paused) showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = R.string.pause_title,
                caption = R.string.pause_caption,
                buttonText = R.string.resume,
                icon = null,
                stars = false,
                score = state.score,
            )
        }
    }

    private fun showCard(
        background: Int,
        button: Int,
        title: Int,
        caption: Int,
        buttonText: Int,
        icon: Pair<Int, Int>?,
        stars: Boolean,
        score: Int?,
    ) {
        binding.statusCard.setBackgroundResource(background)
        binding.actionButton.setBackgroundResource(button)
        binding.statusMessage.setText(title)
        binding.statusCaption.setText(caption)
        binding.actionButton.setText(buttonText)
        binding.statusStars.visibility = if (stars) View.VISIBLE else View.GONE
        binding.statusIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
        if (icon != null) {
            binding.statusIcon.setImageResource(icon.first)
            binding.statusIcon.contentDescription = getString(icon.second)
        }
        val scoreVisibility = if (score != null) View.VISIBLE else View.GONE
        binding.scoreLabel.visibility = scoreVisibility
        binding.scoreValue.visibility = scoreVisibility
        if (score != null) binding.scoreValue.text = score.toString()
    }

    companion object {
        private const val STATE_KEY = "game_state"
        private const val PAUSED_KEY = "game_paused"
    }
}
