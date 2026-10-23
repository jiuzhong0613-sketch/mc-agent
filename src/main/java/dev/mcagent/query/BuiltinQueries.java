package dev.mcagent.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.bridge.Json;
import dev.mcagent.path.Path;
import dev.mcagent.path.PathService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual probes. Everything here is read-only and runs on the client thread.
 */
public final class BuiltinQueries {
    private BuiltinQueries() {
    }

    static int ttl(String name) {
        return switch (name) {
            case "query_block", "query_route" -> 10;
            case "query_scan" -> 20;
            case "query_entities" -> 5;
            default -> 10;
        };
    }

    static JsonObject dispatch(String name, JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            throw new IllegalStateException("not in game");
        }
        return switch (name) {
            case "query_block" -> block(mc, args);
            case "query_scan" -> scan(mc, args);
            case "query_entities" -> entities(mc, args);
            case "query_inventory" -> inventory(mc);
            case "query_raycast" -> raycast(mc, args);
            case "query_can_stand" -> canStand(mc, args);
            case "query_surface" -> surface(mc, args);
            case "query_route" -> route(mc, args);
            case "query_env" -> env(mc);
            case "query_path" -> pathQuery(args);
            default -> throw new IllegalArgumentException("unknown query: " + name);
        };
    }

    private static BlockPos pos(JsonObject o) {
        return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private static JsonObject block(Minecraft mc, JsonObject args) {
        BlockPos pos = pos(args);
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        if (!mc.level.isLoaded(pos)) {
            out.addProperty("loaded", false);
            return out;
        }
        BlockState state = mc.level.getBlockState(pos);
        out.addProperty("loaded", true);
        out.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        out.addProperty("air", state.isAir());
        out.addProperty("solid", state.isSolid());
        out.addProperty("fluid", !state.getFluidState().isEmpty());
        out.addProperty("hardness", Json.round(state.getDestroySpeed(mc.level, pos)));
        out.addProperty("light", mc.level.getMaxLocalRawBrightness(pos));
        return out;
    }

    private static JsonObject scan(Minecraft mc, JsonObject args) {
        BlockPos center = args.has("x") ? pos(args) : mc.player.blockPosition();
        int radius = Math.min(12, args.has("radius") ? args.get("radius").getAsInt() : 6);
        int visited = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> nearest = new LinkedHashMap<>();
        JsonArray solids = new JsonArray();

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.level.isLoaded(p)) {
                        continue;
                    }
                    visited++;
                    if (visited > 4096) {
                        return scanResult(counts, nearest, solids, radius, visited, true);
                    }
                    BlockState s = mc.level.getBlockState(p);
                    if (s.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                    counts.merge(id, 1, Integer::sum);
                    nearest.putIfAbsent(id, x + "," + y + "," + z);
                    if (s.isSolid() && solids.size() < 40) {
                        JsonObject o = new JsonObject();
                        o.addProperty("x", x);
                        o.addProperty("y", y);
                        o.addProperty("z", z);
                        o.addProperty("id", id);
                        solids.add(o);
                    }
                }
            }
        }
        return scanResult(counts, nearest, solids, radius, visited, false);
    }

    private static JsonObject scanResult(Map<String, Integer> counts, Map<String, String> nearest,
                                         JsonArray solids, int radius, int visited, boolean truncated) {
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("radius", radius);
        out.addProperty("visited", visited);
        out.addProperty("truncated", truncated);
        JsonArray types = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", e.getKey());
                    o.addProperty("count", e.getValue());
                    o.addProperty("nearest", nearest.get(e.getKey()));
                    types.add(o);
                });
        out.add("types", types);
        out.add("solids", solids);
        return out;
    }

    private static JsonObject entities(Minecraft mc, JsonObject args) {
        int radius = Math.min(48, args.has("radius") ? args.get("radius").getAsInt() : 16);
        LocalPlayer p = mc.player;
        List<Entity> found = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e != p && e.distanceTo(p) <= radius) {
                found.add(e);
            }
        }
        found.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(found.size(), 24); i++) {
            Entity e = found.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("name", e.getName().getString());
            o.add("pos", Json.vec(e.getX(), e.getY(), e.getZ()));
            o.addProperty("dist", Json.round(e.distanceTo(p)));
            o.addProperty("lineOfSight", p.hasLineOfSight(e));
            if (e instanceof LivingEntity living) {
                o.addProperty("health", Json.round(living.getHealth()));
            }
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("radius", radius);
        out.add("entities", arr);
        return out;
    }

    private static JsonObject inventory(Minecraft mc) {
        LocalPlayer p = mc.player;
        var inv = p.getInventory();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            o.addProperty("count", stack.getCount());
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("maxDamage", stack.getMaxDamage());
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("selected", inv.selected);
        out.add("slots", arr);
        return out;
    }

    private static JsonObject raycast(Minecraft mc, JsonObject args) {
        double distance = Math.min(64, args.has("distance") ? args.get("distance").getAsDouble() : 6);
        HitResult hit = mc.player.pick(distance, 1.0f, false);
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("distance", distance);
        if (hit instanceof BlockHitResult block) {
            BlockPos p = block.getBlockPos();
            out.addProperty("kind", "block");
            out.addProperty("id", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString());
            out.addProperty("face", block.getDirection().getName());
            out.add("pos", Json.vec(p.getX(), p.getY(), p.getZ()));
            out.addProperty("reach", Json.round(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p))));
        } else if (hit instanceof EntityHitResult entity) {
            out.addProperty("kind", "entity");
            out.addProperty("entity", entity.getEntity().getId());
            out.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString());
        } else {
            out.addProperty("kind", "none");
        }
        return out;
    }

    private static JsonObject canStand(Minecraft mc, JsonObject args) {
        BlockPos p = pos(args);
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("loaded", mc.level.isLoaded(p));
        if (!mc.level.isLoaded(p)) {
            return out;
        }
        BlockState feet = mc.level.getBlockState(p);
        BlockState head = mc.level.getBlockState(p.above());
        BlockState floor = mc.level.getBlockState(p.below());
        boolean feetFree = !feet.isSolid() && feet.getFluidState().isEmpty();
        boolean headFree = !head.isSolid();
        boolean floorSolid = floor.isSolid();
        out.addProperty("feetFree", feetFree);
        out.addProperty("headFree", headFree);
        out.addProperty("floorSolid", floorSolid);
        out.addProperty("water", feet.getFluidState().is(FluidTags.WATER) || head.getFluidState().is(FluidTags.WATER));
        out.addProperty("lava", feet.getFluidState().is(FluidTags.LAVA));
        out.addProperty("canStand", feetFree && headFree && floorSolid);
        return out;
    }

    private static JsonObject surface(Minecraft mc, JsonObject args) {
        int x = args.get("x").getAsInt();
        int z = args.get("z").getAsInt();
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        if (!mc.level.isLoaded(new BlockPos(x, mc.player.blockPosition().getY(), z))) {
            out.addProperty("loaded", false);
            return out;
        }
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos top = new BlockPos(x, y - 1, z);
        out.addProperty("loaded", true);
        out.addProperty("y", y);
        out.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(top).getBlock()).toString());
        out.addProperty("water", mc.level.getBlockState(top).getFluidState().is(FluidTags.WATER));
        return out;
    }

    private static JsonObject route(Minecraft mc, JsonObject args) {
        Vec3 target = new Vec3(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
        Vec3 from = mc.player.getEyePosition();
        double distance = from.distanceTo(target);
        int steps = (int) Math.min(96, Math.ceil(distance / 0.5));
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("distance", Json.round(distance));
        String verdict = "clear";
        JsonObject obstruction = null;

        for (int i = 1; i <= steps; i++) {
            Vec3 point = from.lerp(target, i / (double) steps);
            BlockPos bp = BlockPos.containing(point);
            if (!mc.level.isLoaded(bp)) {
                verdict = "unloaded";
                obstruction = vec(bp);
                break;
            }
            BlockState state = mc.level.getBlockState(bp);
            if (!state.getFluidState().isEmpty()) {
                verdict = state.getFluidState().is(FluidTags.LAVA) ? "lava" : "water";
                obstruction = vec(bp);
                break;
            }
            if (state.isSolid()) {
                verdict = "blocked";
                obstruction = vec(bp);
                obstruction.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                break;
            }
        }
        out.addProperty("verdict", verdict);
        if (obstruction != null) {
            out.add("at", obstruction);
        }
        BlockPos end = BlockPos.containing(target);
        BlockState endFloor = mc.level.getBlockState(end.below());
        out.addProperty("targetFloorSolid", endFloor.isSolid());
        out.addProperty("targetFeetFree", !mc.level.getBlockState(end).isSolid());
        out.addProperty("targetHeadFree", !mc.level.getBlockState(end.above()).isSolid());
        return out;
    }

    private static JsonObject pathQuery(JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            out.addProperty("ok", false);
            out.addProperty("error", "query_path needs x, y, z");
            return out;
        }
        int radius = Math.min(40, args.has("radius") ? args.get("radius").getAsInt() : 32);
        try {
            Path path = PathService.planNow(
                    args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt(), radius);
            out.addProperty("reachable", path.reachable());
            out.addProperty("status", path.status.name());
            out.addProperty("length", path.nodes.size());
            out.addProperty("reason", path.reason);
            out.addProperty("expanded", path.expanded);
            JsonArray sample = new JsonArray();
            for (int i = 0; i < Math.min(path.nodes.size(), 8); i++) {
                int[] node = path.nodes.get(i);
                JsonObject p = new JsonObject();
                p.addProperty("x", node[0]);
                p.addProperty("y", node[1]);
                p.addProperty("z", node[2]);
                sample.add(p);
            }
            out.add("sample", sample);
        } catch (Exception e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    private static JsonObject env(Minecraft mc) {
        Level level = mc.level;
        BlockPos p = mc.player.blockPosition();
        JsonObject out = new JsonObject();
        out.addProperty("answered", true);
        out.addProperty("dimension", level.dimension().location().toString());
        out.addProperty("dayTime", level.getDayTime() % 24000L);
        out.addProperty("night", level.isNight());
        out.addProperty("raining", level.isRaining());
        out.addProperty("thundering", level.isThundering());
        out.addProperty("light", level.getMaxLocalRawBrightness(p));
        out.addProperty("biome", level.getBiome(p).unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown"));
        out.addProperty("difficulty", level.getDifficulty().name());
        out.addProperty("gameTime", level.getGameTime());
        return out;
    }

    private static JsonObject vec(BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }
}
