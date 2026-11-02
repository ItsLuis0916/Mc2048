package dev.luis.mc2048;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

public final class Game {

    public enum Direction { UP, DOWN, LEFT, RIGHT }

    public record Slide(int id, int to) {}

    public record Merge(int id, int value, int at, int first, int second) {}

    public record Spawn(int id, int value, int at) {}

    public record Turn(List<Slide> slides, List<Merge> merges, Spawn spawn, int gained) {}

    private final int size;
    private final int winValue;
    private final double fourChance;
    private final int undoCooldown;
    private final RandomGenerator random;
    private final int[] values;
    private final int[] ids;
    private int nextId = 1;
    private int score;
    private int best;
    private boolean won;
    private boolean keepPlaying;
    private int[] undoValues;
    private int undoScore;
    private int undoLock;

    public Game(int size, int winValue, double fourChance, int undoCooldown, RandomGenerator random) {
        this.size = size;
        this.winValue = winValue;
        this.fourChance = fourChance;
        this.undoCooldown = undoCooldown;
        this.random = random;
        this.values = new int[size * size];
        this.ids = new int[size * size];
    }

    public void restart(int startTiles) {
        Arrays.fill(values, 0);
        score = 0;
        won = false;
        keepPlaying = false;
        undoValues = null;
        undoLock = 0;
        assignIds();
        for (int i = 0; i < startTiles; i++) spawn();
    }

    public Turn move(Direction direction) {
        if (!playable()) return null;
        int[] before = values.clone();
        int scoreBefore = score;
        List<Slide> slides = new ArrayList<>();
        List<Merge> merges = new ArrayList<>();
        int gained = 0;
        for (int line = 0; line < size; line++) {
            int filled = 0;
            int lastMerged = -1;
            for (int step = 0; step < size; step++) {
                int from = cell(direction, line, step);
                if (values[from] == 0) continue;
                int value = values[from];
                int id = ids[from];
                values[from] = 0;
                ids[from] = 0;
                if (filled > 0) {
                    int previous = cell(direction, line, filled - 1);
                    if (previous != lastMerged && values[previous] == value) {
                        int merged = nextId++;
                        slides.add(new Slide(id, previous));
                        merges.add(new Merge(merged, value * 2, previous, ids[previous], id));
                        values[previous] = value * 2;
                        ids[previous] = merged;
                        gained += value * 2;
                        lastMerged = previous;
                        if (value * 2 >= winValue) won = true;
                        continue;
                    }
                }
                int to = cell(direction, line, filled++);
                values[to] = value;
                ids[to] = id;
                if (to != from) slides.add(new Slide(id, to));
            }
        }
        if (slides.isEmpty()) return null;
        score += gained;
        best = Math.max(best, score);
        undoValues = before;
        undoScore = scoreBefore;
        if (undoLock > 0) undoLock--;
        return new Turn(slides, merges, spawn(), gained);
    }

    public boolean undo() {
        if (!canUndo()) return false;
        System.arraycopy(undoValues, 0, values, 0, values.length);
        score = undoScore;
        undoValues = null;
        undoLock = undoCooldown;
        won = won && maxValue() >= winValue;
        assignIds();
        return true;
    }

    public boolean canUndo() {
        return undoValues != null && undoLock == 0;
    }

    public void keepPlaying() {
        keepPlaying = true;
    }

    public boolean showWin() {
        return won && !keepPlaying;
    }

    public boolean over() {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) return false;
            if (i % size < size - 1 && values[i] == values[i + 1]) return false;
            if (i + size < values.length && values[i] == values[i + size]) return false;
        }
        return true;
    }

    public void load(int[] cells, int score, int best, boolean won, boolean keepPlaying, int[] undoCells, int undoScore, int undoLock) {
        System.arraycopy(cells, 0, values, 0, values.length);
        this.score = score;
        this.best = Math.max(best, score);
        this.won = won;
        this.keepPlaying = keepPlaying;
        this.undoValues = undoCells == null ? null : undoCells.clone();
        this.undoScore = undoScore;
        this.undoLock = undoLock;
        assignIds();
    }

    public int size() { return size; }
    public int value(int cell) { return values[cell]; }
    public int id(int cell) { return ids[cell]; }
    public int[] cells() { return values.clone(); }
    public int score() { return score; }
    public int best() { return best; }
    public boolean won() { return won; }
    public boolean keptPlaying() { return keepPlaying; }
    public int[] undoCells() { return undoValues == null ? null : undoValues.clone(); }
    public int undoScore() { return undoScore; }
    public int undoLock() { return undoLock; }

    private boolean playable() {
        return !showWin() && !over();
    }

    private Spawn spawn() {
        int free = 0;
        for (int value : values) if (value == 0) free++;
        if (free == 0) return null;
        int pick = random.nextInt(free);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0 || pick-- > 0) continue;
            values[i] = random.nextDouble() < fourChance ? 4 : 2;
            ids[i] = nextId++;
            return new Spawn(ids[i], values[i], i);
        }
        return null;
    }

    private void assignIds() {
        for (int i = 0; i < values.length; i++) ids[i] = values[i] == 0 ? 0 : nextId++;
    }

    private int maxValue() {
        int max = 0;
        for (int value : values) max = Math.max(max, value);
        return max;
    }

    private int cell(Direction direction, int line, int step) {
        return switch (direction) {
            case LEFT -> line * size + step;
            case RIGHT -> line * size + size - 1 - step;
            case UP -> step * size + line;
            case DOWN -> (size - 1 - step) * size + line;
        };
    }
}
