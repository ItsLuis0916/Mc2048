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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class View {

    enum Action { UNDO, RESTART, KEEP_GOING, LEAVE, MENU, PICK, ZOOM_IN, ZOOM_OUT }

    record Click(Action action, int size) {}

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
    private static final int CONFETTI = 9;
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
        private final int size;
        private boolean enabled = true;
        private boolean hovered;

        private Button(Settings.Area area, Action action, String text, double textHeight, int layer) {
            this(area, action, texts.text(text), textHeight, area.height() / 2, layer, 0);
        }

        private Button(Settings.Area area, Action action, Component text, double textHeight, double textY, int layer, int size) {
            this.area = area;
            this.action = action;
            this.size = size;
            this.background = rect(area, layer, settings.buttonColor);
            this.label = label(area.centerX(), area.y() + textY, textHeight, layer + 1, text);
        }

        private void paint() {
            Color color = !enabled ? settings.buttonDisabled : hovered ? settings.buttonHover : settings.buttonColor;
            background.set(d -> d.setBackgroundColor(color));
        }
    }

    private final Plugin plugin;
    private final Player player;
    private final Settings settings;
    private final Settings.Texts texts;
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
    private int shownScore = -1;
    private int shownBest = -1;
    private final Map<Integer, Component> tileTexts = new HashMap<>();
    private int side;
    private double fit;
    private double zoom;
    private double pointerX;
    private double pointerY;

    View(Plugin plugin, Player player, Settings settings, Settings.Texts texts, List<Location> faces, double zoom) {
        this.texts = texts;
        this.zoom = zoom;
        this.plugin = plugin;
        this.player = player;
        this.settings = settings;
        this.faces = faces;
    }

    void build(Game game) {
        side = game.size();
        fit = settings.scale(side);
        page("board.hint");
        label(settings.title.centerX(), settings.title.centerY(), settings.titleHeight, PANEL, texts.text("board.title"));
        scoreValue = scoreBox(settings.score, "board.score");
        bestValue = scoreBox(settings.best, "board.best");
        undo = new Button(settings.undo, Action.UNDO, "board.undo", settings.undoTextHeight, PANEL);
        buttons.add(undo);
        buttons.add(new Button(settings.restart, Action.RESTART, "board.restart", settings.restartTextHeight, PANEL));
        boolean menu = settings.sizes.size() > 1;
        buttons.add(new Button(settings.leave, menu ? Action.MENU : Action.LEAVE, menu ? "board.menu" : "board.leave", settings.leaveTextHeight, PANEL));
        buttons.add(new Button(settings.zoomOut, Action.ZOOM_OUT, "board.zoom-out", settings.zoomTextHeight, PANEL));
        buttons.add(new Button(settings.zoomIn, Action.ZOOM_IN, "board.zoom-in", settings.zoomTextHeight, PANEL));
        rect(settings.board, PANEL, settings.boardColor);
        double cell = settings.cellSize(side);
        for (int i = 0; i < side * side; i++) {
            rect(new Settings.Area(cellX(i) - cell / 2, cellY(i) - cell / 2, cell, cell), CELL, settings.cellColor);
        }
        cursor();
        sync(game, false);
    }

    void buildMenu(List<Integer> sizes, Map<Integer, Store.Slot> slots) {
        Settings.Menu menu = settings.menu;
        page("menu.hint");
        label(settings.pageWidth / 2, menu.titleY(), menu.titleHeight(), PANEL, texts.text("board.title"));
        label(settings.pageWidth / 2, menu.subtitleY(), menu.subtitleHeight(), PANEL, texts.text("menu.subtitle"));
        double cell = menuCell(sizes);
        double zone = previewSpan(sizes.stream().mapToInt(Integer::intValue).max().orElse(0), cell);
        double height = menu.previewY() + zone + menu.bestGap() * 2;
        double x = (settings.pageWidth - sizes.stream().mapToDouble(size -> cardWidth(size, cell)).sum() - (sizes.size() - 1) * menu.cardGap()) / 2;
        for (int size : sizes) {
            Settings.Area area = new Settings.Area(x, menu.cardY(), cardWidth(size, cell), height);
            buttons.add(new Button(area, Action.PICK, texts.text("menu.size", "%size%", size), menu.nameHeight(), menu.nameY(), PANEL, size));
            Store.Slot slot = slots.get(size);
            miniBoard(area.x() + menu.pad(), area.y() + menu.previewY(), size, cell, slot == null ? new int[0] : slot.cells());
            int best = slot == null ? 0 : slot.best();
            label(area.centerX(), area.y() + menu.previewY() + zone + menu.bestGap(), menu.bestHeight(), PANEL + 1,
                    best == 0 ? texts.text("menu.empty") : texts.text("menu.best", "%value%", best));
            x += area.width() + menu.cardGap();
        }
        buttons.add(new Button(menu.back(), Action.LEAVE, "menu.back", menu.backHeight(), PANEL));
        cursor();
    }

    private double previewSpan(int size, double cell) {
        return size * cell + (size + 1) * settings.menu.cellGap();
    }

    private double cardWidth(int size, double cell) {
        return previewSpan(size, cell) + settings.menu.pad() * 2;
    }

    private double menuCell(List<Integer> sizes) {
        Settings.Menu menu = settings.menu;
        double fixed = (sizes.size() - 1) * menu.cardGap();
        int cells = 0;
        for (int size : sizes) {
            fixed += (size + 1) * menu.cellGap() + menu.pad() * 2;
            cells += size;
        }
        return Math.min(menu.cell(), (settings.pageWidth - menu.margin() * 2 - fixed) / cells);
    }

    private void miniBoard(double x, double y, int size, double cell, int[] values) {
        double gap = settings.menu.cellGap();
        double span = previewSpan(size, cell);
        double reference = settings.cellSize(settings.referenceSize);
        rect(new Settings.Area(x, y, span, span), PANEL + 1, settings.boardColor);
        for (int i = 0; i < size * size; i++) {
            double centerX = x + gap + (i % size) * (cell + gap) + cell / 2;
            double centerY = y + gap + (i / size) * (cell + gap) + cell / 2;
            Settings.Area area = new Settings.Area(centerX - cell / 2, centerY - cell / 2, cell, cell);
            int value = values.length == size * size ? values[i] : 0;
            if (value == 0) {
                rect(area, GLOW, settings.cellColor);
                continue;
            }
            Settings.TileColors colors = settings.tileColors(value);
            rect(area, TILE, colors.background());
            label(centerX, centerY, settings.tileTextHeight(value) * cell / reference, TILE_TEXT, tileText(value, colors));
        }
    }

    private void page(String hint) {
        double backdrop = settings.backdropSize;
        rect(new Settings.Area((settings.pageWidth - backdrop) / 2, (settings.pageHeight - backdrop) / 2, backdrop, backdrop), BACKDROP, settings.backdropColor);
        rect(new Settings.Area(0, 0, settings.pageWidth, settings.pageHeight), PAGE, settings.pageColor);
        label(settings.pageWidth / 2, settings.hintY, settings.hintTextHeight, PAGE, texts.text(hint));
    }

    private void cursor() {
        double size = settings.cursorSize;
        double inner = size - settings.cursorBorder * 2;
        cursor = rect(new Settings.Area(0, 0, size, size), CURSOR, settings.cursorBorderColor);
        cursorInner = rect(new Settings.Area(0, 0, inner, inner), CURSOR + 1, settings.cursorColor);
    }

    void tick() {
        now++;
        if (pending.isEmpty()) return;
        List<Pending> due = null;
        for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
            Pending next = iterator.next();
            if (next.due() > now) continue;
            if (due == null) due = new ArrayList<>();
            due.add(next);
            iterator.remove();
        }
        if (due == null) return;
        due.sort(Comparator.comparingLong(Pending::due));
        due.forEach(p -> p.action().run());
    }

    void sync(Game game, boolean animate) {
        flush();
        tiles.values().forEach(this::removeTile);
        tiles.clear();
        for (int cell = 0; cell < side * side; cell++) {
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
        later(settings.slideTicks + settings.settleTicks, () -> {
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
        if (score != shownScore) {
            shownScore = score;
            Component text = texts.text("board.value", "%value%", score);
            scoreValue.set(d -> d.text(text));
        }
        if (best != shownBest) {
            shownBest = best;
            Component text = texts.text("board.value", "%value%", best);
            bestValue.set(d -> d.text(text));
        }
        if (gained <= 0) return;
        Settings.Area area = settings.score.area();
        double x = area.centerX();
        double y = area.y() + settings.score.valueY();
        Part floating = label(x, y, settings.floatTextHeight, FLOAT, texts.text("board.float", "%value%", gained));
        later(1, () -> floating.animate(settings.floatTicks, d -> {
            d.setTransformation(labelTransform(x, y - settings.floatDistance, settings.floatTextHeight, FLOAT, 1));
            d.setTextOpacity(FADED);
        }));
        later(settings.floatTicks + 1, floating::remove);
    }

    void confetti() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Settings.Area board = settings.board;
        List<Color> colors = settings.confettiColors;
        if (colors.isEmpty()) return;
        for (int i = 0; i < settings.confettiCount; i++) {
            double size = settings.confettiMinSize + random.nextDouble() * (settings.confettiMaxSize - settings.confettiMinSize);
            double direction = random.nextDouble() * Math.PI * 2;
            double distance = settings.confettiSpread * (0.4 + random.nextDouble() * 0.6);
            double burstX = board.centerX() + Math.cos(direction) * distance;
            double burstY = board.centerY() + Math.sin(direction) * distance * 0.7 - settings.confettiSpread * 0.3;
            double landX = burstX + (random.nextDouble() - 0.5) * settings.confettiSpread * 0.4;
            double landY = burstY + settings.confettiFall * (0.6 + random.nextDouble() * 0.4);
            float spin = (float) ((random.nextDouble() - 0.5) * Math.PI * 4);
            Color color = colors.get(random.nextInt(colors.size()));
            Part piece = spawn(d -> {
                d.text(Component.text(BLOCK));
                d.setBackgroundColor(color);
            }, () -> pieceTransform(board.centerX(), board.centerY(), size, 0, HIDDEN));
            later(1, () -> piece.animate(settings.confettiBurstTicks, d -> d.setTransformation(pieceTransform(burstX, burstY, size, spin / 2, 1))));
            later(1 + settings.confettiBurstTicks, () -> piece.animate(settings.confettiFallTicks, d -> d.setTransformation(pieceTransform(landX, landY, size, spin, HIDDEN))));
            later(1 + settings.confettiBurstTicks + settings.confettiFallTicks, piece::remove);
        }
    }

    private Transformation pieceTransform(double x, double y, double size, float angle, float scale) {
        float sx = (float) (size * unit() / (BLOCK_WIDTH * PIXEL)) * scale;
        float sy = (float) (size * unit() / (BLOCK_HEIGHT * PIXEL)) * scale;
        Quaternionf rotation = new Quaternionf().rotateZ(angle);
        Vector3f offset = new Vector3f(BLOCK_SHIFT_X * PIXEL * sx, BLOCK_HEIGHT / 2 * PIXEL * sy, 0).rotate(rotation);
        Vector3f translation = new Vector3f(localX(x) - offset.x, localY(y) - offset.y, localZ(CONFETTI));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, 1), new Quaternionf());
    }

    void undoEnabled(boolean enabled) {
        undo.enabled = enabled;
        undo.paint();
    }

    void overlay(Overlay next, boolean canUndo) {
        if (next == overlay && canUndo == overlayUndo) return;
        overlay = next;
        overlayUndo = canUndo;
        overlayParts.forEach(Part::remove);
        overlayParts.clear();
        overlayButtons.clear();
        if (next == Overlay.NONE) return;
        showOverlay(next, canUndo);
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
        Transformation outer = rectTransform(x, y, size, size, CURSOR, 1);
        Transformation center = rectTransform(x, y, inner, inner, CURSOR + 1, 1);
        cursor.animate(ticks, d -> d.setTransformation(outer));
        cursorInner.animate(ticks, d -> d.setTransformation(center));
        hover(buttons, x, y);
        hover(overlayButtons, x, y);
    }

    private void hover(List<Button> list, double x, double y) {
        for (Button button : list) {
            boolean hovered = button.enabled && button.area.contains(x, y);
            if (hovered == button.hovered) continue;
            button.hovered = hovered;
            button.paint();
        }
    }

    Click click() {
        for (Button button : buttons) if (button.hovered) return new Click(button.action, button.size);
        for (Button button : overlayButtons) if (button.hovered) return new Click(button.action, button.size);
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
                texts.text(next == Overlay.WON ? "board.won" : "board.over"));
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
                default -> "board.try-again";
            };
            Settings.Area area = new Settings.Area(x, board.y() + settings.overlayButtonY, width, settings.overlayButtonHeight);
            Button button = new Button(area, action, key, settings.overlayButtonTextHeight, OVERLAY_BUTTON);
            overlayButtons.add(button);
            overlayParts.add(button.background);
            overlayParts.add(button.label);
            x += width + settings.overlayButtonGap;
        }
    }

    private Part scoreBox(Settings.ScoreBox box, String labelKey) {
        Settings.Area area = box.area();
        rect(area, PANEL, box.color());
        label(area.centerX(), area.y() + box.labelY(), box.labelHeight(), CELL, texts.text(labelKey));
        return label(area.centerX(), area.y() + box.valueY(), box.valueHeight(), CELL, Component.empty());
    }

    private void appear(int id, int value, int cell) {
        Tile tile = tile(value, cell, HIDDEN);
        tiles.put(id, tile);
        later(1, () -> place(tile, cell, 1, settings.appearTicks));
    }

    private Tile tile(int value, int cell, float scale) {
        Settings.TileColors colors = settings.tileColors(value);
        double size = settings.cellSize(side);
        double height = settings.tileTextHeight(value) * fit;
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
        double halo = settings.cellSize(side) + settings.glowMargin * fit * 2;
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
        return tileTexts.computeIfAbsent(value, key -> texts.text("board.tile", "%value%", key).colorIfAbsent(TextColor.color(colors.text().asRGB())));
    }

    private void place(Tile tile, int cell, float scale, int ticks) {
        double size = settings.cellSize(side);
        double height = settings.tileTextHeight(tile.value) * fit;
        double halo = size + settings.glowMargin * fit * 2;
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
        double size = settings.cellSize(side);
        double gap = settings.gap(side);
        return settings.board.x() + gap + (cell % side) * (size + gap) + size / 2;
    }

    private double cellY(int cell) {
        double size = settings.cellSize(side);
        double gap = settings.gap(side);
        return settings.board.y() + gap + (cell / side) * (size + gap) + size / 2;
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
