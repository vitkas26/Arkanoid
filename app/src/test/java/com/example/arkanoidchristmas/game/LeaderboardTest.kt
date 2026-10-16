package com.example.arkanoidchristmas.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardTest {
    private fun e(name: String, score: Int, level: Int = 1) = LeaderboardEntry(name, score, level)

    @Test fun sortsBestFirstAndKeepsOnlyTopTen() {
        var list = emptyList<LeaderboardEntry>()
        for (i in 1..12) list = Leaderboard.add(list, e("P$i", i * 10))
        assertEquals(10, list.size)
        assertEquals("P12", list.first().name)
        assertEquals(30, list.last().score)
        assertEquals(list.sortedByDescending { it.score }, list)
    }

    @Test fun sameNameKeepsOnlyBestResultIgnoringCase() {
        var list = Leaderboard.add(emptyList(), e("Аня", 100, 2))
        list = Leaderboard.add(list, e("аня", 40, 1))
        assertEquals(listOf(e("Аня", 100, 2)), list)
        list = Leaderboard.add(list, e("АНЯ", 200, 2))
        assertEquals(1, list.size)
        assertEquals(200, list.single().score)
    }

    @Test fun equalScoresPreferHigherLevel() {
        val list = Leaderboard.add(Leaderboard.add(emptyList(), e("A", 80, 1)), e("B", 80, 2))
        assertEquals(listOf("B", "A"), list.map { it.name })
    }

    @Test fun weakResultDoesNotEnterFullBoard() {
        var list = emptyList<LeaderboardEntry>()
        for (i in 1..10) list = Leaderboard.add(list, e("P$i", 100 + i))
        val after = Leaderboard.add(list, e("Слабый", 5))
        assertEquals(list, after)
        assertFalse(after.any { it.name == "Слабый" })
    }

    @Test fun nameIsTrimmedLimitedAndDefaultsToPlayer() {
        assertEquals("Игрок", Leaderboard.normalizeName(null))
        assertEquals("Игрок", Leaderboard.normalizeName("   \n\t "))
        assertEquals("Вика", Leaderboard.normalizeName("  Вика  "))
        assertEquals(16, Leaderboard.normalizeName("x".repeat(40)).length)
        assertEquals("a b", Leaderboard.normalizeName("a\tb"))
        assertEquals("Игрок", Leaderboard.add(emptyList(), e("", 10)).single().name)
    }

    @Test fun encodeDecodeRoundTripsAndSurvivesGarbage() {
        val list = Leaderboard.add(Leaderboard.add(emptyList(), e("Аня", 200, 2)), e("Боб", 90))
        assertEquals(list, Leaderboard.decode(Leaderboard.encode(list)))
        assertTrue(Leaderboard.decode(null).isEmpty())
        assertEquals(listOf(e("Ок", 5, 1)), Leaderboard.decode("мусор\nabc\t1\tx\n5\t1\tОк\n-3\t1\tМинус"))
    }

    @Test fun attemptScoreCarriesThroughLevelsAndOnlyLevelFiveEndsIt() {
        val won1 = GameEngine.newGame().copy(status = GameStatus.WON, bricks = emptyList(), score = 80)
        assertFalse(won1.attemptOver)
        val engine = GameEngine(won1)
        assertFalse(engine.markSaved())
        engine.nextLevel()
        assertEquals(80, engine.state.carried)
        assertEquals(0, engine.state.score)

        // Level 2 is intermediate now: no end of attempt, no save, no bonus.
        val won2 = engine.state.copy(status = GameStatus.WON, bricks = emptyList(), score = 120)
        assertEquals(200, won2.attemptScore)
        assertFalse(won2.attemptOver)
        assertEquals(0, won2.lifeBonus)
        val second = GameEngine(won2)
        assertFalse(second.markSaved())
        second.nextLevel()
        assertEquals(3, second.state.level)
        assertEquals(200, second.state.carried)

        // Level 3 is intermediate as well; the carried base score is 80 + 120 + 160.
        val won3 = second.state.copy(status = GameStatus.WON, bricks = emptyList(), score = 160)
        assertEquals(360, won3.attemptScore)
        assertFalse(won3.attemptOver)
        assertEquals(0, won3.lifeBonus)
        val third = GameEngine(won3)
        assertFalse(third.markSaved())
        third.nextLevel()
        assertEquals(4, third.state.level)
        assertEquals(360, third.state.carried)

        // Level 4 is intermediate too.
        val won4 = third.state.copy(status = GameStatus.WON, bricks = emptyList(), score = 200)
        assertEquals(560, won4.attemptScore)
        assertFalse(won4.attemptOver)
        assertEquals(0, won4.lifeBonus)
        val fourth = GameEngine(won4)
        assertFalse(fourth.markSaved())
        fourth.nextLevel()
        assertEquals(5, fourth.state.level)
        assertEquals(560, fourth.state.carried)

        val won5 = fourth.state.copy(status = GameStatus.WON, bricks = emptyList(), score = 240)
        assertEquals(800, won5.attemptScore)
        assertTrue(won5.attemptOver)
        assertEquals(60, won5.lifeBonus)
        assertEquals(860, won5.finalScore)
    }

    @Test fun lossEndsAttemptWithCarriedPlusCurrentAndSavesOnce() {
        val lost = GameEngine.newGame(2).copy(status = GameStatus.LOST, carried = 80, score = 30)
        val engine = GameEngine(lost)
        assertEquals(110, lost.attemptScore)
        assertTrue(lost.attemptOver)
        assertTrue(engine.markSaved())
        assertFalse(engine.markSaved())
        assertTrue(engine.state.saved)
    }

    @Test fun restartStartsNewAttemptWithZeroCarriedAndNotSaved() {
        val engine = GameEngine(
            GameEngine.newGame(2).copy(status = GameStatus.LOST, carried = 80, score = 30, saved = true),
        )
        engine.restart()
        assertEquals(GameEngine.newGame(2), engine.state)
        assertEquals(0, engine.state.carried)
        assertFalse(engine.state.saved)
    }
}
