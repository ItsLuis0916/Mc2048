package dev.luis.mc2048;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class Mc2048 extends JavaPlugin implements Listener {

    private static final String MESSAGES = "messages.yml";
    private static final String PLAYERS = "players";

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    private Settings settings;
    private Store store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File messages = new File(getDataFolder(), MESSAGES);
        if (!messages.exists()) saveResource(MESSAGES, false);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(messages);
        loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(getResource(MESSAGES), StandardCharsets.UTF_8)));
        settings = new Settings(getConfig(), loaded);
        store = new Store(new File(getDataFolder(), PLAYERS));
        getServer().getPluginManager().registerEvents(this, this);
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

    Store store() {
        return store;
    }

    void ended(UUID uuid, Session session) {
        sessions.remove(uuid, session);
    }

    private void open(Player player) {
        UUID uuid = player.getUniqueId();
        if (!opening.add(uuid)) return;
        Game game = new Game(settings.size, settings.winValue, settings.fourChance, settings.undoCooldown, ThreadLocalRandom.current());
        store.load(uuid, game, settings.zoomDefault).whenComplete((saved, error) -> player.getScheduler().run(this, task -> {
            opening.remove(uuid);
            if (!player.isOnline() || sessions.containsKey(uuid)) return;
            if (saved == null || !saved.game()) game.restart(settings.startTiles);
            Session session = new Session(this, settings, player, game, saved == null ? settings.zoomDefault : saved.zoom());
            sessions.put(uuid, session);
            if (!session.open()) sessions.remove(uuid, session);
        }, () -> opening.remove(uuid)));
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
    public void onQuit(PlayerQuitEvent event) {
        Session session = session(event.getPlayer());
        if (session != null) session.close(false);
    }

    private void close(Player player) {
        Session session = session(player);
        if (session != null) session.close(true);
    }
}
