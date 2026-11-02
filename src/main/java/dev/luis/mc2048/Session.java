package dev.luis.mc2048;

import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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
    private static final String CAMERA_KEY = "camera";
    private static final BlockData AIR = Material.AIR.createBlockData();

    private final Mc2048 plugin;
    private final Settings settings;
    private final Player player;
    private final Game game;
    private final ClientView client;
    private final NamespacedKey cameraKey;
    private final Map<Position, BlockData> realBlocks = new HashMap<>();
    private final List<Entity> hidden = new ArrayList<>();
    private View view;
    private ItemDisplay seat;
    private ScheduledTask task;
    private Input input;
    private float yaw;
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    private boolean closed;

    Session(Mc2048 plugin, Settings settings, Player player, Game game) {
        this.plugin = plugin;
        this.settings = settings;
        this.player = player;
        this.game = game;
        this.client = new ClientView(player);
        this.cameraKey = new NamespacedKey(plugin, CAMERA_KEY);
        this.input = player.getCurrentInput();
    }

    boolean open() {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        seat = world.spawn(feet, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            ClientView.keepFeet(display, player);
        });
        player.showEntity(plugin, seat);
        if (!seat.addPassenger(player)) {
            View.kill(plugin, seat);
            return false;
        }
        yaw = feet.getYaw();
        Location eye = player.getEyeLocation();
        eye.setPitch(0);
        buildBox(world, eye);
        Location front = eye.clone();
        front.setYaw(yaw + 180);
        Location back = eye.clone();
        back.setYaw(yaw);
        view = new View(plugin, player, settings, List.of(front, back));
        view.build(game);
        refresh(0, 0);
        AttributeInstance camera = player.getAttribute(Attribute.CAMERA_DISTANCE);
        if (camera != null) {
            camera.removeModifier(cameraKey);
            camera.addTransientModifier(new AttributeModifier(cameraKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        client.attach(yaw, 0);
        task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(), () -> close(false), 1, 1);
        player.sendMessage(settings.text("start"));
        return true;
    }

    void close(boolean notify) {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        plugin.ended(player.getUniqueId(), this);
        plugin.store().save(player.getUniqueId(), game);
        client.detach();
        AttributeInstance camera = player.getAttribute(Attribute.CAMERA_DISTANCE);
        if (camera != null) camera.removeModifier(cameraKey);
        if (seat != null) {
            seat.removePassenger(player);
            View.kill(plugin, seat);
        }
        if (player.isOnline()) {
            player.sendMultiBlockChange(realBlocks);
            hidden.forEach(entity -> player.showEntity(plugin, entity));
            if (notify) player.sendMessage(settings.text("stop"));
        }
        if (view != null) view.remove();
    }

    boolean owns(Entity entity) {
        return entity.equals(seat);
    }

    void refake(Block block) {
        Position position = Position.block(block.getX(), block.getY(), block.getZ());
        if (!realBlocks.containsKey(position)) return;
        player.getScheduler().run(plugin, task -> {
            if (!closed) player.sendBlockChange(block.getLocation(), settings.boxBlock);
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
            case LEAVE -> {
                close(true);
                return;
            }
        }
        refresh(0, 0);
    }

    private void move(Game.Direction direction) {
        Game.Turn turn = game.move(direction);
        if (turn == null) return;
        view.render(turn);
        refresh(turn.gained(), settings.slideTicks + settings.appearTicks);
    }

    private void refresh(int gained, int overlayDelay) {
        view.score(game.score(), game.best(), gained);
        view.undoEnabled(game.canUndo());
        View.Overlay overlay = game.showWin() ? View.Overlay.WON : game.over() ? View.Overlay.OVER : View.Overlay.NONE;
        view.overlay(overlay, game.canUndo(), overlayDelay);
    }

    private void tick() {
        view.tick();
        Location look = player.getLocation();
        double halfWidth = settings.pageWidth / 2 * settings.unit;
        double halfHeight = settings.pageHeight / 2 * settings.unit;
        double side = Math.toRadians(wrap(look.getYaw() - yaw));
        double pitch = Math.toRadians(look.getPitch());
        double sideLimit = Math.atan(halfWidth / settings.distance);
        double clampedSide = Math.max(-sideLimit, Math.min(sideLimit, side));
        double down = Math.tan(pitch) / Math.cos(clampedSide) * settings.distance;
        double clampedDown = Math.max(-halfHeight, Math.min(halfHeight, down));
        if (clampedSide != side || clampedDown != down) {
            float pitchBack = (float) Math.toDegrees(Math.atan(clampedDown * Math.cos(clampedSide) / settings.distance));
            client.look((float) (yaw + Math.toDegrees(clampedSide)), pitchBack);
        }
        double x = settings.pageWidth / 2 + settings.distance * Math.tan(clampedSide) / settings.unit;
        double y = settings.pageHeight / 2 + clampedDown / settings.unit;
        if (Math.abs(x - pointerX) < MIN_POINTER_STEP && Math.abs(y - pointerY) < MIN_POINTER_STEP) return;
        pointerX = x;
        pointerY = y;
        view.pointer(x, y);
    }

    private void buildBox(World world, Location eye) {
        int x = eye.getBlockX();
        int y = eye.getBlockY();
        int z = eye.getBlockZ();
        int radius = settings.boxRadius;
        Map<Position, BlockData> fake = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -settings.boxDown; dy <= settings.boxUp; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (y + dy < world.getMinHeight() || y + dy >= world.getMaxHeight()) continue;
                    boolean shell = Math.abs(dx) == radius || Math.abs(dz) == radius || dy == -settings.boxDown || dy == settings.boxUp;
                    Position position = Position.block(x + dx, y + dy, z + dz);
                    realBlocks.put(position, world.getBlockData(x + dx, y + dy, z + dz));
                    fake.put(position, shell ? settings.boxBlock : AIR);
                }
            }
        }
        player.sendMultiBlockChange(fake);
        BoundingBox inside = new BoundingBox(x - radius, y - settings.boxDown, z - radius, x + radius + 1, y + settings.boxUp + 1, z + radius + 1);
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
