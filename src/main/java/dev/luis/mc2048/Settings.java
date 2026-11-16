package dev.luis.mc2048;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class Settings {

    public record Area(double x, double y, double width, double height) {
        public double centerX() { return x + width / 2; }
        public double centerY() { return y + height / 2; }
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public record ScoreBox(Area area, double labelY, double labelHeight, double valueY, double valueHeight) {}

    public record TileColors(Color background, Color text) {}

    public record Theme(String id, Color page, Color board, Color cell, Color score, Color best, Color button, Color buttonHover,
                        Color buttonDisabled, Color settingsButton, Color overlayWon, Color overlayOver, Color glow, Color cursor,
                        Color cursorBorder, Map<Integer, TileColors> tiles, TileColors superTile, Map<Integer, TextColor> text) {

        public TileColors tile(int value) {
            return tiles.getOrDefault(value, superTile);
        }

        public Component recolor(Component component) {
            if (text.isEmpty()) return component;
            TextColor mapped = component.color() == null ? null : text.get(component.color().value());
            Component result = mapped == null ? component : component.color(mapped);
            List<Component> children = result.children();
            return children.isEmpty() ? result : result.children(children.stream().map(this::recolor).toList());
        }
    }

    public record Options(double titleY, double titleHeight, double rowY, double rowGap, double rowHeight, double labelX,
                          double labelHeight, double controlX, double controlWidth, double stepWidth, double textHeight) {}

    public record Menu(double titleY, double titleHeight, double subtitleY, double subtitleHeight, double cardY, double margin,
                       double cardGap, double pad, double cell, double cellGap, double nameY, double nameHeight, double previewY,
                       double bestGap, double bestHeight, Area back, double backHeight) {}

    private static final int MIN_SIZE = 2;
    private static final int MAX_SIZE = 16;
    private static final int MAX_MENU_SIZES = 4;
    private static final int DEFAULT_SIZE = 4;
    private static final String DEFAULT_THEME = "classic";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final TextReplacementConfig KEYBINDS = TextReplacementConfig.builder()
            .match(Pattern.compile("\\{(key\\.[a-z.]+)}"))
            .replacement((match, builder) -> Component.keybind(match.group(1)))
            .build();

    public final List<Integer> sizes;
    public final int referenceSize;
    public final int startTiles;
    public final int winValue;
    public final double fourChance;
    public final int undoCooldown;

    public final double distance;
    public final double lensOffset;
    public final int hintClearTicks;
    public final double zoomDefault;
    public final double zoomMin;
    public final double zoomMax;
    public final double zoomStep;
    public final int zoomTicks;
    public final int clearRadius;
    public final int clearUp;
    public final int clearDown;

    public final int slideTicks;
    public final int popTicks;
    public final int settleTicks;
    public final float popScale;
    public final int appearTicks;
    public final int floatTicks;
    public final double floatDistance;
    public final int cursorTicks;

    public final double unit;
    public final double layer;
    public final double pageWidth;
    public final double pageHeight;
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
    public final Area board;
    private final double gap;
    public final Menu menu;
    public final Options options;
    public final Area settingsButton;
    public final Prefs defaults;
    public final int volumeStep;
    public final double overlayTextY;
    public final double overlayTextHeight;
    public final double overlayButtonY;
    public final double overlayButtonWidth;
    public final double overlayButtonHeight;
    public final double overlayButtonGap;
    public final double overlayButtonTextHeight;
    public final double floatTextHeight;
    public final int confettiCount;
    public final double confettiMinSize;
    public final double confettiMaxSize;
    public final double confettiSpread;
    public final double confettiFall;
    public final int confettiBurstTicks;
    public final int confettiFallTicks;
    public final List<Color> confettiColors = new ArrayList<>();
    public final int glowFrom;
    public final double glowMinAlpha;
    public final double glowMaxAlpha;
    public final double glowMargin;
    public final double hintY;
    public final double hintTextHeight;
    public final double backdropSize;
    public final Color backdropColor;
    public final Area zoomIn;
    public final Area zoomOut;
    public final double zoomTextHeight;
    public final double cursorSize;
    public final double cursorBorder;
    public final List<Theme> themes;
    private final Map<String, Theme> themesById = new LinkedHashMap<>();

    private final TreeMap<Integer, Double> tileTextHeights = new TreeMap<>();
    private final Map<String, List<Sound>> sounds = new HashMap<>();
    public final float mergePitchStep;
    public final float mergePitchMax;
    private final Texts fallback;
    private final Map<String, Texts> languages = new HashMap<>();

    public static final class Texts {
        private final FileConfiguration messages;
        private final Map<String, Component> cache = new ConcurrentHashMap<>();

        private Texts(FileConfiguration messages) {
            this.messages = messages;
        }

        public boolean has(String path) {
            return messages.isString(path);
        }

        public Component text(String path) {
            return cache.computeIfAbsent(path, key -> LEGACY.deserialize(Objects.requireNonNullElse(messages.getString(key), "")).replaceText(KEYBINDS));
        }

        public Component text(String path, String placeholder, Object value) {
            return LEGACY.deserialize(Objects.requireNonNullElse(messages.getString(path), "").replace(placeholder, String.valueOf(value)));
        }
    }

    public Settings(FileConfiguration config, FileConfiguration messages, Map<String, FileConfiguration> languages) {
        this.fallback = new Texts(messages);
        languages.forEach((language, file) -> this.languages.put(language, new Texts(file)));
        List<Integer> sizes = config.getIntegerList("game.sizes").stream()
                .filter(size -> size >= MIN_SIZE && size <= MAX_SIZE).distinct().sorted().limit(MAX_MENU_SIZES).toList();
        this.sizes = sizes.isEmpty() ? List.of(DEFAULT_SIZE) : sizes;
        startTiles = config.getInt("game.start-tiles");
        winValue = config.getInt("game.win-value");
        fourChance = config.getDouble("game.four-chance");
        undoCooldown = config.getInt("game.undo-cooldown");

        distance = config.getDouble("camera.distance");
        lensOffset = config.getDouble("camera.lens-offset");
        hintClearTicks = config.getInt("camera.hint-clear-ticks");
        zoomDefault = config.getDouble("camera.zoom.default");
        zoomMin = config.getDouble("camera.zoom.min");
        zoomMax = config.getDouble("camera.zoom.max");
        zoomStep = config.getDouble("camera.zoom.step");
        zoomTicks = config.getInt("camera.zoom.ticks");
        clearRadius = config.getInt("camera.clear.radius");
        clearUp = config.getInt("camera.clear.up");
        clearDown = config.getInt("camera.clear.down");

        slideTicks = config.getInt("animation.slide-ticks");
        popTicks = config.getInt("animation.pop-ticks");
        settleTicks = config.getInt("animation.settle-ticks");
        popScale = (float) config.getDouble("animation.pop-scale");
        appearTicks = config.getInt("animation.appear-ticks");
        floatTicks = config.getInt("animation.float-ticks");
        floatDistance = config.getDouble("animation.float-distance");
        cursorTicks = config.getInt("animation.cursor-ticks");

        ConfigurationSection layout = config.getConfigurationSection("layout");
        unit = layout.getDouble("unit");
        layer = layout.getDouble("layer");
        pageWidth = layout.getDouble("page.width");
        pageHeight = layout.getDouble("page.height");
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
        double boardSize = layout.getDouble("board.size");
        board = new Area(layout.getDouble("board.x"), layout.getDouble("board.y"), boardSize, boardSize);
        gap = layout.getDouble("board.gap");
        referenceSize = Math.max(1, layout.getInt("board.reference-size"));
        overlayTextY = layout.getDouble("overlay.text-y");
        overlayTextHeight = layout.getDouble("overlay.text-height");
        overlayButtonY = layout.getDouble("overlay.button-y");
        overlayButtonWidth = layout.getDouble("overlay.button-width");
        overlayButtonHeight = layout.getDouble("overlay.button-height");
        overlayButtonGap = layout.getDouble("overlay.button-gap");
        overlayButtonTextHeight = layout.getDouble("overlay.button-text-height");
        floatTextHeight = layout.getDouble("float.text-height");
        confettiCount = config.getInt("animation.confetti.count");
        confettiMinSize = config.getDouble("animation.confetti.min-size");
        confettiMaxSize = config.getDouble("animation.confetti.max-size");
        confettiSpread = config.getDouble("animation.confetti.spread");
        confettiFall = config.getDouble("animation.confetti.fall");
        confettiBurstTicks = config.getInt("animation.confetti.burst-ticks");
        confettiFallTicks = config.getInt("animation.confetti.fall-ticks");
        for (String hex : config.getStringList("animation.confetti.colors")) confettiColors.add(color(hex));
        glowFrom = Math.max(1, layout.getInt("tiles.glow.from"));
        glowMinAlpha = layout.getDouble("tiles.glow.min-alpha");
        glowMaxAlpha = layout.getDouble("tiles.glow.max-alpha");
        glowMargin = layout.getDouble("tiles.glow.margin");
        hintY = layout.getDouble("hint.y");
        hintTextHeight = layout.getDouble("hint.text-height");
        backdropSize = layout.getDouble("backdrop.size");
        backdropColor = color(layout.getString("backdrop.color"));
        ConfigurationSection menuSection = layout.getConfigurationSection("menu");
        menu = new Menu(menuSection.getDouble("title-y"), menuSection.getDouble("title-height"),
                menuSection.getDouble("subtitle-y"), menuSection.getDouble("subtitle-height"),
                menuSection.getDouble("card-y"), menuSection.getDouble("margin"), menuSection.getDouble("card-gap"),
                menuSection.getDouble("pad"), menuSection.getDouble("cell"), menuSection.getDouble("cell-gap"),
                menuSection.getDouble("name-y"), menuSection.getDouble("name-height"), menuSection.getDouble("preview-y"),
                menuSection.getDouble("best-gap"), menuSection.getDouble("best-height"),
                area(menuSection.getConfigurationSection("back")), menuSection.getDouble("back.text-height"));
        ConfigurationSection prefs = layout.getConfigurationSection("prefs");
        options = new Options(prefs.getDouble("title-y"), prefs.getDouble("title-height"), prefs.getDouble("row-y"),
                prefs.getDouble("row-gap"), prefs.getDouble("row-height"), prefs.getDouble("label-x"),
                prefs.getDouble("label-height"), prefs.getDouble("control-x"), prefs.getDouble("control-width"),
                prefs.getDouble("step-width"), prefs.getDouble("text-height"));
        settingsButton = area(layout.getConfigurationSection("settings-button"));
        ConfigurationSection player = config.getConfigurationSection("player-defaults");
        defaults = new Prefs(player.getBoolean("sound"), player.getInt("volume"), player.getBoolean("confetti"),
                player.getBoolean("glow"), player.getBoolean("animations"), player.getString("theme", ""));
        volumeStep = Math.max(1, player.getInt("volume-step"));
        zoomIn = area(layout.getConfigurationSection("zoom-in"));
        zoomOut = area(layout.getConfigurationSection("zoom-out"));
        zoomTextHeight = layout.getDouble("zoom-in.text-height");
        cursorSize = layout.getDouble("cursor.size");
        cursorBorder = layout.getDouble("cursor.border");

        ConfigurationSection heights = layout.getConfigurationSection("tiles.text-height");
        for (String digits : heights.getKeys(false)) tileTextHeights.put(Integer.parseInt(digits), heights.getDouble(digits));
        Map<Integer, TileColors> tiles = new HashMap<>();
        TileColors top = tiles(layout.getConfigurationSection("tiles.colors"), tiles, null);
        Theme base = new Theme("", color(layout.getString("page.color")), color(layout.getString("board.color")),
                color(layout.getString("board.cell")), color(layout.getString("score.color")), color(layout.getString("best.color")),
                color(layout.getString("button.color")), color(layout.getString("button.hover")), color(layout.getString("button.disabled")),
                color(layout.getString("settings-button.color")), color(layout.getString("overlay.won")), color(layout.getString("overlay.over")),
                color(layout.getString("tiles.glow.color")), color(layout.getString("cursor.color")), color(layout.getString("cursor.border-color")),
                tiles, top, Map.of());
        ConfigurationSection themeSection = config.getConfigurationSection("themes");
        if (themeSection != null) {
            for (String id : themeSection.getKeys(false)) themesById.put(id, theme(id, themeSection.getConfigurationSection(id), base));
        }
        if (themesById.isEmpty()) themesById.put(DEFAULT_THEME, theme(DEFAULT_THEME, null, base));
        themes = List.copyOf(themesById.values());

        ConfigurationSection soundSection = config.getConfigurationSection("sounds.events");
        for (String event : soundSection.getKeys(false)) {
            List<Sound> list = new ArrayList<>();
            for (Map<?, ?> entry : soundSection.getMapList(event)) {
                Object name = entry.get("sound");
                if (name == null) continue;
                float volume = entry.get("volume") instanceof Number number ? number.floatValue() : 1;
                float pitch = entry.get("pitch") instanceof Number number ? number.floatValue() : 1;
                list.add(Sound.sound(Key.key(name.toString()), Sound.Source.MASTER, volume, pitch));
            }
            sounds.put(event, list);
        }
        mergePitchStep = (float) config.getDouble("sounds.merge-pitch-step");
        mergePitchMax = (float) config.getDouble("sounds.merge-pitch-max");
    }

    public double gap(int size) {
        return gap * referenceSize / size;
    }

    public double cellSize(int size) {
        return (board.width() - gap(size) * (size + 1)) / size;
    }

    public double scale(int size) {
        return cellSize(size) / cellSize(referenceSize);
    }

    public double tileTextHeight(int value) {
        Map.Entry<Integer, Double> entry = tileTextHeights.floorEntry(String.valueOf(value).length());
        return entry == null ? tileTextHeights.firstEntry().getValue() : entry.getValue();
    }

    public List<Sound> sounds(String event) {
        return sounds.getOrDefault(event, List.of());
    }

    public Theme theme(String id) {
        return themesById.getOrDefault(id, themes.getFirst());
    }

    public String shiftTheme(String id, int step) {
        int index = themes.indexOf(theme(id));
        return themes.get(Math.floorMod(index + step, themes.size())).id();
    }

    public Texts texts(Locale locale) {
        return languages.getOrDefault(locale.getLanguage(), fallback);
    }

    public Component text(String path) {
        return fallback.text(path);
    }

    private static Area area(ConfigurationSection section) {
        return new Area(section.getDouble("x"), section.getDouble("y"), section.getDouble("width"), section.getDouble("height"));
    }

    private static ScoreBox scoreBox(ConfigurationSection section) {
        return new ScoreBox(area(section), section.getDouble("label-y"),
                section.getDouble("label-height"), section.getDouble("value-y"), section.getDouble("value-height"));
    }

    private static TileColors tiles(ConfigurationSection section, Map<Integer, TileColors> into, TileColors top) {
        if (section == null) return top;
        for (String value : section.getKeys(false)) {
            List<String> pair = section.getStringList(value);
            if (pair.size() < 2) continue;
            TileColors tile = new TileColors(color(pair.get(0)), color(pair.get(1)));
            if (value.equals("super")) top = tile;
            else if (value.matches("\\d{1,9}")) into.put(Integer.parseInt(value), tile);
        }
        return top;
    }

    private static Theme theme(String id, ConfigurationSection section, Theme base) {
        if (section == null) section = new MemoryConfiguration();
        Map<Integer, TileColors> tiles = new HashMap<>(base.tiles());
        TileColors top = tiles(section.getConfigurationSection("tiles"), tiles, base.superTile());
        Map<Integer, TextColor> text = new HashMap<>();
        ConfigurationSection mapping = section.getConfigurationSection("text");
        if (mapping != null) {
            for (String from : mapping.getKeys(false)) text.put(color(from).asRGB(), TextColor.color(color(mapping.getString(from)).asRGB()));
        }
        Color score = pick(section, "score", base.score());
        return new Theme(id, pick(section, "page", base.page()), pick(section, "board", base.board()), pick(section, "cell", base.cell()),
                score, pick(section, "score", base.best()), pick(section, "button", base.button()),
                pick(section, "button-hover", base.buttonHover()), pick(section, "button-disabled", base.buttonDisabled()),
                pick(section, "settings-button", base.settingsButton()), pick(section, "overlay-won", base.overlayWon()),
                pick(section, "overlay-over", base.overlayOver()), pick(section, "glow", base.glow()), pick(section, "cursor", base.cursor()),
                pick(section, "cursor-border", base.cursorBorder()), Map.copyOf(tiles), top, Map.copyOf(text));
    }

    private static Color pick(ConfigurationSection section, String key, Color fallback) {
        return section.isString(key) ? color(section.getString(key)) : fallback;
    }

    private static Color color(String hex) {
        String digits = hex == null ? "" : hex.replace("#", "");
        long value = digits.isEmpty() ? 0 : Long.parseLong(digits, 16);
        return Color.fromARGB(digits.length() <= 6 ? (int) (0xFF000000L | value) : (int) value);
    }
}
