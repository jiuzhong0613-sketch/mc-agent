package dev.mcagent.obs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.AgentConfig;
import dev.mcagent.bridge.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Structure over screenshots: everything a player can perceive, in a stable JSON shape.
 */
public final class Observation {
    private static final int MAX_BLOCKS = 800;
    private static final int MAX_ENTITIES = 48;

    private Observation() {
    }

    public static JsonObject snapshot(int tick, JsonArray events) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        out.addProperty("protocol", "mc-agent/1");
        out.addProperty("tick", tick);

        boolean inGame = mc.level != null && mc.player != null;
        out.addProperty("inGame", inGame);
        out.addProperty("paused", inGame && mc.isPaused());
        if (mc.screen != null) {
            out.addProperty("screen", mc.screen.getClass().getSimpleName());
        }
        if (!inGame) {
            return out;
        }

        LocalPlayer player = mc.player;
        out.addProperty("dimension", mc.level.dimension().location().toString());
        out.add("player", player(player, mc));
        out.add("inventory", inventory(player));
        out.add("lookingAt", lookingAt(mc));
        out.add("blocks", blocks(mc, player));
        out.add("entities", entities(mc, player));
        out.add("menu", menu(player));
        out.add("events", events);
        out.add("coverage", coverage());
        return out;
    }

    private static JsonObject coverage() {
        JsonObject o = new JsonObject();
        o.addProperty("blockRadius", AgentConfig.OBS_RADIUS.get());
        o.addProperty("blockCap", MAX_BLOCKS);
        o.addProperty("entityRadius", 32);
        o.addProperty("entityCap", MAX_ENTITIES);
        o.addProperty("loadedChunksOnly", true);
        o.addProperty("note", "partial view; call query_* tools for anything outside these limits");
        return o;
    }

    private static JsonObject player(LocalPlayer p, Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName().getString());
        o.addProperty("uuid", p.getUUID().toString());
        o.add("pos", Json.vec(p.getX(), p.getY(), p.getZ()));
        o.add("vel", Json.vec(p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z));
        o.addProperty("yaw", Json.round(p.getYRot()));
        o.addProperty("pitch", Json.round(p.getXRot()));
        o.addProperty("onGround", p.onGround());
        o.addProperty("sneaking", p.isShiftKeyDown());
        o.addProperty("sprinting", p.isSprinting());
        o.addProperty("health", Json.round(p.getHealth()));
        o.addProperty("maxHealth", Json.round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", Json.round(p.getFoodData().getSaturationLevel()));
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("air", p.getAirSupply());
        o.addProperty("dead", p.isDeadOrDying());
        o.addProperty("gameMode", mc.gameMode.getPlayerMode().getName());
        return o;
    }

    private static JsonObject inventory(LocalPlayer p) {
        JsonArray arr = new JsonArray();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                slot.addProperty("damage", stack.getDamageValue());
            }
            arr.add(slot);
        }
        JsonObject out = new JsonObject();
        out.addProperty("selected", inv.selected);
        out.add("slots", arr);
        return out;
    }

    private static JsonObject lookingAt(Minecraft mc) {
        JsonObject o = new JsonObject();
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            o.addProperty("kind", "block");
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            o.addProperty("face", block.getDirection().getName());
            o.add("pos", Json.vec(pos.getX(), pos.getY(), pos.getZ()));
        } else if (hit instanceof EntityHitResult entity) {
            o.addProperty("kind", "entity");
            o.addProperty("entityId", entity.getEntity().getId());
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString());
        } else {
            o.addProperty("kind", "none");
        }
        return o;
    }

    private static final class BlockEntry {
        final int x;
        final int y;
        final int z;
        final String id;
        final double dist;

        BlockEntry(int x, int y, int z, String id, double dist) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
            this.dist = dist;
        }
    }

    private static JsonArray blocks(Minecraft mc, LocalPlayer p) {
        int radius = AgentConfig.OBS_RADIUS.get();
        int minY = Math.max(mc.level.getMinBuildHeight(), (int) Math.floor(p.getY()) - radius);
        int maxY = Math.min(mc.level.getMaxBuildHeight() - 1, (int) Math.floor(p.getY()) + radius);
        int px = (int) Math.floor(p.getX());
        int py = (int) Math.floor(p.getY());
        int pz = (int) Math.floor(p.getZ());

        List<BlockEntry> found = new ArrayList<>();
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    double dist = Math.sqrt(pos.distSqr(p.blockPosition()));
                    found.add(new BlockEntry(x, y, z, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), dist));
                }
            }
        }
        found.sort(Comparator.comparingDouble(e -> e.dist));

        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(found.size(), MAX_BLOCKS); i++) {
            BlockEntry e = found.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", e.x);
            o.addProperty("y", e.y);
            o.addProperty("z", e.z);
            o.addProperty("id", e.id);
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray entities(Minecraft mc, LocalPlayer p) {
        List<Entity> nearby = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || e.distanceTo(p) > 32.0) {
                continue;
            }
            nearby.add(e);
        }
        nearby.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));

        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(nearby.size(), MAX_ENTITIES); i++) {
            Entity e = nearby.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("uuid", e.getUUID().toString());
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("name", e.getName().getString());
            o.add("pos", Json.vec(e.getX(), e.getY(), e.getZ()));
            o.addProperty("dist", Json.round(e.distanceTo(p)));
            if (e instanceof LivingEntity living) {
                o.addProperty("health", Json.round(living.getHealth()));
            }
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject menu(LocalPlayer p) {
        JsonObject o = new JsonObject();
        AbstractContainerMenu menu = p.containerMenu;
        boolean open = menu != p.inventoryMenu;
        o.addProperty("open", open);
        if (!open) {
            return o;
        }
        o.addProperty("id", menu.containerId);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(menu.slots.size(), 90); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject s = new JsonObject();
            s.addProperty("slot", slot.index);
            s.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            s.addProperty("count", stack.getCount());
            slots.add(s);
        }
        o.add("slots", slots);
        return o;
    }
}
