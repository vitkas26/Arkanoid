package com.example.arkanoidchristmas

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.arkanoidchristmas.databinding.ActivityMainBinding
import com.example.arkanoidchristmas.game.GameEngine
import com.example.arkanoidchristmas.game.GameState
import com.example.arkanoidchristmas.game.GameStatus
import com.example.arkanoidchristmas.game.GameWorld
import com.example.arkanoidchristmas.game.Leaderboard
import com.example.arkanoidchristmas.game.LeaderboardEntry
import com.example.arkanoidchristmas.game.Levels

/** Everything that changes what [MainActivity.renderState] shows; unchanged key means no view work. */
private data class RenderKey(
    val status: GameStatus,
    val score: Int,
    val level: Int,
    val saved: Boolean,
    val lives: Int,
    val resume: Boolean,
    val carried: Int,
    val paused: Boolean,
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: GameEngine
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var shownKey: RenderKey? = null
    private val leaderboard by lazy {
        LeaderboardStore(getSharedPreferences(leaderboardPrefsName, Context.MODE_PRIVATE))
    }

    /** Open dialogs, exposed so instrumentation tests can drive them. */
    internal var nameDialog: Dialog? = null
    internal var leaderboardDialog: Dialog? = null
    internal var exitDialog: Dialog? = null
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
                    // Only a level win that has a successor continues; every final screen offers the
                    // agreed reset dialog (no retry of the same level with refilled lives).
                    if (!Levels.hasNext(engine.state.level) || engine.state.status == GameStatus.LOST) {
                        showExitDialog()
                        return@setOnClickListener
                    }
                    engine.nextLevel()
                    engine.start()
                }
                GameStatus.RUNNING -> paused = false
            }
            renderState()
            binding.gameView.invalidate()
            startFrameLoop()
        }
        binding.pauseButton.setOnClickListener { pauseGame() }
        binding.saveButton.setOnClickListener { showSaveDialog() }
        binding.leaderboardButton.setOnClickListener { showLeaderboard() }
        binding.exitButton.setOnClickListener { showExitDialog() }
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
        val key = RenderKey(
            state.status, state.score, state.level, state.saved, state.lives, state.resume,
            state.carried, paused,
        )
        if (key == shownKey) return
        shownKey = key

        val levelLabel = getString(
            R.string.level_label, state.level, Levels.TOTAL, getString(levelName(state.level)),
        )
        binding.scoreText.text = getString(
            R.string.score_value, levelLabel, state.score,
            if (state.lives > 0) "♥".repeat(state.lives) else "0",
        )
        val overlayVisible = !(state.status == GameStatus.RUNNING && !paused)
        binding.statusOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
        binding.saveButton.visibility =
            if (state.attemptOver && !state.saved) View.VISIBLE else View.GONE
        binding.leaderboardButton.visibility =
            if (state.status == GameStatus.RUNNING && !paused) View.GONE else View.VISIBLE
        val pristine = state.status == GameStatus.READY && !state.resume && state.level == 1 &&
            state.carried == 0 && state.score == 0 && state.lives == GameWorld.MAX_LIVES
        // On final screens the main button already is "Начать заново": no duplicate secondary one.
        binding.exitButton.visibility =
            if (overlayVisible && !pristine && !state.attemptOver) View.VISIBLE else View.GONE
        val speed = getString(R.string.speed_label, (Levels.speedFactor(state.level) * 100).roundToInt())
        // The big number of a finished attempt is exactly what the breakdown and the leaderboard use;
        // an intermediate win shows just that level's score.
        val endScore = if (state.attemptOver) state.finalScore else state.score
        val breakdown = if (state.attemptOver) {
            "\n" + getString(R.string.breakdown, state.attemptScore, state.lifeBonus, state.finalScore)
        } else ""
        binding.pauseButton.visibility =
            if (state.status == GameStatus.RUNNING && !paused) View.VISIBLE else View.GONE
        when (state.status) {
            GameStatus.READY -> if (state.resume) showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = getString(R.string.life_lost_title),
                caption = getString(R.string.life_lost_caption, levelLabel, state.lives) + "\n" + speed,
                buttonText = R.string.resume,
                icon = null,
                stars = false,
                score = state.score,
            ) else showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = getString(R.string.game_title),
                caption = getString(R.string.ready_caption_level, levelLabel) + "\n" + speed,
                buttonText = R.string.start,
                icon = null,
                stars = false,
                score = null,
            )
            GameStatus.WON -> showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = getString(R.string.win_title, state.level),
                caption = getString(R.string.win_caption, levelLabel) + breakdown,
                buttonText = if (Levels.hasNext(state.level)) R.string.win_button_next else R.string.exit_button,
                icon = R.drawable.ic_trophy to R.string.win_icon_description,
                stars = true,
                score = endScore,
            )
            GameStatus.LOST -> showCard(
                background = R.drawable.end_card_lose,
                button = R.drawable.end_button_lose,
                title = getString(R.string.lose_title),
                caption = getString(R.string.lose_caption) + breakdown,
                buttonText = R.string.exit_button,
                icon = R.drawable.ic_retry to R.string.lose_icon_description,
                stars = false,
                score = endScore,
            )
            GameStatus.RUNNING -> if (paused) showCard(
                background = R.drawable.end_card_win,
                button = R.drawable.end_button_win,
                title = getString(R.string.pause_title),
                caption = getString(R.string.pause_caption, levelLabel) + "\n" + speed,
                buttonText = R.string.resume,
                icon = null,
                stars = false,
                score = state.score,
            )
        }
    }

    /** Themed card dialog from [layout]: transparent window, limited width, resizes for the keyboard. */
    private fun cardDialog(layout: Int): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layout)
        val metrics = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                minOf((metrics.widthPixels * 0.92f).toInt(), resources.getDimensionPixelSize(R.dimen.dialog_max_width)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // Whole card scrolls on short screens or above the keyboard, so every action stays reachable.
        dialog.findViewById<MaxHeightScrollView>(R.id.dialogScroll)?.maxHeightPx =
            (metrics.heightPixels * 0.92f).toInt()
        return dialog
    }

    private fun showSaveDialog() {
        val state = engine.state
        if (state.saved || !state.attemptOver) return
        val dialog = cardDialog(R.layout.dialog_name)
        val input = dialog.findViewById<EditText>(R.id.nameInput)
        input.setText(Leaderboard.DEFAULT_NAME)
        input.selectAll()
        dialog.findViewById<TextView>(R.id.nameSummary).text = getString(
            R.string.save_dialog_message,
            getString(R.string.breakdown, state.attemptScore, state.lifeBonus, state.finalScore),
            state.level,
        )
        dialog.findViewById<Button>(R.id.nameConfirm).setOnClickListener {
            val saved = saveResult(input.text?.toString())
            dialog.dismiss()
            if (saved) showLeaderboard()
        }
        dialog.findViewById<Button>(R.id.nameCancel).setOnClickListener { dialog.dismiss() }
        nameDialog = dialog
        dialog.show()
    }

    /** Saves the finished attempt once; later calls for the same attempt are ignored. */
    internal fun saveResult(rawName: String?): Boolean {
        val state = engine.state
        if (!engine.markSaved()) return false
        leaderboard.add(LeaderboardEntry(Leaderboard.normalizeName(rawName), state.finalScore, state.level))
        renderState()
        return true
    }

    internal fun leaderboardText(): String {
        val entries = leaderboard.load()
        if (entries.isEmpty()) return getString(R.string.leaderboard_empty)
        return entries.mapIndexed { index, entry ->
            getString(R.string.leaderboard_row, index + 1, entry.name, entry.score, entry.level)
        }.joinToString("\n")
    }

    private fun showLeaderboard() {
        val dialog = cardDialog(R.layout.dialog_leaderboard)
        val rows = dialog.findViewById<ViewGroup>(R.id.leaderboardRows)
        val entries = leaderboard.load()
        if (entries.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = getString(R.string.leaderboard_empty)
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.end_text_secondary))
                textSize = 16f
                setPadding(0, 16, 0, 16)
            })
        }
        entries.forEachIndexed { index, entry ->
            val row = layoutInflater.inflate(R.layout.row_leaderboard, rows, false)
            row.findViewById<TextView>(R.id.rowPlace).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.rowName).text = entry.name
            row.findViewById<TextView>(R.id.rowScore).text = entry.score.toString()
            row.findViewById<TextView>(R.id.rowLevel).text = getString(R.string.leaderboard_level_short, entry.level)
            rows.addView(row)
        }
        dialog.findViewById<Button>(R.id.leaderboardClose).setOnClickListener { dialog.dismiss() }
        leaderboardDialog = dialog
        dialog.show()
    }

    /** Asks to reset the game to a fresh level 1; never touches the saved leaderboard. */
    private fun showExitDialog() {
        if (engine.state.status == GameStatus.RUNNING && !paused) return
        val dialog = cardDialog(R.layout.dialog_exit)
        dialog.findViewById<TextView>(R.id.exitMessage).text = getString(R.string.exit_message)
        dialog.findViewById<Button>(R.id.exitConfirm).setOnClickListener {
            dialog.dismiss()
            exitToFirstLevel()
        }
        dialog.findViewById<Button>(R.id.exitCancel).setOnClickListener { dialog.dismiss() }
        exitDialog = dialog
        dialog.show()
    }

    /** Back to a fresh level 1 (3 lives, nothing carried) in the same Activity. */
    internal fun exitToFirstLevel() {
        stopFrameLoop()
        paused = false
        engine.exitToFirstLevel()
        binding.gameView.invalidate()
        renderState()
    }

    override fun onDestroy() {
        nameDialog?.dismiss()
        leaderboardDialog?.dismiss()
        exitDialog?.dismiss()
        super.onDestroy()
    }

    private fun showCard(
        background: Int,
        button: Int,
        title: String,
        caption: String,
        buttonText: Int,
        icon: Pair<Int, Int>?,
        stars: Boolean,
        score: Int?,
    ) {
        binding.statusCard.setBackgroundResource(background)
        binding.actionButton.setBackgroundResource(button)
        binding.statusMessage.text = title
        binding.statusCaption.text = caption
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

    private fun levelName(level: Int) = when (level) {
        2 -> R.string.level_name_2
        3 -> R.string.level_name_3
        4 -> R.string.level_name_4
        5 -> R.string.level_name_5
        else -> R.string.level_name_1
    }

    companion object {
        /** SharedPreferences file of the leaderboard; instrumentation tests point it at a scratch file. */
        internal var leaderboardPrefsName = "leaderboard"
        private const val STATE_KEY = "game_state"
        private const val PAUSED_KEY = "game_paused"
    }
}
