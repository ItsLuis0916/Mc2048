package dev.luis.mc2048;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Store {

    private final File folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    public Store(File folder) {
        this.folder = folder;
    }

    public record Saved(boolean game, double zoom) {}

    public CompletableFuture<Saved> load(UUID uuid, Game game, double defaultZoom) {
        return CompletableFuture.supplyAsync(() -> {
            File file = file(uuid);
            if (!file.isFile()) return new Saved(false, defaultZoom);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            double zoom = yaml.getDouble("zoom", defaultZoom);
            int[] cells = ints(yaml.getIntegerList("cells"));
            if (cells.length != game.size() * game.size()) return new Saved(false, zoom);
            int[] undo = ints(yaml.getIntegerList("undo.cells"));
            game.load(cells, yaml.getInt("score"), yaml.getInt("best"), yaml.getBoolean("won"),
                    yaml.getBoolean("keep-playing"), undo.length == cells.length ? undo : null,
                    yaml.getInt("undo.score"), yaml.getInt("undo.lock"));
            return new Saved(true, zoom);
        }, io);
    }

    public void save(UUID uuid, Game game, double zoom) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("zoom", zoom);
        yaml.set("cells", list(game.cells()));
        yaml.set("score", game.score());
        yaml.set("best", game.best());
        yaml.set("won", game.won());
        yaml.set("keep-playing", game.keptPlaying());
        int[] undo = game.undoCells();
        if (undo != null) yaml.set("undo.cells", list(undo));
        yaml.set("undo.score", game.undoScore());
        yaml.set("undo.lock", game.undoLock());
        String data = yaml.saveToString();
        io.execute(() -> {
            try {
                File file = file(uuid);
                file.getParentFile().mkdirs();
                File temp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(temp.toPath(), data);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    public void close() {
        io.close();
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private static int[] ints(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<Integer> list(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }
}
