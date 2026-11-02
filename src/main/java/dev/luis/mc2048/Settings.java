package dev.luis.mc2048;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class Settings {

    public record Area(double x, double y, double width, double height) {
        public double centerX() { return x + width / 2; }
        public double centerY() { return y + height / 2; }
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public record ScoreBox(Area area, Color color, double labelY, double labelHeight, double valueY, double valueHeight) {}

    public record TileColors(Color background, Color text) {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final TextReplacementConfig KEYBINDS = TextReplacementConfig.builder()
            .match(Pattern.compile("\\{(key\\.[a-z.]+)}"))
            .replacement((match, builder) -> Component.keybind(match.group(1)))
            .build();

    public final int size;
    public final int startTiles;
    public final int winValue;
    public final double fourChance;
    public final int undoCooldown;

    public final double distance;
    public final int attachDelay;
    public final double lensOffset;
    public final double zoomDefault;
    public final double zoomMin;
    public final double zoomMax;
    public final double zoomStep;
    public final int zoomTicks;
    public final BlockData boxBlock;
    public final int boxRadius;
    public final int boxUp;
    public final int boxDown;

    public final int slideTicks;
    public final int popTicks;
    public final float popScale;
    public final int appearTicks;
    public final int floatTicks;
    public final double floatDistance;
    public final int overlayTicks;
    public final int cursorTicks;

    public final double unit;
    public final double layer;
    public final double pageWidth;
    public final double pageHeight;
    public final Color pageColor;
    public final Area title;
    public final double titleHeight;
    public final ScoreBox score;
    public final ScoreBox best;
    public final Area undo;
    public final double undoTextHeight;
    public final Area restart;
    public final double restartTextHeight;
    public final Area leave;
    public final double leaveTextHeight;
    public final Color buttonColor;
    public final Color buttonHover;
    public final Color buttonDisabled;
    public final Area board;
    public final double gap;
    public final Color boardColor;
    public final Color cellColor;
    public final Color overlayWon;
    public final Color overlayOver;
    public final double overlayTextY;
    public final double overlayTextHeight;
    public final double overlayButtonY;
    public final double overlayButtonWidth;
    public final double overlayButtonHeight;
    public final double overlayButtonGap;
    public final double overlayButtonTextHeight;
    public final double floatTextHeight;
    public final double hintY;
    public final double hintTextHeight;
    public final Color hintBackground;
    public final int hintTicks;
    public final int hintFadeTicks;
    public final double cursorSize;
    public final double cursorBorder;
    public final Color cursorColor;
    public final Color cursorBorderColor;

    private final TreeMap<Integer, Double> tileTextHeights = new TreeMap<>();
    private final Map<Integer, TileColors> tileColors = new HashMap<>();
    private final TileColors superTile;
    private final FileConfiguration messages;

    public Settings(FileConfiguration config, FileConfiguration messages) {
        this.messages = messages;
        size = config.getInt("game.size");
        startTiles = config.getInt("game.start-tiles");
        winValue = config.getInt("game.win-value");
        fourChance = config.getDouble("game.four-chance");
        undoCooldown = config.getInt("game.undo-cooldown");

        distance = config.getDouble("camera.distance");
        attachDelay = Math.max(1, config.getInt("camera.attach-delay-ticks"));
        lensOffset = config.getDouble("camera.lens-offset");
        zoomDefault = config.getDouble("camera.zoom.default");
        zoomMin = config.getDouble("camera.zoom.min");
        zoomMax = config.getDouble("camera.zoom.max");
        zoomStep = config.getDouble("camera.zoom.step");
        zoomTicks = config.getInt("camera.zoom.ticks");
        Material block = Material.matchMaterial(config.getString("camera.box.block", ""));
        boxBlock = (block != null && block.isBlock() ? block : Material.BLACK_CONCRETE).createBlockData();
        boxRadius = config.getInt("camera.box.radius");
        boxUp = config.getInt("camera.box.up");
        boxDown = config.getInt("camera.box.down");

        slideTicks = config.getInt("animation.slide-ticks");
        popTicks = config.getInt("animation.pop-ticks");
        popScale = (float) config.getDouble("animation.pop-scale");
        appearTicks = config.getInt("animation.appear-ticks");
        floatTicks = config.getInt("animation.float-ticks");
        floatDistance = config.getDouble("animation.float-distance");
        overlayTicks = config.getInt("animation.overlay-ticks");
        cursorTicks = config.getInt("animation.cursor-ticks");

        ConfigurationSection layout = config.getConfigurationSection("layout");
        unit = layout.getDouble("unit");
        layer = layout.getDouble("layer");
        pageWidth = layout.getDouble("page.width");
        pageHeight = layout.getDouble("page.height");
        pageColor = color(layout.getString("page.color"));
        title = area(layout.getConfigurationSection("title"));
        titleHeight = layout.getDouble("title.text-height");
        score = scoreBox(layout.getConfigurationSection("score"));
        best = scoreBox(layout.getConfigurationSection("best"));
        undo = area(layout.getConfigurationSection("undo"));
        undoTextHeight = layout.getDouble("undo.text-height");
        restart = area(layout.getConfigurationSection("restart"));
        restartTextHeight = layout.getDouble("restart.text-height");
        leave = area(layout.getConfigurationSection("leave"));
        leaveTextHeight = layout.getDouble("leave.text-height");
        buttonColor = color(layout.getString("button.color"));
        buttonHover = color(layout.getString("button.hover"));
        buttonDisabled = color(layout.getString("button.disabled"));
        double boardSize = layout.getDouble("board.size");
        board = new Area(layout.getDouble("board.x"), layout.getDouble("board.y"), boardSize, boardSize);
        gap = layout.getDouble("board.gap");
        boardColor = color(layout.getString("board.color"));
        cellColor = color(layout.getString("board.cell"));
        overlayWon = color(layout.getString("overlay.won"));
        overlayOver = color(layout.getString("overlay.over"));
        overlayTextY = layout.getDouble("overlay.text-y");
        overlayTextHeight = layout.getDouble("overlay.text-height");
        overlayButtonY = layout.getDouble("overlay.button-y");
        overlayButtonWidth = layout.getDouble("overlay.button-width");
        overlayButtonHeight = layout.getDouble("overlay.button-height");
        overlayButtonGap = layout.getDouble("overlay.button-gap");
        overlayButtonTextHeight = layout.getDouble("overlay.button-text-height");
        floatTextHeight = layout.getDouble("float.text-height");
        hintY = layout.getDouble("hint.y");
        hintTextHeight = layout.getDouble("hint.text-height");
        hintBackground = color(layout.getString("hint.background"));
        hintTicks = config.getInt("animation.hint-ticks");
        hintFadeTicks = config.getInt("animation.hint-fade-ticks");
        cursorSize = layout.getDouble("cursor.size");
        cursorBorder = layout.getDouble("cursor.border");
        cursorColor = color(layout.getString("cursor.color"));
        cursorBorderColor = color(layout.getString("cursor.border-color"));

        ConfigurationSection heights = layout.getConfigurationSection("tiles.text-height");
        for (String digits : heights.getKeys(false)) tileTextHeights.put(Integer.parseInt(digits), heights.getDouble(digits));
        ConfigurationSection colors = layout.getConfigurationSection("tiles.colors");
        TileColors fallback = null;
        for (String value : colors.getKeys(false)) {
            List<String> pair = colors.getStringList(value);
            TileColors tile = new TileColors(color(pair.get(0)), color(pair.get(1)));
            if (value.equals("super")) fallback = tile;
            else tileColors.put(Integer.parseInt(value), tile);
        }
        superTile = fallback;
    }

    public double cellSize() {
        return (board.width() - gap * (size + 1)) / size;
    }

    public double tileTextHeight(int value) {
        Map.Entry<Integer, Double> entry = tileTextHeights.floorEntry(String.valueOf(value).length());
        return entry == null ? tileTextHeights.firstEntry().getValue() : entry.getValue();
    }

    public TileColors tileColors(int value) {
        return tileColors.getOrDefault(value, superTile);
    }

    public Component text(String path) {
        return LEGACY.deserialize(Objects.requireNonNullElse(messages.getString(path), "")).replaceText(KEYBINDS);
    }

    public Component text(String path, String placeholder, Object value) {
        return LEGACY.deserialize(Objects.requireNonNullElse(messages.getString(path), "").replace(placeholder, String.valueOf(value)));
    }

    private static Area area(ConfigurationSection section) {
        return new Area(section.getDouble("x"), section.getDouble("y"), section.getDouble("width"), section.getDouble("height"));
    }

    private static ScoreBox scoreBox(ConfigurationSection section) {
        return new ScoreBox(area(section), color(section.getString("color")), section.getDouble("label-y"),
                section.getDouble("label-height"), section.getDouble("value-y"), section.getDouble("value-height"));
    }

    private static Color color(String hex) {
        String digits = hex == null ? "" : hex.replace("#", "");
        long value = digits.isEmpty() ? 0 : Long.parseLong(digits, 16);
        return Color.fromARGB(digits.length() <= 6 ? (int) (0xFF000000L | value) : (int) value);
    }
}
