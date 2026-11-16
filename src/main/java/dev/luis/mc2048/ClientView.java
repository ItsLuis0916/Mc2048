package dev.luis.mc2048;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClientView extends ChannelOutboundHandlerAdapter {

    private static final String HANDLER = "mc2048";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final int PLAYER_MENU = 0;
    private static final int FIRST_HOTBAR_SLOT = 36;
    private static final int OFFHAND_SLOT = 45;
    private static final int LAST_HOTBAR_INDEX = 8;
    private static final int OFFHAND_INDEX = 40;
    private static final int FLAGS = 0;
    private static final byte INVISIBLE = (byte) (1 << Entity.FLAG_INVISIBLE);

    private final ServerPlayer player;
    private final Channel channel;
    private final Set<Entity> live = new LinkedHashSet<>();
    private final Set<Entity> added = new LinkedHashSet<>();
    private final IntArrayList removed = new IntArrayList();

    ClientView(Player player) {
        this.player = ((CraftPlayer) player).getHandle();
        this.channel = this.player.connection.connection.channel;
    }

    void spawn(org.bukkit.entity.Entity entity) {
        Entity handle = ((CraftEntity) entity).getHandle();
        live.add(handle);
        added.add(handle);
    }

    void remove(org.bukkit.entity.Entity entity) {
        Entity handle = ((CraftEntity) entity).getHandle();
        if (live.remove(handle) && !added.remove(handle)) removed.add(handle.getId());
    }

    void commit() {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (!removed.isEmpty()) packets.add(new ClientboundRemoveEntitiesPacket(removed.toIntArray()));
        removed.clear();
        for (Entity entity : added) {
            packets.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.getXRot(), entity.getYRot(), entity.getType(), 0, Vec3.ZERO, entity.getYRot()));
            List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
            entity.getEntityData().packDirty();
            if (data != null) packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
        }
        added.clear();
        for (Entity entity : live) {
            if (entity.getEntityData().isDirty()) packets.add(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().packDirty()));
        }
        if (packets.isEmpty() || !channel.isOpen()) return;
        if (packets.size() == 1) {
            player.connection.send(packets.getFirst());
            return;
        }
        for (int from = 0; from < packets.size(); from += BundlerInfo.BUNDLE_SIZE_LIMIT) {
            player.connection.send(new ClientboundBundlePacket(List.copyOf(packets.subList(from, Math.min(packets.size(), from + BundlerInfo.BUNDLE_SIZE_LIMIT)))));
        }
    }

    static void keepFeet(org.bukkit.entity.Entity seat, Player rider) {
        Entity vehicle = ((CraftEntity) seat).getHandle();
        ServerPlayer passenger = ((CraftPlayer) rider).getHandle();
        double feet = vehicle.getPassengerRidingPosition(passenger).y - passenger.getVehicleAttachmentPoint(vehicle).y;
        vehicle.setPos(vehicle.getX(), 2 * vehicle.getY() - feet, vehicle.getZ());
    }

    static void face(org.bukkit.entity.Entity entity, float yaw) {
        Entity handle = ((CraftEntity) entity).getHandle();
        handle.setYRot(yaw);
        handle.setYHeadRot(yaw);
        handle.setYBodyRot(yaw);
    }

    void attach(org.bukkit.entity.Entity camera, float yaw, float pitch) {
        if (channel.pipeline().get(HANDLER) == null) channel.pipeline().addBefore(PACKET_HANDLER, HANDLER, this);
        player.connection.send(new ClientboundSetCameraPacket(((CraftEntity) camera).getHandle()));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, GameType.SPECTATOR.getId()));
        player.connection.send(new ClientboundPlayerRotationPacket(yaw, false, pitch, false));
        player.connection.send(flags(true));
        player.inventoryMenu.sendAllDataToRemote();
    }

    void detach() {
        if (channel.pipeline().get(HANDLER) == this) channel.pipeline().remove(this);
        if (!channel.isOpen()) return;
        player.connection.send(new ClientboundSetCameraPacket(player));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, player.gameMode().getId()));
        player.onUpdateAbilities();
        player.connection.send(flags(false));
        player.inventoryMenu.sendAllDataToRemote();
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        super.write(context, message instanceof Packet<?> packet ? rewrite(packet) : message, promise);
    }

    private Packet<?> rewrite(Packet<?> packet) {
        return switch (packet) {
            case ClientboundContainerSetSlotPacket slot when slot.getContainerId() == PLAYER_MENU && hidden(slot.getSlot()) ->
                    new ClientboundContainerSetSlotPacket(PLAYER_MENU, slot.getStateId(), slot.getSlot(), ItemStack.EMPTY);
            case ClientboundContainerSetContentPacket content when content.containerId() == PLAYER_MENU -> {
                List<ItemStack> items = new ArrayList<>(content.items());
                for (int slot = FIRST_HOTBAR_SLOT; slot <= OFFHAND_SLOT && slot < items.size(); slot++) items.set(slot, ItemStack.EMPTY);
                yield new ClientboundContainerSetContentPacket(PLAYER_MENU, content.stateId(), items, content.carriedItem());
            }
            case ClientboundSetPlayerInventoryPacket inventory when inventory.slot() <= LAST_HOTBAR_INDEX || inventory.slot() == OFFHAND_INDEX ->
                    new ClientboundSetPlayerInventoryPacket(inventory.slot(), ItemStack.EMPTY);
            case ClientboundSetEntityDataPacket data when data.id() == player.getId() -> {
                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(data.packedItems());
                values.replaceAll(value -> value.id() == FLAGS && value.value() instanceof Byte flags
                        ? new SynchedEntityData.DataValue<>(FLAGS, EntityDataSerializers.BYTE, (byte) (flags | INVISIBLE))
                        : value);
                yield new ClientboundSetEntityDataPacket(data.id(), values);
            }
            default -> packet;
        };
    }

    private ClientboundSetEntityDataPacket flags(boolean invisible) {
        byte flags = 0;
        List<SynchedEntityData.DataValue<?>> current = player.getEntityData().getNonDefaultValues();
        if (current != null) {
            for (SynchedEntityData.DataValue<?> value : current) {
                if (value.id() == FLAGS && value.value() instanceof Byte set) flags = set;
            }
        }
        if (invisible) flags |= INVISIBLE;
        return new ClientboundSetEntityDataPacket(player.getId(), List.of(new SynchedEntityData.DataValue<>(FLAGS, EntityDataSerializers.BYTE, flags)));
    }

    private static boolean hidden(int slot) {
        return slot >= FIRST_HOTBAR_SLOT && slot <= OFFHAND_SLOT;
    }
}
