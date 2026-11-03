package dev.luis.mc2048;

import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Session {

    private static final double MIN_POINTER_STEP = 0.5;
    private static final float YAW_STEP = 360f / 256;
    private static final BlockData AIR = Material.AIR.createBlockData();

    private final Mc2048 plugin;
    private final Settings settings;
    private final Player player;
    private final Game game;
    private final ClientView client;
    private final Settings.Texts texts;
    private final Map<Position, BlockData> realBlocks = new HashMap<>();
    private final List<Entity> hidden = new ArrayList<>();
    private View view;
    private ItemDisplay seat;
    private ArmorStand camera;
    private Location ear;
    private ScheduledTask task;
    private Input input;
    private float yaw;
    private double zoom;
    private double yawAnchor;
    private double pitchAnchor;
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    private boolean closed;
    private View.Overlay shownOverlay = View.Overlay.NONE;

    Session(Mc2048 plugin, Settings settings, Player player, Game game, double zoom) {
        this.plugin = plugin;
        this.settings = settings;
        this.zoom = clampZoom(zoom);
        this.player = player;
        this.game = game;
        this.client = new ClientView(player);
        this.texts = settings.texts(player.locale());
        this.input = player.getCurrentInput();
    }

    boolean open() {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        yaw = Math.round(feet.getYaw() / YAW_STEP) * YAW_STEP;
        Location lens = player.getEyeLocation();
        lens.setYaw(yaw);
        lens.setPitch(0);
        lens.add(lens.getDirection().multiply(settings.lensOffset));
        clearView(world, lens);
        Location back = lens.clone();
        Location front = lens.clone();
        front.setYaw(yaw + 180);
        view = new View(plugin, player, settings, texts, List.of(front, back), zoom);
        view.build(game);
        refresh(0, 0);
        ear = lens.clone();
        camera = world.spawn(lens, ArmorStand.class, stand -> {
            stand.setPersistent(false);
            stand.setVisibleByDefault(false);
            stand.setMarker(true);
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            ClientView.face(stand, yaw);
            AttributeInstance distance = stand.getAttribute(Attribute.CAMERA_DISTANCE);
            if (distance != null) distance.setBaseValue(0);
        });
        player.showEntity(plugin, camera);
        client.attach(camera, yaw, 0);
        yawAnchor = yaw;
        pitchAnchor = 0;
        seat = world.spawn(feet, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            ClientView.keepFeet(display, player);
        });
        player.showEntity(plugin, seat);
        if (!seat.addPassenger(player)) {
            close(false);
            return false;
        }
        task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(), () -> close(false), 1, 1);
        player.sendMessage(texts.text("start"));
        play("open", 0);
        return true;
    }

    void close(boolean notify) {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        plugin.ended(player.getUniqueId(), this);
        plugin.store().save(player.getUniqueId(), game, zoom);
        client.detach();
        if (camera != null) View.kill(plugin, camera);
        if (seat != null) {
            seat.removePassenger(player);
            View.kill(plugin, seat);
        }
        if (player.isOnline()) {
            player.sendMultiBlockChange(realBlocks);
            hidden.forEach(entity -> player.showEntity(plugin, entity));
            if (notify) player.sendMessage(texts.text("stop"));
            play("close", 0);
        }
        if (view != null) view.remove();
    }

    void refake(Block block) {
        Position position = Position.block(block.getX(), block.getY(), block.getZ());
        if (!realBlocks.containsKey(position)) return;
        player.getScheduler().run(plugin, task -> {
            if (!closed) player.sendBlockChange(block.getLocation(), AIR);
        }, null);
    }

    void input(Input next) {
        Input previous = input;
        input = next;
        if (next.isSneak() && !previous.isSneak()) {
            close(true);
            return;
        }
        if (next.isForward() && !previous.isForward()) move(Game.Direction.UP);
        if (next.isBackward() && !previous.isBackward()) move(Game.Direction.DOWN);
        if (next.isLeft() && !previous.isLeft()) move(Game.Direction.LEFT);
        if (next.isRight() && !previous.isRight()) move(Game.Direction.RIGHT);
    }

    void click() {
        View.Action action = view.click();
        if (action == null) return;
        play(action == View.Action.UNDO ? "undo" : action == View.Action.RESTART ? "restart" : "click", 0);
        switch (action) {
            case UNDO -> {
                if (!game.undo()) return;
                view.sync(game, false);
            }
            case RESTART -> {
                game.restart(settings.startTiles);
                view.sync(game, true);
            }
            case KEEP_GOING -> game.keepPlaying();
            case ZOOM_IN -> {
                resize(1);
                return;
            }
            case ZOOM_OUT -> {
                resize(-1);
                return;
            }
            case LEAVE -> {
                close(true);
                return;
            }
        }
        refresh(0, 0);
    }

    private void move(Game.Direction direction) {
        Game.Turn turn = game.move(direction);
        if (turn == null) {
            play("blocked", 0);
            return;
        }
        int merged = turn.merges().stream().mapToInt(Game.Merge::value).max().orElse(0);
        if (merged == 0) play("slide", 0);
        else play("merge", (float) Math.min(settings.mergePitchMax, settings.mergePitchStep * (Integer.numberOfTrailingZeros(merged) - 2)));
        view.render(turn);
        refresh(turn.gained(), settings.slideTicks + settings.appearTicks);
    }

    private void refresh(int gained, int overlayDelay) {
        plugin.store().remember(player.getUniqueId(), game);
        view.score(game.score(), game.best(), gained);
        view.undoEnabled(game.canUndo());
        View.Overlay overlay = game.showWin() ? View.Overlay.WON : game.over() ? View.Overlay.OVER : View.Overlay.NONE;
        view.overlay(overlay, game.canUndo(), overlayDelay);
        if (overlay != shownOverlay && overlay != View.Overlay.NONE) play(overlay == View.Overlay.WON ? "won" : "over", 0);
        shownOverlay = overlay;
    }

    private void play(String event, float pitchShift) {
        if (ear == null) return;
        for (Sound sound : settings.sounds(event)) {
            player.playSound(Sound.sound(sound).pitch(sound.pitch() + pitchShift).build(), ear.getX(), ear.getY(), ear.getZ());
        }
    }

    private void resize(int direction) {
        double next = clampZoom(zoom * Math.pow(settings.zoomStep, direction));
        if (next == zoom) return;
        zoom = next;
        view.zoom(zoom);
        double unit = settings.unit * zoom;
        Location look = player.getLocation();
        yawAnchor = look.getYaw() - Math.toDegrees(Math.atan((pointerX - settings.pageWidth / 2) * unit / settings.distance));
        pitchAnchor = look.getPitch() - Math.toDegrees(Math.atan((pointerY - settings.pageHeight / 2) * unit / settings.distance));
    }

    private double clampZoom(double value) {
        return Math.max(settings.zoomMin, Math.min(settings.zoomMax, value));
    }

    private void tick() {
        view.tick();
        Location look = player.getLocation();
        double unit = settings.unit * zoom;
        double halfWidth = settings.pageWidth / 2 * unit;
        double halfHeight = settings.pageHeight / 2 * unit;
        double sideLimit = Math.toDegrees(Math.atan(halfWidth / settings.distance));
        double upLimit = Math.toDegrees(Math.atan(halfHeight / settings.distance));
        double side = wrap(look.getYaw() - yawAnchor);
        if (Math.abs(side) > sideLimit) {
            side = Math.copySign(sideLimit, side);
            yawAnchor = look.getYaw() - side;
        }
        double down = look.getPitch() - pitchAnchor;
        if (Math.abs(down) > upLimit) {
            down = Math.copySign(upLimit, down);
            pitchAnchor = look.getPitch() - down;
        }
        double x = settings.pageWidth / 2 + settings.distance * Math.tan(Math.toRadians(side)) / unit;
        double y = settings.pageHeight / 2 + settings.distance * Math.tan(Math.toRadians(down)) / unit;
        if (Math.abs(x - pointerX) < MIN_POINTER_STEP && Math.abs(y - pointerY) < MIN_POINTER_STEP) return;
        pointerX = x;
        pointerY = y;
        view.pointer(x, y);
    }

    private void clearView(World world, Location eye) {
        int x = eye.getBlockX();
        int y = eye.getBlockY();
        int z = eye.getBlockZ();
        int radius = settings.clearRadius;
        Map<Position, BlockData> fake = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -settings.clearDown; dy <= settings.clearUp; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (y + dy < world.getMinHeight() || y + dy >= world.getMaxHeight()) continue;
                    Position position = Position.block(x + dx, y + dy, z + dz);
                    realBlocks.put(position, world.getBlockData(x + dx, y + dy, z + dz));
                    fake.put(position, AIR);
                }
            }
        }
        player.sendMultiBlockChange(fake);
        BoundingBox inside = new BoundingBox(x - radius, y - settings.clearDown, z - radius, x + radius + 1, y + settings.clearUp + 1, z + radius + 1);
        for (Entity entity : world.getNearbyEntities(inside)) {
            if (entity.equals(player) || entity.equals(seat)) continue;
            player.hideEntity(plugin, entity);
            hidden.add(entity);
        }
    }

    private static double wrap(double angle) {
        double wrapped = angle % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
}
