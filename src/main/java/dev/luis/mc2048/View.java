package dev.luis.mc2048;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class View {

    enum Action { UNDO, RESTART, KEEP_GOING, LEAVE, ZOOM_IN, ZOOM_OUT }

    enum Overlay { NONE, WON, OVER }

    private static final float PIXEL = 0.025f;
    private static final String BLOCK = " ";
    private static final float BLOCK_WIDTH = 5;
    private static final float BLOCK_HEIGHT = 10;
    private static final float BLOCK_SHIFT_X = 0.5f;
    private static final float GLYPH_SHIFT_X = 0.5f;
    private static final float GLYPH_CENTER_Y = 5.5f;
    private static final float CAP_HEIGHT = 7;
    private static final float HIDDEN = 0.001f;
    private static final byte OPAQUE = (byte) -1;
    private static final byte FADED = 4;
    private static final int LINE_WIDTH = 1000;
    private static final Color CLEAR = Color.fromARGB(0);
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private static final int BACKDROP = -1;
    private static final int PAGE = 0;
    private static final int PANEL = 1;
    private static final int CELL = 2;
    private static final int GLOW = 3;
    private static final int TILE = 4;
    private static final int TILE_TEXT = 5;
    private static final int FLOAT = 6;
    private static final int OVERLAY = 7;
    private static final int OVERLAY_BUTTON = 8;
    private static final int CURSOR = 10;

    private record Pending(long due, Runnable action) {}

    private final class Part {
        private final List<TextDisplay> displays = new ArrayList<>();
        private Supplier<Transformation> layout;

        private void animate(int ticks, Consumer<TextDisplay> change) {
            for (TextDisplay display : displays) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(ticks);
                change.accept(display);
            }
        }

        private void set(Consumer<TextDisplay> change) {
            displays.forEach(change);
        }

        private void remove() {
            parts.remove(this);
            displays.forEach(display -> kill(plugin, display));
        }
    }

    private static final class Tile {
        private final Part background;
        private final Part number;
        private Part glow;
        private int value;

        private Tile(Part background, Part number) {
            this.background = background;
            this.number = number;
        }
    }

    private final class Button {
        private final Settings.Area area;
        private final Action action;
        private final Part background;
        private final Part label;
        private boolean enabled = true;
        private boolean hovered;

        private Button(Settings.Area area, Action action, String text, double textHeight, int layer) {
            this.area = area;
            this.action = action;
            this.background = rect(area, layer, settings.buttonColor);
            this.label = label(area.centerX(), area.centerY(), textHeight, layer + 1, settings.text(text));
        }

        private void paint() {
            Color color = !enabled ? settings.buttonDisabled : hovered ? settings.buttonHover : settings.buttonColor;
            background.set(d -> d.setBackgroundColor(color));
        }
    }

    private final Plugin plugin;
    private final Player player;
    private final Settings settings;
    private final List<Location> faces;
    private final List<Part> parts = new ArrayList<>();
    private final Map<Integer, Tile> tiles = new HashMap<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> overlayButtons = new ArrayList<>();
    private final List<Part> overlayParts = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private Button undo;
    private Part scoreValue;
    private Part bestValue;
    private Part cursor;
    private Part cursorInner;
    private Overlay overlay = Overlay.NONE;
    private boolean overlayUndo;
    private long now;
    private double zoom;
    private double pointerX;
    private double pointerY;

    View(Plugin plugin, Player player, Settings settings, List<Location> faces, double zoom) {
        this.zoom = zoom;
        this.plugin = plugin;
        this.player = player;
        this.settings = settings;
        this.faces = faces;
    }

    void build(Game game) {
        double backdrop = settings.backdropSize;
        rect(new Settings.Area((settings.pageWidth - backdrop) / 2, (settings.pageHeight - backdrop) / 2, backdrop, backdrop), BACKDROP, settings.backdropColor);
        rect(new Settings.Area(0, 0, settings.pageWidth, settings.pageHeight), PAGE, settings.pageColor);
        label(settings.pageWidth / 2, settings.hintY, settings.hintTextHeight, PAGE, settings.text("board.hint"));
        label(settings.title.centerX(), settings.title.centerY(), settings.titleHeight, PANEL, settings.text("board.title"));
        scoreValue = scoreBox(settings.score, "board.score");
        bestValue = scoreBox(settings.best, "board.best");
        undo = new Button(settings.undo, Action.UNDO, "board.undo", settings.undoTextHeight, PANEL);
        buttons.add(undo);
        buttons.add(new Button(settings.restart, Action.RESTART, "board.restart", settings.restartTextHeight, PANEL));
        buttons.add(new Button(settings.leave, Action.LEAVE, "board.leave", settings.leaveTextHeight, PANEL));
        buttons.add(new Button(settings.zoomOut, Action.ZOOM_OUT, "board.zoom-out", settings.zoomTextHeight, PANEL));
        buttons.add(new Button(settings.zoomIn, Action.ZOOM_IN, "board.zoom-in", settings.zoomTextHeight, PANEL));
        rect(settings.board, PANEL, settings.boardColor);
        double cell = settings.cellSize();
        for (int i = 0; i < settings.size * settings.size; i++) {
            rect(new Settings.Area(cellX(i) - cell / 2, cellY(i) - cell / 2, cell, cell), CELL, settings.cellColor);
        }
        double size = settings.cursorSize;
        double inner = size - settings.cursorBorder * 2;
        cursor = rect(new Settings.Area(0, 0, size, size), CURSOR, settings.cursorBorderColor);
        cursorInner = rect(new Settings.Area(0, 0, inner, inner), CURSOR + 1, settings.cursorColor);
        sync(game, false);
    }

    void tick() {
        now++;
        List<Pending> due = pending.stream().filter(p -> p.due() <= now).sorted(Comparator.comparingLong(Pending::due)).toList();
        pending.removeAll(due);
        due.forEach(p -> p.action().run());
    }

    void sync(Game game, boolean animate) {
        flush();
        tiles.values().forEach(this::removeTile);
        tiles.clear();
        for (int cell = 0; cell < settings.size * settings.size; cell++) {
            int value = game.value(cell);
            if (value == 0) continue;
            if (animate) appear(game.id(cell), value, cell);
            else tiles.put(game.id(cell), tile(value, cell, 1));
        }
    }

    void render(Game.Turn turn) {
        flush();
        for (Game.Slide slide : turn.slides()) {
            Tile tile = tiles.get(slide.id());
            if (tile != null) place(tile, slide.to(), 1, settings.slideTicks);
        }
        later(settings.slideTicks, () -> {
            for (Game.Merge merge : turn.merges()) {
                Tile second = tiles.remove(merge.second());
                if (second != null) removeTile(second);
                Tile first = tiles.remove(merge.first());
                if (first == null) continue;
                tiles.put(merge.id(), first);
                paint(first, merge.value());
                place(first, merge.at(), settings.popScale, settings.popTicks);
                later(settings.popTicks, () -> place(first, merge.at(), 1, settings.popTicks));
            }
            if (turn.spawn() != null) appear(turn.spawn().id(), turn.spawn().value(), turn.spawn().at());
        });
    }

    void score(int score, int best, int gained) {
        scoreValue.set(d -> d.text(settings.text("board.value", "%value%", score)));
        bestValue.set(d -> d.text(settings.text("board.value", "%value%", best)));
        if (gained <= 0) return;
        Settings.Area area = settings.score.area();
        double x = area.centerX();
        double y = area.y() + settings.score.valueY();
        Part floating = label(x, y, settings.floatTextHeight, FLOAT, settings.text("board.float", "%value%", gained));
        later(1, () -> floating.animate(settings.floatTicks, d -> {
            d.setTransformation(labelTransform(x, y - settings.floatDistance, settings.floatTextHeight, FLOAT, 1));
            d.setTextOpacity(FADED);
        }));
        later(settings.floatTicks + 1, floating::remove);
    }

    void undoEnabled(boolean enabled) {
        undo.enabled = enabled;
        undo.paint();
    }

    void overlay(Overlay next, boolean canUndo, int delay) {
        if (next == overlay && canUndo == overlayUndo) return;
        overlay = next;
        overlayUndo = canUndo;
        overlayParts.forEach(Part::remove);
        overlayParts.clear();
        overlayButtons.clear();
        if (next == Overlay.NONE) return;
        later(delay, () -> showOverlay(next, canUndo));
    }

    void zoom(double next) {
        zoom = next;
        for (Part part : parts) {
            if (part.layout == null) continue;
            Transformation transformation = part.layout.get();
            part.animate(settings.zoomTicks, d -> d.setTransformation(transformation));
        }
        movePointer(pointerX, pointerY, settings.zoomTicks);
    }

    void pointer(double x, double y) {
        movePointer(x, y, settings.cursorTicks);
    }

    private void movePointer(double x, double y, int ticks) {
        pointerX = x;
        pointerY = y;
        double size = settings.cursorSize;
        double inner = size - settings.cursorBorder * 2;
        cursor.animate(ticks, d -> d.setTransformation(rectTransform(x, y, size, size, CURSOR, 1)));
        cursorInner.animate(ticks, d -> d.setTransformation(rectTransform(x, y, inner, inner, CURSOR + 1, 1)));
        for (Button button : allButtons()) {
            boolean hovered = button.enabled && button.area.contains(x, y);
            if (hovered == button.hovered) continue;
            button.hovered = hovered;
            button.paint();
        }
    }

    Action click() {
        for (Button button : allButtons()) if (button.hovered) return button.action;
        return null;
    }

    void remove() {
        pending.clear();
        new ArrayList<>(parts).forEach(Part::remove);
        tiles.clear();
    }

    static void kill(Plugin plugin, Entity entity) {
        if (entity.isDead()) return;
        if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
        else entity.getScheduler().run(plugin, task -> entity.remove(), null);
    }

    private void showOverlay(Overlay next, boolean canUndo) {
        Settings.Area board = settings.board;
        Color color = over(next == Overlay.WON ? settings.overlayWon : settings.overlayOver, settings.boardColor);
        Part background = rect(board, OVERLAY, color);
        overlayParts.add(background);
        Part text = label(board.centerX(), board.y() + settings.overlayTextY, settings.overlayTextHeight, OVERLAY_BUTTON + 1,
                settings.text(next == Overlay.WON ? "board.won" : "board.over"));
        text.set(d -> d.setTextOpacity(FADED));
        overlayParts.add(text);
        List<Action> actions = new ArrayList<>();
        if (next == Overlay.WON) actions.add(Action.KEEP_GOING);
        if (next == Overlay.OVER && canUndo) actions.add(Action.UNDO);
        actions.add(Action.RESTART);
        double width = settings.overlayButtonWidth;
        double total = actions.size() * width + (actions.size() - 1) * settings.overlayButtonGap;
        double x = board.centerX() - total / 2;
        for (Action action : actions) {
            String key = switch (action) {
                case KEEP_GOING -> "board.keep-going";
                case UNDO -> "board.undo";
                case RESTART -> "board.try-again";
                case LEAVE -> "board.leave";
                case ZOOM_IN -> "board.zoom-in";
                case ZOOM_OUT -> "board.zoom-out";
            };
            Settings.Area area = new Settings.Area(x, board.y() + settings.overlayButtonY, width, settings.overlayButtonHeight);
            Button button = new Button(area, action, key, settings.overlayButtonTextHeight, OVERLAY_BUTTON);
            overlayButtons.add(button);
            overlayParts.add(button.background);
            overlayParts.add(button.label);
            x += width + settings.overlayButtonGap;
        }
        later(1, () -> text.animate(settings.overlayTicks, d -> d.setTextOpacity(OPAQUE)));
    }

    private List<Button> allButtons() {
        List<Button> all = new ArrayList<>(buttons);
        all.addAll(overlayButtons);
        return all;
    }

    private Part scoreBox(Settings.ScoreBox box, String labelKey) {
        Settings.Area area = box.area();
        rect(area, PANEL, box.color());
        label(area.centerX(), area.y() + box.labelY(), box.labelHeight(), CELL, settings.text(labelKey));
        return label(area.centerX(), area.y() + box.valueY(), box.valueHeight(), CELL, Component.empty());
    }

    private void appear(int id, int value, int cell) {
        Tile tile = tile(value, cell, HIDDEN);
        tiles.put(id, tile);
        later(1, () -> place(tile, cell, 1, settings.appearTicks));
    }

    private Tile tile(int value, int cell, float scale) {
        Settings.TileColors colors = settings.tileColors(value);
        double size = settings.cellSize();
        double height = settings.tileTextHeight(value);
        Part glow = value < settings.glowFrom ? null : glow(value, cell, scale);
        Part background = spawn(d -> {
            d.text(Component.text(BLOCK));
            d.setBackgroundColor(colors.background());
            d.setTransformation(rectTransform(cellX(cell), cellY(cell), size, size, TILE, scale));
        }, () -> rectTransform(cellX(cell), cellY(cell), size, size, TILE, 1));
        Part number = spawn(d -> {
            d.text(tileText(value, colors));
            d.setTransformation(labelTransform(cellX(cell), cellY(cell), height, TILE_TEXT, scale));
        }, () -> labelTransform(cellX(cell), cellY(cell), height, TILE_TEXT, 1));
        Tile tile = new Tile(background, number);
        tile.glow = glow;
        tile.value = value;
        return tile;
    }

    private void paint(Tile tile, int value) {
        tile.value = value;
        Settings.TileColors colors = settings.tileColors(value);
        Component text = tileText(value, colors);
        Color glow = glowColor(value);
        if (tile.glow != null) tile.glow.set(d -> d.setBackgroundColor(glow));
        tile.background.set(d -> d.setBackgroundColor(colors.background()));
        tile.number.set(d -> d.text(text));
    }

    private Part glow(int value, int cell, float scale) {
        double halo = settings.cellSize() + settings.glowMargin * 2;
        Color color = glowColor(value);
        return spawn(d -> {
            d.text(Component.text(BLOCK));
            d.setBackgroundColor(color);
            d.setTransformation(rectTransform(cellX(cell), cellY(cell), halo, halo, GLOW, scale));
        }, () -> rectTransform(cellX(cell), cellY(cell), halo, halo, GLOW, 1));
    }

    private Color glowColor(int value) {
        if (value < settings.glowFrom) return CLEAR;
        double steps = Math.log(settings.winValue / (double) settings.glowFrom) / Math.log(2);
        double step = Math.min(1, Math.log(value / (double) settings.glowFrom) / Math.log(2) / Math.max(1, steps));
        double share = settings.glowMinAlpha + (settings.glowMaxAlpha - settings.glowMinAlpha) * step;
        int alpha = (int) Math.round(255 * share);
        return over(Color.fromARGB(alpha, settings.glowColor.getRed(), settings.glowColor.getGreen(), settings.glowColor.getBlue()), settings.boardColor);
    }

    private static Color over(Color top, Color bottom) {
        double alpha = top.getAlpha() / 255.0;
        return Color.fromRGB(
                (int) Math.round(top.getRed() * alpha + bottom.getRed() * (1 - alpha)),
                (int) Math.round(top.getGreen() * alpha + bottom.getGreen() * (1 - alpha)),
                (int) Math.round(top.getBlue() * alpha + bottom.getBlue() * (1 - alpha)));
    }

    private Component tileText(int value, Settings.TileColors colors) {
        return settings.text("board.tile", "%value%", value).colorIfAbsent(TextColor.color(colors.text().asRGB()));
    }

    private void place(Tile tile, int cell, float scale, int ticks) {
        double size = settings.cellSize();
        double height = settings.tileTextHeight(tile.value);
        double halo = size + settings.glowMargin * 2;
        if (tile.glow == null && tile.value >= settings.glowFrom) tile.glow = glow(tile.value, cell, 1);
        if (tile.glow != null) {
            tile.glow.animate(ticks, d -> d.setTransformation(rectTransform(cellX(cell), cellY(cell), halo, halo, GLOW, scale)));
            tile.glow.layout = () -> rectTransform(cellX(cell), cellY(cell), halo, halo, GLOW, 1);
        }
        tile.background.animate(ticks, d -> d.setTransformation(rectTransform(cellX(cell), cellY(cell), size, size, TILE, scale)));
        tile.number.animate(ticks, d -> d.setTransformation(labelTransform(cellX(cell), cellY(cell), height, TILE_TEXT, scale)));
        tile.background.layout = () -> rectTransform(cellX(cell), cellY(cell), size, size, TILE, 1);
        tile.number.layout = () -> labelTransform(cellX(cell), cellY(cell), height, TILE_TEXT, 1);
    }

    private void removeTile(Tile tile) {
        if (tile.glow != null) tile.glow.remove();
        tile.background.remove();
        tile.number.remove();
    }

    private void flush() {
        while (!pending.isEmpty()) {
            List<Pending> run = new ArrayList<>(pending);
            pending.clear();
            run.sort(Comparator.comparingLong(Pending::due));
            run.forEach(p -> p.action().run());
        }
    }

    private void later(long ticks, Runnable action) {
        pending.add(new Pending(now + ticks, action));
    }

    private double cellX(int cell) {
        double size = settings.cellSize();
        return settings.board.x() + settings.gap + (cell % settings.size) * (size + settings.gap) + size / 2;
    }

    private double cellY(int cell) {
        double size = settings.cellSize();
        return settings.board.y() + settings.gap + (cell / settings.size) * (size + settings.gap) + size / 2;
    }

    private Part rect(Settings.Area area, int layer, Color color) {
        return spawn(d -> {
            d.text(Component.text(BLOCK));
            d.setBackgroundColor(color);
        }, () -> rectTransform(area.centerX(), area.centerY(), area.width(), area.height(), layer, 1));
    }

    private Part label(double x, double y, double height, int layer, Component text) {
        return spawn(d -> d.text(text), () -> labelTransform(x, y, height, layer, 1));
    }

    private Transformation rectTransform(double x, double y, double width, double height, int layer, float scale) {
        float sx = (float) (width * unit() / (BLOCK_WIDTH * PIXEL)) * scale;
        float sy = (float) (height * unit() / (BLOCK_HEIGHT * PIXEL)) * scale;
        Vector3f translation = new Vector3f(localX(x) - BLOCK_SHIFT_X * PIXEL * sx, localY(y) - BLOCK_HEIGHT / 2 * PIXEL * sy, localZ(layer));
        return new Transformation(translation, new Quaternionf(), new Vector3f(sx, sy, 1), new Quaternionf());
    }

    private Transformation labelTransform(double x, double y, double height, int layer, float scale) {
        float size = (float) (height * unit() / (CAP_HEIGHT * PIXEL)) * scale;
        Vector3f translation = new Vector3f(localX(x) - GLYPH_SHIFT_X * PIXEL * size, localY(y) - GLYPH_CENTER_Y * PIXEL * size, localZ(layer));
        return new Transformation(translation, new Quaternionf(), new Vector3f(size, size, size), new Quaternionf());
    }

    private double unit() {
        return settings.unit * zoom;
    }

    private float localX(double x) {
        return (float) ((x - settings.pageWidth / 2) * unit());
    }

    private float localY(double y) {
        return (float) ((settings.pageHeight / 2 - y) * unit());
    }

    private float localZ(int layer) {
        return (float) (-settings.distance + layer * settings.layer);
    }

    private Part spawn(Consumer<TextDisplay> setup, Supplier<Transformation> layout) {
        Part part = new Part();
        part.layout = layout;
        Transformation start = layout.get();
        for (Location face : faces) {
            TextDisplay display = face.getWorld().spawn(face, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBrightness(FULL_BRIGHT);
                d.setShadowed(false);
                d.setLineWidth(LINE_WIDTH);
                d.setBackgroundColor(CLEAR);
                d.setTransformation(start);
                setup.accept(d);
            });
            player.showEntity(plugin, display);
            part.displays.add(display);
        }
        parts.add(part);
        return part;
    }
}
