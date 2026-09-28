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
        restoreGameState(restoredState ?: GameEngine.newGame())
        binding.actionButton.setOnClickListener {
            when (engine.state.status) {
                GameStatus.READY -> engine.start()
                GameStatus.WON, GameStatus.LOST -> {
                    engine.restart()
                    engine.start()
                }
                GameStatus.RUNNING -> Unit
            }
            renderState()
            binding.gameView.invalidate()
            startFrameLoop()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    internal fun restoreGameState(state: GameState) {
        stopFrameLoop()
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
        super.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        if (engine.state.status != GameStatus.RUNNING || frameScheduled) return
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
        binding.scoreText.text = getString(R.string.score_value, state.score)
        binding.statusOverlay.visibility = if (state.status == GameStatus.RUNNING) View.GONE else View.VISIBLE
        when (state.status) {
            GameStatus.READY -> {
                binding.statusMessage.setText(R.string.game_title)
                binding.actionButton.setText(R.string.start)
            }
            GameStatus.WON -> {
                binding.statusMessage.text = getString(R.string.win_message, state.score)
                binding.actionButton.setText(R.string.play_again)
            }
            GameStatus.LOST -> {
                binding.statusMessage.text = getString(R.string.lose_message, state.score)
                binding.actionButton.setText(R.string.play_again)
            }
            GameStatus.RUNNING -> Unit
        }
    }

    companion object {
        private const val STATE_KEY = "game_state"
    }
}
