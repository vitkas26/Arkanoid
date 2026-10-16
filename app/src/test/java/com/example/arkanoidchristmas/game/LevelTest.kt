package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LevelTest {
    private val levels = listOf(Levels.first, Levels.second, Levels.third, Levels.fourth, Levels.fifth)

    /** Hand-written figures: bricks per row, top to bottom (tree, castle, diamond, cross, heart). */
    private val expectedRows = listOf(
        listOf(1, 2, 3, 1, 1),
        listOf(3, 5, 4),
        listOf(1, 2, 3, 4, 3, 2, 1),
        listOf(2, 2, 6, 6, 2, 2),
        listOf(4, 8, 6, 4, 2),
    )

    private fun rowsOf(bricks: List<Brick>): List<Int> =
        bricks.groupBy { it.top }.toSortedMap().values.map { it.size }

    @Test fun levelsOneToFiveExistAndTheSixthDoesNot() {
        assertEquals(5, Levels.TOTAL)
        assertEquals(5, Levels.IMPLEMENTED)
        assertEquals(listOf(1, 2, 3, 4, 5), levels.map { it.number })
        assertEquals(levels, (1..5).map { Levels.byNumber(it) })
        assertEquals((1..4).map { true } + false, (1..5).map { Levels.hasNext(it) })
        for (unknown in listOf(0, 6, 99, -1)) {
            assertEquals(1, GameEngine.newGame(unknown).level)
            assertEquals(Levels.first.bricks, GameEngine.newGame(unknown).bricks)
            assertEquals(1f, Levels.speedFactor(unknown), 0f)
        }
    }

    @Test fun brickCountsScoresAndMaximumBalance() {
        assertEquals(listOf(8, 12, 16, 20, 24), levels.map { it.bricks.size })
        assertEquals(listOf(80, 120, 160, 200, 240), levels.map { it.bricks.size * GameWorld.BRICK_SCORE })
        val base = levels.sumOf { it.bricks.size * GameWorld.BRICK_SCORE }
        assertEquals(800, base)
        assertEquals(860, base + GameWorld.MAX_LIVES * GameWorld.LIFE_BONUS)
        assertEquals(levels.map { it.bricks }, (1..5).map { GameEngine.newGame(it).bricks })
    }

    @Test fun everyLevelIsADistinctFigureWithTheExpectedRows() {
        assertEquals(expectedRows, levels.map { rowsOf(it.bricks) })
        // Same bricks moved to the origin: no two levels share a shape.
        val shapes = levels.map { level ->
            val left = level.bricks.minOf { it.left }
            val top = level.bricks.minOf { it.top }
            level.bricks.map { Triple(Math.round(it.left - left), Math.round(it.top - top), Math.round(it.width)) }.toSet()
        }
        for (a in shapes.indices) for (b in a + 1 until shapes.size) {
            assertTrue("levels ${a + 1} and ${b + 1} look the same", shapes[a] != shapes[b])
        }
    }

    @Test fun everyFigureIsMirrorBalancedInsideTheFieldWithoutOverlap() {
        for (level in levels) {
            val bricks = level.bricks
            val name = "level ${level.number}"
            assertEquals(name, bricks.minOf { it.left }, GameWorld.WIDTH - bricks.maxOf { it.right }, 0.001f)
            for (brick in bricks) {
                val mirrored = GameWorld.WIDTH - brick.right
                assertTrue("$name: no mirror of $brick", bricks.any {
                    it.top == brick.top && it.width == brick.width && abs(it.left - mirrored) < 0.001f
                })
                assertTrue("$name: outside the field", brick.left >= 0f && brick.right <= GameWorld.WIDTH && brick.top >= 0f)
                assertTrue("$name: bricks must stay wide enough for the ball", brick.width >= 36f && brick.height >= 28f)
            }
            for (a in bricks.indices) for (b in a + 1 until bricks.size) {
                val x = bricks[a]; val y = bricks[b]
                assertFalse("$name: $a/$b overlap", x.left < y.right && y.left < x.right && x.top < y.bottom && y.top < x.bottom)
            }
        }
    }

    @Test fun startCorridorStaysFreeBelowEveryFigure() {
        val start = GameEngine.newGame().ball
        for (level in levels) {
            val lowest = level.bricks.maxOf { it.bottom }
            // At least a ball's diameter plus a generous corridor between the lowest brick and the ball.
            assertTrue("level ${level.number}: $lowest", lowest < start.y - GameWorld.BALL_RADIUS - 100f)
            assertTrue(level.bricks.none { it.bottom >= start.y - GameWorld.BALL_RADIUS })
        }
    }

    @Test fun restartKeepsLevelAndRestoresLayoutAndScore() {
        val engine = GameEngine(GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80))
        engine.restart()
        assertEquals(GameEngine.newGame(), engine.state)
        assertEquals(1, engine.state.level)
        assertEquals(8, engine.state.bricks.size)
        assertEquals(0, engine.state.score)
    }

    @Test fun clearingTheLastBrickOfEachLevelWinsWithItsFullScore() {
        for (level in levels) {
            val last = level.bricks.maxByOrNull { it.bottom }!!
            val total = level.bricks.size * GameWorld.BRICK_SCORE
            val engine = GameEngine(GameEngine.newGame(level.number).copy(
                status = GameStatus.RUNNING, bricks = listOf(last), velocity = Point(0f, -200f),
                ball = Point(last.left + last.width / 2f, last.bottom + GameWorld.BALL_RADIUS + 1f),
                score = total - GameWorld.BRICK_SCORE,
            ))
            engine.update(0.02f)
            assertEquals(GameStatus.WON, engine.state.status)
            assertEquals(total, engine.state.score)
        }
    }

    @Test fun nextLevelAfterWinOneStartsLevelTwoReadyWithZeroScore() {
        val engine = GameEngine(GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80))
        engine.nextLevel()
        assertEquals(GameEngine.newGame(2).copy(carried = 80), engine.state)
        assertEquals(0, engine.state.score)
        assertEquals(80, engine.state.attemptScore)
    }

    @Test fun nextLevelIsIgnoredOutsideWinAndAfterTheFifthLevel() {
        val ready = GameEngine()
        ready.nextLevel()
        assertEquals(GameEngine.newGame(), ready.state)
        val lost = GameEngine(GameEngine.newGame().copy(status = GameStatus.LOST, score = 20))
        lost.nextLevel()
        assertEquals(1, lost.state.level)
        val won5 = GameEngine.newGame(5).copy(status = GameStatus.WON, bricks = emptyList(), score = 240)
        val engine = GameEngine(won5)
        engine.nextLevel()
        assertEquals(won5, engine.state)
        assertEquals(5, engine.state.level)
    }

    @Test fun everyWinMovesOnWithCarriedBaseScoreAndLives() {
        val scores = listOf(80, 120, 160, 200)
        var carried = 0
        for (level in 1..4) {
            val engine = GameEngine(
                GameEngine.newGame(level).copy(
                    status = GameStatus.WON, bricks = emptyList(), carried = carried, score = scores[level - 1], lives = 2,
                ),
            )
            engine.nextLevel()
            carried += scores[level - 1]
            assertEquals(GameEngine.newGame(level + 1).copy(carried = carried, lives = 2), engine.state)
            assertEquals(0, engine.state.score)
        }
        assertEquals(560, carried)
    }

    @Test fun ballSpeedFactorGrowsTenPercentPerLevel() {
        assertEquals(listOf(1f, 1.1f, 1.2f, 1.3f, 1.4f), (1..5).map { Levels.speedFactor(it) })
        assertEquals(Point(130f, -270f), GameEngine.newGame(1).velocity)
        // Expected launch velocities worked out by hand: (130, -270) * 1.1 / 1.2 / 1.3 / 1.4.
        val expected = listOf(Triple(2, 143f, -297f), Triple(3, 156f, -324f), Triple(4, 169f, -351f), Triple(5, 182f, -378f))
        for ((level, vx, vy) in expected) {
            val v = GameEngine.newGame(level).velocity
            assertEquals("vx level $level", vx, v.x, 0.001f)
            assertEquals("vy level $level", vy, v.y, 0.001f)
        }
    }

    @Test fun advancingLevelsReplacesSpeedInsteadOfAccumulatingAndExitResetsIt() {
        var state = GameEngine.newGame()
        for (level in 2..5) {
            val engine = GameEngine(state.copy(status = GameStatus.WON, bricks = emptyList(), score = 10))
            engine.nextLevel()
            state = engine.state
            assertEquals(level, state.level)
            assertEquals(Levels.startVelocity(level), state.velocity)
        }
        assertEquals(-378f, state.velocity.y, 0.001f)
        val engine = GameEngine(state)
        engine.exitToFirstLevel()
        assertEquals(Point(130f, -270f), engine.state.velocity)
        // Restoring a saved state does not rescale it: the velocity is simply what was stored.
        val restored = GameEngine(state.copy(status = GameStatus.RUNNING))
        assertEquals(state.velocity, restored.state.velocity)
    }
}
