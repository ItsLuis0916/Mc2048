package dev.luis.mc2048;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Mc2048 extends JavaPlugin implements Listener {

    private static final String MESSAGES = "messages.yml";
    private static final String PLAYERS = "players";
    private static final String PLACEHOLDER_API = "PlaceholderAPI";
    private static final String LANGUAGE_FILE = "messages_%s.yml";
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("messages_([a-z]{2,3})\\.yml");
    private static final List<String> BUNDLED_LANGUAGES = List.of("de");

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    private Settings settings;
    private Store store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        if (!new File(getDataFolder(), MESSAGES).exists()) saveResource(MESSAGES, false);
        for (String language : BUNDLED_LANGUAGES) {
            String name = String.format(LANGUAGE_FILE, language);
            if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
        }
        YamlConfiguration english = messages(MESSAGES, null);
        Map<String, FileConfiguration> languages = new HashMap<>();
        File[] files = getDataFolder().listFiles((folder, name) -> LANGUAGE_PATTERN.matcher(name).matches());
        if (files != null) {
            for (File file : files) {
                Matcher matcher = LANGUAGE_PATTERN.matcher(file.getName());
                if (matcher.matches()) languages.put(matcher.group(1), messages(file.getName(), english));
            }
        }
        settings = new Settings(getConfig(), english, languages);
        store = new Store(new File(getDataFolder(), PLAYERS));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(player -> store.preload(player.getUniqueId()));
        if (getServer().getPluginManager().isPluginEnabled(PLACEHOLDER_API)) new Placeholders(this).register();
    }

    @Override
    public void onDisable() {
        sessions.values().forEach(session -> session.close(false));
        if (store != null) store.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(settings.text("players-only"));
            return true;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null) session.close(true);
        else open(player);
        return true;
    }

    private YamlConfiguration messages(String name, YamlConfiguration fallback) {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
        InputStream bundled = getResource(name);
        YamlConfiguration defaults = bundled == null ? fallback : YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8));
        if (bundled != null && fallback != null) defaults.setDefaults(fallback);
        if (defaults != null) file.setDefaults(defaults);
        return file;
    }

    Store store() {
        return store;
    }

    void ended(UUID uuid, Session session) {
        sessions.remove(uuid, session);
    }

    CompletableFuture<Game> game(UUID uuid, int size) {
        Game game = new Game(size, settings.winValue, settings.fourChance, settings.undoCooldown, ThreadLocalRandom.current());
        return store.load(uuid, game).exceptionally(error -> false).thenApply(found -> {
            if (!found) game.restart(settings.startTiles);
            return game;
        });
    }

    private void open(Player player) {
        UUID uuid = player.getUniqueId();
        if (!opening.add(uuid)) return;
        boolean menu = settings.sizes.size() > 1;
        CompletableFuture<Game> first = menu ? CompletableFuture.completedFuture(null) : game(uuid, settings.sizes.getFirst());
        store.profile(uuid, settings.zoomDefault).exceptionally(error -> new Store.Profile(settings.zoomDefault, Map.of()))
                .thenCombine(first, (profile, game) -> {
                    player.getScheduler().run(this, task -> {
                        opening.remove(uuid);
                        if (!player.isOnline() || sessions.containsKey(uuid)) return;
                        Session session = new Session(this, settings, player, profile.zoom());
                        sessions.put(uuid, session);
                        if (!session.open(profile.slots(), game)) sessions.remove(uuid, session);
                    }, () -> opening.remove(uuid));
                    return null;
                });
    }

    private Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    private void block(PlayerEvent event) {
        if (event instanceof Cancellable cancellable && session(event.getPlayer()) != null) cancellable.setCancelled(true);
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Session session = session(event.getPlayer());
        if (session != null) session.input(event.getInput());
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        Session session = session(event.getPlayer());
        if (session == null || event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        event.setCancelled(true);
        session.click();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Session session = session(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        if (event.getClickedBlock() != null) session.refake(event.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        Session session = session(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        session.refake(event.getBlock());
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) close(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        block(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        block(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        block(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) close(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        close(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        close(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        store.preload(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = session(event.getPlayer());
        if (session != null) session.close(false);
        store.forget(event.getPlayer().getUniqueId());
    }

    private void close(Player player) {
        Session session = session(player);
        if (session != null) session.close(true);
    }
}
