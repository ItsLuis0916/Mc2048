package dev.luis.mc2048;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    private static Game game(int size) {
        return new Game(size, 2048, 0, 2, new Random(1));
    }

    @Test
    void keepsOneGamePerSize(@TempDir Path folder) throws Exception {
        UUID uuid = UUID.randomUUID();
        Store store = new Store(folder.toFile());
        Game small = game(4);
        small.restart(2);
        Game large = game(6);
        large.restart(2);
        store.save(uuid, small, 1.5);
        store.save(uuid, large, 1.2);
        store.close();

        Store reopened = new Store(folder.toFile());
        Game loaded = game(6);
        assertTrue(reopened.load(uuid, loaded).get());
        assertEquals(36, loaded.cells().length);
        assertTrue(reopened.load(uuid, game(4)).get());
        assertFalse(reopened.load(uuid, game(8)).get());
        Store.Profile profile = reopened.profile(uuid, 2, new Prefs(true, 100, true, true, true)).get();
        assertEquals(1.2, profile.zoom());
        assertEquals(java.util.Set.of(4, 6), profile.slots().keySet());
        reopened.close();
    }

    @Test
    void keepsPlayerSettings(@TempDir Path folder) throws Exception {
        UUID uuid = UUID.randomUUID();
        Prefs defaults = new Prefs(true, 100, true, true, true);
        Store store = new Store(folder.toFile());
        assertEquals(100, store.profile(uuid, 1.5, defaults).get().prefs().volume);
        store.saveSettings(uuid, new Prefs(false, 40, false, true, false));
        Game game = game(4);
        game.restart(2);
        store.save(uuid, game, 1.5);
        Prefs saved = store.profile(uuid, 1.5, defaults).get().prefs();
        assertFalse(saved.sound);
        assertEquals(40, saved.volume);
        assertFalse(saved.confetti);
        assertTrue(saved.glow);
        assertFalse(saved.animations);
        store.close();
    }

    @Test
    void readsTheOldSingleBoardFile(@TempDir Path folder) throws Exception {
        UUID uuid = UUID.randomUUID();
        File file = new File(folder.toFile(), uuid + ".yml");
        Files.writeString(file.toPath(), """
                zoom: 1.4
                cells: [2, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8]
                score: 40
                best: 120
                won: false
                keep-playing: false
                undo:
                  score: 0
                  lock: 0
                """);
        Store store = new Store(folder.toFile());
        Game loaded = game(4);
        assertTrue(store.load(uuid, loaded).get());
        assertEquals(40, loaded.score());
        assertEquals(120, loaded.best());
        assertEquals(120, store.profile(uuid, 2, new Prefs(true, 100, true, true, true)).get().slots().get(4).best());
        store.close();
    }
}
