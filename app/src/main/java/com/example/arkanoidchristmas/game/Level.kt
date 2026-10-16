package com.example.arkanoidchristmas.game

/** Static description of one level; levels 1..[Levels.IMPLEMENTED] of [Levels.TOTAL] exist so far. */
data class LevelDefinition(val number: Int, val bricks: List<Brick>)

object Levels {
    const val TOTAL = 5
    const val IMPLEMENTED = 5

    /**
     * Builds a symmetric figure from [pattern] rows ('#' = brick, '.' = gap). Every row is centred
     * on the field, so a palindromic row is mirror-balanced, and rows of different parity get the
     * half-brick offset a figure needs (diamond, tree, heart).
     */
    private fun figure(
        pattern: List<String>,
        width: Float,
        height: Float,
        gapX: Float,
        gapY: Float,
        top: Float = 80f,
    ): List<Brick> = pattern.flatMapIndexed { row, cells ->
        val rowWidth = cells.length * width + (cells.length - 1) * gapX
        val start = (GameWorld.WIDTH - rowWidth) / 2f
        cells.mapIndexedNotNull { column, cell ->
            if (cell != '#') null else Brick(
                left = start + column * (width + gapX),
                top = top + row * (height + gapY),
                width = width,
                height = height,
            )
        }
    }

    /** "Первый снег": a Christmas tree with a two-brick trunk (8 bricks). */
    val first = LevelDefinition(
        number = 1,
        bricks = figure(listOf("#", "##", "###", "#", "#"), width = 64f, height = 40f, gapX = 12f, gapY = 10f),
    )

    /** "Ледяная крепость": a castle with battlements and a gate (12 bricks). */
    val second = LevelDefinition(
        number = 2,
        bricks = figure(listOf("#.#.#", "#####", "##.##"), width = 64f, height = 40f, gapX = 10f, gapY = 12f),
    )

    /** "Снежный бастион": a diamond of 1-2-3-4-3-2-1 bricks (16 bricks). */
    val third = LevelDefinition(
        number = 3,
        bricks = figure(
            listOf("#", "##", "###", "####", "###", "##", "#"),
            width = 64f, height = 30f, gapX = 10f, gapY = 8f,
        ),
    )

    /** "Морозная цитадель": a cross (20 bricks). */
    val fourth = LevelDefinition(
        number = 4,
        bricks = figure(
            listOf("..##..", "..##..", "######", "######", "..##..", "..##.."),
            width = 52f, height = 36f, gapX = 8f, gapY = 8f,
        ),
    )

    /** "Рождественское сердце": a heart (24 bricks). */
    val fifth = LevelDefinition(
        number = 5,
        bricks = figure(
            listOf(".##..##.", "########", ".######.", "..####..", "...##..."),
            width = 40f, height = 36f, gapX = 6f, gapY = 8f,
        ),
    )

    /** Ball speed multiplier of a level: 1.0 / 1.1 / 1.2 / 1.3 / 1.4 (the paddle speed is not scaled). */
    fun speedFactor(level: Int): Float = when (byNumber(level).number) {
        2 -> 1.1f
        3 -> 1.2f
        4 -> 1.3f
        5 -> 1.4f
        else -> 1f
    }

    /** Launch velocity of a level: the base (130, -270) times its [speedFactor]. */
    fun startVelocity(level: Int): Point {
        val factor = speedFactor(level)
        return Point(GameWorld.START_VELOCITY.x * factor, GameWorld.START_VELOCITY.y * factor)
    }

    fun hasNext(number: Int): Boolean = number < IMPLEMENTED

    /** Unknown numbers fall back to level 1. */
    fun byNumber(number: Int): LevelDefinition = when (number) {
        second.number -> second
        third.number -> third
        fourth.number -> fourth
        fifth.number -> fifth
        else -> first
    }
}
