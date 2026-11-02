package dev.luis.mc2048;

import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

final class Session {

    private static final double MIN_POINTER_STEP = 0.5;

    private final Mc2048 plugin;
    private final Settings settings;
    private final Player player;
    private final Game game;
    private final ClientView client;
    private final Map<Position, BlockData> realBlocks = new HashMap<>();
    private View view;
    private ItemDisplay camera;
    private ScheduledTask task;
    private Input input;
    private double yawAnchor;
    private double pitchAnchor;
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    private boolean closed;

    Session(Mc2048 plugin, Settings settings, Player player, Game game) {
        this.plugin = plugin;
        this.settings = settings;
        this.player = player;
        this.game = game;
        this.client = new ClientView(player);
        this.input = player.getCurrentInput();
    }

    void open() {
        Location base = player.getLocation();
        World world = base.getWorld();
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int top = Math.max(world.getHighestBlockYAt(x, z), base.getBlockY()) + settings.heightAboveGround;
        int y = Math.max(world.getMinHeight() + settings.boxDown + 1, Math.min(top, world.getMaxHeight() - settings.boxUp - 2));
        buildBox(world, x, y, z);
        Location eye = new Location(world, x + 0.5, y + 0.5, z + settings.backGap, 0, 0);
        camera = world.spawn(eye, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setVisibleByDefault(false);
        });
        player.showEntity(plugin, camera);
        Location front = eye.clone();
        front.setYaw(180);
        view = new View(plugin, player, settings, front);
        view.build(game);
        refresh(0, 0);
        yawAnchor = base.getYaw();
        pitchAnchor = base.getPitch();
        task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(), () -> close(false), 1, 1);
        player.getScheduler().runDelayed(plugin, scheduled -> {
            if (!closed) client.attach(camera);
        }, null, settings.attachDelay);
        player.sendMessage(settings.text("start"));
    }

    void close(boolean notify) {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        plugin.ended(player.getUniqueId(), this);
        plugin.store().save(player.getUniqueId(), game);
        client.detach();
        if (player.isOnline()) {
            player.sendMultiBlockChange(realBlocks);
            if (notify) player.sendMessage(settings.text("stop"));
        }
        if (view != null) view.remove();
        if (camera != null) View.kill(plugin, camera);
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
        double yawLimit = Math.toDegrees(Math.atan(halfWidth / settings.distance));
        double pitchLimit = Math.toDegrees(Math.atan(halfHeight / settings.distance));
        double yaw = wrap(look.getYaw() - yawAnchor);
        if (Math.abs(yaw) > yawLimit) {
            yaw = Math.copySign(yawLimit, yaw);
            yawAnchor = look.getYaw() - yaw;
        }
        double pitch = look.getPitch() - pitchAnchor;
        if (Math.abs(pitch) > pitchLimit) {
            pitch = Math.copySign(pitchLimit, pitch);
            pitchAnchor = look.getPitch() - pitch;
        }
        double x = settings.pageWidth / 2 + settings.distance * Math.tan(Math.toRadians(yaw)) / settings.unit;
        double y = settings.pageHeight / 2 + settings.distance * Math.tan(Math.toRadians(pitch)) / settings.unit;
        if (Math.abs(x - pointerX) < MIN_POINTER_STEP && Math.abs(y - pointerY) < MIN_POINTER_STEP) return;
        pointerX = x;
        pointerY = y;
        view.pointer(x, y);
    }

    private void buildBox(World world, int x, int y, int z) {
        Map<Position, BlockData> fake = new HashMap<>();
        for (int dx = -settings.boxSide; dx <= settings.boxSide; dx++) {
            for (int dy = -settings.boxDown; dy <= settings.boxUp; dy++) {
                for (int dz = -settings.boxBack; dz <= settings.boxFront; dz++) {
                    boolean shell = Math.abs(dx) == settings.boxSide || dy == -settings.boxDown || dy == settings.boxUp
                            || dz == -settings.boxBack || dz == settings.boxFront;
                    if (!shell) continue;
                    Position position = Position.block(x + dx, y + dy, z + dz);
                    realBlocks.put(position, world.getBlockData(x + dx, y + dy, z + dz));
                    fake.put(position, settings.boxBlock);
                }
            }
        }
        player.sendMultiBlockChange(fake);
    }

    private static double wrap(double angle) {
        double wrapped = angle % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
}
