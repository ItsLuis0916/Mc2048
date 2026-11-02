package dev.luis.mc2048;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTest {

    private static Game game(int... cells) {
        Game game = new Game(4, 2048, 0, 2, new Random(1));
        game.load(cells, 0, 0, false, false, null, 0, 0);
        return game;
    }

    private static int[] withoutSpawn(Game game, Game.Turn turn) {
        int[] cells = game.cells();
        cells[turn.spawn().at()] = 0;
        return cells;
    }

    private static int[] row(int[] cells, int row) {
        return new int[] {cells[row * 4], cells[row * 4 + 1], cells[row * 4 + 2], cells[row * 4 + 3]};
    }

    @Test
    void mergesEachTileOnlyOnce() {
        Game game = game(
                2, 2, 2, 2,
                2, 2, 4, 0,
                4, 0, 4, 8,
                0, 0, 0, 0);
        Game.Turn turn = game.move(Game.Direction.LEFT);
        int[] cells = withoutSpawn(game, turn);
        assertArrayEquals(new int[] {4, 4, 0, 0}, row(cells, 0));
        assertArrayEquals(new int[] {4, 4, 0, 0}, row(cells, 1));
        assertArrayEquals(new int[] {8, 8, 0, 0}, row(cells, 2));
        assertEquals(20, turn.gained());
        assertEquals(20, game.score());
        assertEquals(4, turn.merges().size());
    }

    @Test
    void movesTowardsTheFarSide() {
        Game game = game(
                2, 0, 0, 0,
                2, 0, 0, 0,
                4, 0, 0, 0,
                4, 0, 0, 0);
        int[] cells = withoutSpawn(game, game.move(Game.Direction.DOWN));
        assertEquals(4, cells[8]);
        assertEquals(8, cells[12]);
    }

    @Test
    void blockedMoveChangesNothing() {
        Game game = game(
                2, 4, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
        assertNull(game.move(Game.Direction.LEFT));
        assertFalse(game.canUndo());
    }

    @Test
    void undoNeedsTwoMovesBeforeItWorksAgain() {
        Game game = game(
                2, 2, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
        int[] start = game.cells();
        game.move(Game.Direction.LEFT);
        assertTrue(game.undo());
        assertArrayEquals(start, game.cells());
        assertEquals(0, game.score());
        game.move(Game.Direction.LEFT);
        assertFalse(game.canUndo());
        game.move(Game.Direction.RIGHT);
        assertTrue(game.canUndo());
    }

    @Test
    void reachingTheWinValueStopsUntilKeepPlaying() {
        Game game = game(
                1024, 1024, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
        game.move(Game.Direction.LEFT);
        assertTrue(game.showWin());
        assertNull(game.move(Game.Direction.RIGHT));
        game.keepPlaying();
        assertFalse(game.showWin());
    }

    @Test
    void fullBoardWithoutPairsIsOver() {
        Game game = game(
                2, 4, 2, 4,
                4, 2, 4, 2,
                2, 4, 2, 4,
                4, 2, 4, 2);
        assertTrue(game.over());
    }
}
