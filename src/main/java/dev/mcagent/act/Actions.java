package dev.mcagent.act;

import com.google.gson.JsonObject;
import dev.mcagent.AgentConfig;
import dev.mcagent.bridge.Json;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Action factory plus the primitive implementations.
 *
 * Rule of thumb: primitives are tick-driven, never blocking. They are allowed to
 * finish early and must leave keys released when they do.
 */
public final class Actions {
    private Actions() {
    }

    public static Action create(String type, JsonObject req) {
        return switch (type) {
            case "look" -> new Look(req);
            case "look_at" -> new LookAt(req);
            case "move" -> new Move(req);
            case "walk_to" -> new WalkTo(req);
            case "mine" -> new Mine(req);
            case "place" -> new Place(req);
            case "use" -> new Use(req);
            case "attack" -> new Attack(req);
            case "select_slot" -> new SelectSlot(req);
            case "chat" -> new Chat(req);
            case "drop" -> new Drop(req);
            case "respawn" -> new Respawn();
            case "wait" -> new Wait(req);
            default -> throw new IllegalArgumentException("unknown action: " + type);
        };
    }

    static void releaseKeys(Minecraft mc) {
        if (mc.options == null) {
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static double reqDouble(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static int reqInt(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    private static boolean reqBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static String reqString(JsonObject o, String key) {
        if (!o.has(key)) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return o.get(key).getAsString();
    }

    private static BlockPos reqPos(JsonObject o) {
        return new BlockPos(reqInt(o, "x", 0), reqInt(o, "y", 0), reqInt(o, "z", 0));
    }

    private static void checkReach(LocalPlayer player, Vec3 target) {
        double max = AgentConfig.ACTION_RADIUS.get();
        if (player.getEyePosition().distanceTo(target) > max) {
            throw new IllegalStateException("out of reach (max " + max + ")");
        }
    }

    private static boolean face(Minecraft mc, double x, double y, double z) {
        LocalPlayer p = mc.player;
        double dx = x - p.getX();
        double dy = y - p.getEyeY();
        double dz = z - p.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return aim(mc, yaw, pitch);
    }

    /**
     * Rotate toward the target at a human-like speed instead of snapping.
     * Returns true when the camera is aligned within one step.
     */
    private static boolean aim(Minecraft mc, float targetYaw, float targetPitch) {
        LocalPlayer p = mc.player;
        float max = (float) (double) AgentConfig.TURN_SPEED.get();
        float deltaYaw = Mth.wrapDegrees(targetYaw - p.getYRot());
        float deltaPitch = targetPitch - p.getXRot();
        p.setYRot(p.getYRot() + Mth.clamp(deltaYaw, -max, max));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(deltaPitch, -max, max), -90.0f, 90.0f));
        return Math.abs(deltaYaw) <= max && Math.abs(deltaPitch) <= max;
    }

    private static JsonObject done() {
        return new JsonObject();
    }

    static final class Look implements Action {
        private final float yaw;
        private final float pitch;

        Look(JsonObject req) {
            this.yaw = (float) reqDouble(req, "yaw", 0);
            this.pitch = (float) reqDouble(req, "pitch", 0);
        }

        @Override
        public String type() {
            return "look";
        }

        @Override
        public int timeoutTicks() {
            return 30;
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_MOVE.get()) {
                throw new IllegalStateException("movement disabled by config");
            }
            return aim(mc, yaw, pitch) ? done() : null;
        }
    }

    static final class LookAt implements Action {
        private final double x;
        private final double y;
        private final double z;

        LookAt(JsonObject req) {
            this.x = reqDouble(req, "x", 0);
            this.y = reqDouble(req, "y", 0);
            this.z = reqDouble(req, "z", 0);
        }

        @Override
        public String type() {
            return "look_at";
        }

        @Override
        public int timeoutTicks() {
            return 40;
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            return face(mc, x, y, z) ? done() : null;
        }
    }

    static final class Move implements Action {
        private final double forward;
        private final double strafe;
        private final boolean jump;
        private final boolean sneak;
        private final boolean sprint;
        private final int duration;
        private int ticks;

        Move(JsonObject req) {
            this.forward = reqDouble(req, "forward", 0);
            this.strafe = reqDouble(req, "strafe", 0);
            this.jump = reqBool(req, "jump", false);
            this.sneak = reqBool(req, "sneak", false);
            this.sprint = reqBool(req, "sprint", false);
            this.duration = Math.max(1, reqInt(req, "ticks", 20));
        }

        @Override
        public String type() {
            return "move";
        }

        @Override
        public int timeoutTicks() {
            return duration + 20;
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_MOVE.get()) {
                throw new IllegalStateException("movement disabled by config");
            }
            if (ticks == 0) {
                mc.options.keyUp.setDown(forward > 0.01);
                mc.options.keyDown.setDown(forward < -0.01);
                mc.options.keyRight.setDown(strafe > 0.01);
                mc.options.keyLeft.setDown(strafe < -0.01);
                mc.options.keyJump.setDown(jump);
                mc.options.keyShift.setDown(sneak);
                mc.options.keySprint.setDown(sprint);
            }
            ticks++;
            if (ticks >= duration) {
                releaseKeys(mc);
                JsonObject data = done();
                data.addProperty("ticks", ticks);
                return data;
            }
            return null;
        }

        @Override
        public void cancel(Minecraft mc) {
            releaseKeys(mc);
        }
    }

    static final class WalkTo implements Action {
        private final double x;
        private final double y;
        private final double z;
        private final double tolerance;
        private final boolean sprint;
        private int ticks;
        private double best = Double.MAX_VALUE;
        private int stale;

        WalkTo(JsonObject req) {
            this.x = reqDouble(req, "x", 0);
            this.y = reqDouble(req, "y", 0);
            this.z = reqDouble(req, "z", 0);
            this.tolerance = Math.max(0.3, reqDouble(req, "tolerance", 0.8));
            this.sprint = reqBool(req, "sprint", true);
        }

        @Override
        public String type() {
            return "walk_to";
        }

        @Override
        public int timeoutTicks() {
            return 600;
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_MOVE.get()) {
                throw new IllegalStateException("movement disabled by config");
            }
            LocalPlayer p = mc.player;
            ticks++;
            double dx = x - p.getX();
            double dz = z - p.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double vertical = Math.abs(y - p.getY());

            if (horizontal <= tolerance && vertical <= 1.5) {
                releaseKeys(mc);
                JsonObject data = done();
                data.addProperty("distance", Json.round(horizontal));
                data.addProperty("ticks", ticks);
                return data;
            }

            if (horizontal < best - 0.05) {
                best = horizontal;
                stale = 0;
            } else if (++stale > 80) {
                releaseKeys(mc);
                throw new IllegalStateException("stuck at distance " + Json.round(horizontal));
            }

            face(mc, x, p.getY() + 1.6, z);
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(sprint);
            if (p.horizontalCollision) {
                mc.options.keyJump.setDown(true);
            } else if (ticks % 10 == 0) {
                mc.options.keyJump.setDown(false);
            }
            return null;
        }

        @Override
        public void cancel(Minecraft mc) {
            releaseKeys(mc);
        }
    }

    static final class Mine implements Action {
        private final BlockPos pos;
        private boolean started;
        private int ticks;

        Mine(JsonObject req) {
            this.pos = reqPos(req);
        }

        @Override
        public String type() {
            return "mine";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_BREAK.get()) {
                throw new IllegalStateException("breaking disabled by config");
            }
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) {
                JsonObject data = done();
                data.addProperty("broken", true);
                data.addProperty("ticks", ticks);
                return data;
            }
            checkReach(mc.player, Vec3.atCenterOf(pos));
            if (!started) {
                started = true;
                mc.gameMode.startDestroyBlock(pos, Direction.UP);
            } else {
                mc.gameMode.continueDestroyBlock(pos, Direction.UP);
            }
            ticks++;
            return null;
        }

        @Override
        public void cancel(Minecraft mc) {
            mc.gameMode.stopDestroyBlock();
        }

        @Override
        public int timeoutTicks() {
            return 600;
        }
    }

    static final class Place implements Action {
        private final BlockPos pos;
        private final Direction face;
        private boolean used;

        Place(JsonObject req) {
            this.pos = reqPos(req);
            this.face = req.has("face") ? Direction.valueOf(reqString(req, "face").toUpperCase()) : Direction.UP;
        }

        @Override
        public String type() {
            return "place";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_PLACE.get()) {
                throw new IllegalStateException("placing disabled by config");
            }
            BlockPos adjacent = pos.relative(face);
            checkReach(mc.player, Vec3.atCenterOf(adjacent));
            if (!used) {
                used = true;
                Vec3 hit = Vec3.atCenterOf(adjacent).add(
                        face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                BlockHitResult result = new BlockHitResult(hit, face.getOpposite(), adjacent, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, result);
            }
            JsonObject data = done();
            data.addProperty("target", pos.toShortString());
            return data;
        }
    }

    static final class Use implements Action {
        private final InteractionHand hand;

        Use(JsonObject req) {
            this.hand = "offhand".equalsIgnoreCase(req.has("hand") ? reqString(req, "hand") : "main")
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }

        @Override
        public String type() {
            return "use";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            mc.gameMode.useItem(mc.player, hand);
            return done();
        }
    }

    static final class Attack implements Action {
        private final int entityId;
        private boolean attacked;

        Attack(JsonObject req) {
            this.entityId = reqInt(req, "entity", -1);
        }

        @Override
        public String type() {
            return "attack";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_ATTACK.get()) {
                throw new IllegalStateException("attack disabled by config");
            }
            Entity target = null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getId() == entityId) {
                    target = e;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalStateException("entity not found: " + entityId);
            }
            checkReach(mc.player, target.position().add(0, target.getBbHeight() * 0.5, 0));
            if (!attacked) {
                attacked = true;
                face(mc, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
                mc.gameMode.attack(mc.player, target);
            }
            JsonObject data = done();
            if (target instanceof LivingEntity living) {
                data.addProperty("targetHealth", Json.round(living.getHealth()));
            }
            return data;
        }
    }

    static final class SelectSlot implements Action {
        private final int slot;

        SelectSlot(JsonObject req) {
            this.slot = reqInt(req, "slot", 0);
        }

        @Override
        public String type() {
            return "select_slot";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException("slot must be 0..8");
            }
            mc.player.getInventory().selected = slot;
            return done();
        }
    }

    static final class Chat implements Action {
        private final String message;

        Chat(JsonObject req) {
            this.message = reqString(req, "text");
        }

        @Override
        public String type() {
            return "chat";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (!AgentConfig.ALLOW_CHAT.get()) {
                throw new IllegalStateException("chat disabled by config");
            }
            if (message.length() > 256) {
                throw new IllegalArgumentException("message too long");
            }
            mc.player.connection.sendChat(message);
            return done();
        }
    }

    static final class Drop implements Action {
        private final boolean all;

        Drop(JsonObject req) {
            this.all = reqBool(req, "all", false);
        }

        @Override
        public String type() {
            return "drop";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            mc.player.drop(all);
            return done();
        }
    }

    static final class Respawn implements Action {
        @Override
        public String type() {
            return "respawn";
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            if (mc.player.isDeadOrDying()) {
                mc.player.respawn();
            }
            return done();
        }
    }

    static final class Wait implements Action {
        private final int duration;
        private int ticks;

        Wait(JsonObject req) {
            this.duration = Math.max(1, reqInt(req, "ticks", 20));
        }

        @Override
        public String type() {
            return "wait";
        }

        @Override
        public int timeoutTicks() {
            return duration + 20;
        }

        @Override
        public JsonObject tick(Minecraft mc) {
            ticks++;
            if (ticks >= duration) {
                JsonObject data = done();
                data.addProperty("ticks", ticks);
                return data;
            }
            return null;
        }
    }
}
