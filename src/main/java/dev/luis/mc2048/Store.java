package dev.luis.mc2048;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Store {

    public record Record(int best, int score, int highest) {}

    private static final Record EMPTY = new Record(0, 0, 0);
    private static final String SIZES = "sizes";
    private static final List<String> LEGACY_KEYS = List.of("cells", "score", "best", "won", "keep-playing", "undo.cells", "undo.score", "undo.lock");

    private final File folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Integer>> bests = new ConcurrentHashMap<>();

    public Store(File folder) {
        this.folder = folder;
    }

    public record Slot(int best, int[] cells) {}

    public record Profile(double zoom, Map<Integer, Slot> slots) {}

    public CompletableFuture<Profile> profile(UUID uuid, double defaultZoom) {
        return CompletableFuture.supplyAsync(() -> {
            File file = file(uuid);
            if (!file.isFile()) return new Profile(defaultZoom, Map.of());
            YamlConfiguration yaml = read(file);
            Map<Integer, Slot> slots = new HashMap<>();
            sections(yaml).forEach((size, section) -> slots.put(size, new Slot(section.getInt("best"), ints(section.getIntegerList("cells")))));
            return new Profile(yaml.getDouble("zoom", defaultZoom), slots);
        }, io);
    }

    public CompletableFuture<Boolean> load(UUID uuid, Game game) {
        return CompletableFuture.supplyAsync(() -> {
            File file = file(uuid);
            if (!file.isFile()) return false;
            ConfigurationSection section = sections(read(file)).get(game.size());
            if (section == null) return false;
            int[] cells = ints(section.getIntegerList("cells"));
            if (cells.length != game.size() * game.size()) return false;
            int[] undo = ints(section.getIntegerList("undo.cells"));
            game.load(cells, section.getInt("score"), section.getInt("best"), section.getBoolean("won"),
                    section.getBoolean("keep-playing"), undo.length == cells.length ? undo : null,
                    section.getInt("undo.score"), section.getInt("undo.lock"));
            remember(uuid, game);
            return true;
        }, io);
    }

    public void save(UUID uuid, Game game, double zoom) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cells", list(game.cells()));
        data.put("score", game.score());
        data.put("best", game.best());
        data.put("won", game.won());
        data.put("keep-playing", game.keptPlaying());
        Map<String, Object> undo = new LinkedHashMap<>();
        int[] undoCells = game.undoCells();
        if (undoCells != null) undo.put("cells", list(undoCells));
        undo.put("score", game.undoScore());
        undo.put("lock", game.undoLock());
        data.put("undo", undo);
        int size = game.size();
        io.execute(() -> {
            try {
                File file = file(uuid);
                YamlConfiguration yaml = file.isFile() ? read(file) : new YamlConfiguration();
                yaml.set("zoom", zoom);
                yaml.set("last", size);
                yaml.createSection(SIZES + "." + size, data);
                file.getParentFile().mkdirs();
                File temp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(temp.toPath(), yaml.saveToString());
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    public Record record(UUID uuid) {
        return records.getOrDefault(uuid, EMPTY);
    }

    public void remember(UUID uuid, Game game) {
        Map<Integer, Integer> sizes = bests.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        sizes.put(game.size(), game.best());
        records.put(uuid, new Record(Collections.max(sizes.values()), game.score(), game.highest()));
    }

    public void preload(UUID uuid) {
        io.execute(() -> {
            File file = file(uuid);
            if (!file.isFile()) return;
            YamlConfiguration yaml = read(file);
            Map<Integer, ConfigurationSection> sections = sections(yaml);
            if (sections.isEmpty()) return;
            Map<Integer, Integer> sizes = bests.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
            sections.forEach((size, section) -> sizes.putIfAbsent(size, section.getInt("best")));
            ConfigurationSection last = sections.getOrDefault(yaml.getInt("last"), sections.values().iterator().next());
            int highest = last.getIntegerList("cells").stream().mapToInt(Integer::intValue).max().orElse(0);
            records.putIfAbsent(uuid, new Record(Collections.max(sizes.values()), last.getInt("score"), highest));
        });
    }

    public void forget(UUID uuid) {
        records.remove(uuid);
        bests.remove(uuid);
    }

    public void close() {
        io.close();
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private static YamlConfiguration read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Integer> cells = yaml.getIntegerList("cells");
        if (cells.isEmpty()) return yaml;
        int size = (int) Math.round(Math.sqrt(cells.size()));
        String base = SIZES + "." + size + ".";
        for (String key : LEGACY_KEYS) yaml.set(base + key, yaml.get(key));
        yaml.set("last", size);
        for (String key : LEGACY_KEYS) yaml.set(key, null);
        yaml.set("undo", null);
        return yaml;
    }

    private static Map<Integer, ConfigurationSection> sections(YamlConfiguration yaml) {
        Map<Integer, ConfigurationSection> result = new TreeMap<>();
        ConfigurationSection sizes = yaml.getConfigurationSection(SIZES);
        if (sizes == null) return result;
        for (String key : sizes.getKeys(false)) {
            ConfigurationSection section = sizes.getConfigurationSection(key);
            if (section != null && key.matches("\\d{1,3}")) result.put(Integer.parseInt(key), section);
        }
        return result;
    }

    private static int[] ints(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<Integer> list(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }
}
