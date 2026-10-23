package dev.mcagent.act;

import com.google.gson.JsonObject;
import dev.mcagent.AgentConfig;
import dev.mcagent.path.AStar;
import dev.mcagent.path.Path;
import dev.mcagent.path.PathService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Plans with A* and follows the path. Aborts on water, long falls and being stuck.
 */
public final class PathFollowerAction implements Action {
    private final int gx;
    private final int gy;
    private final int gz;
    private final int radius;
    private final double tolerance;

    private CompletableFuture<Path> future;
    private List<int[]> nodes;
    private int index;
    private int ticks;
    private int stuckTicks;
    private double lastX;
    private double lastZ;

    public PathFollowerAction(JsonObject req) {
        this.gx = req.get("x").getAsInt();
        this.gy = req.get("y").getAsInt();
        this.gz = req.get("z").getAsInt();
        this.radius = Math.max(8, Math.min(48,
                req.has("radius") ? req.get("radius").getAsInt() : PathService.DEFAULT_RADIUS));
        this.tolerance = req.has("tolerance") ? req.get("tolerance").getAsDouble() : 0.8;
    }

    @Override
    public String type() {
        return "path_to";
    }

    @Override
    public int timeoutTicks() {
        return 4000;
    }

    @Override
    public JsonObject tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player.isInWater()) {
            Actions.releaseKeys(mc);
            throw new IllegalStateException("entered water");
        }
        if (player.fallDistance > 3.5f) {
            Actions.releaseKeys(mc);
            throw new IllegalStateException("falling too far");
        }
        if (future == null) {
            future = PathService.planAsync(gx, gy, gz, radius);
            return null;
        }
        if (!future.isDone()) {
            if (++ticks > 200) {
                throw new IllegalStateException("planning timeout");
            }
            return null;
        }
        if (nodes == null) {
            Path path;
            try {
                path = future.join();
            } catch (Exception e) {
                throw new IllegalStateException("path failed: " + e.getMessage());
            }
            if (!path.reachable()) {
                JsonObject error = new JsonObject();
                error.addProperty("status", path.status.name());
                error.addProperty("length", path.nodes.size());
                throw new IllegalStateException(path.status + ": " + path.reason);
            }
            nodes = path.nodes;
            index = 1;
            ticks = 0;
            lastX = player.getX();
            lastZ = player.getZ();
        }
        if (index >= nodes.size()) {
            Actions.releaseKeys(mc);
            double dist = Math.hypot(player.getX() - (gx + 0.5), player.getZ() - (gz + 0.5));
            JsonObject data = new JsonObject();
            data.addProperty("nodes", nodes.size());
            data.addProperty("distance", Math.round(dist * 1000.0) / 1000.0);
            data.addProperty("ticks", ticks);
            if (dist > tolerance + 1.0) {
                throw new IllegalStateException("stopped " + Math.round(dist * 100.0) / 100.0 + " blocks away");
            }
            return data;
        }

        int[] node = nodes.get(index);
        double tx = node[0] + 0.5;
        double tz = node[2] + 0.5;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.75 && Math.abs(player.getY() - node[1]) < 1.2) {
            index++;
            ticks++;
            return null;
        }

        aim(mc, tx, player.getY() + 1.6, tz);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(horizontal > 3.0);
        if (node[1] > player.getY() + 0.4 || player.horizontalCollision) {
            mc.options.keyJump.setDown(true);
        } else if (ticks % 5 == 0) {
            mc.options.keyJump.setDown(false);
        }

        ticks++;
        if (ticks % 10 == 0) {
            double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
            if (moved < 0.15) {
                stuckTicks += 10;
                if (stuckTicks >= 30) {
                    Actions.releaseKeys(mc);
                    throw new IllegalStateException("stuck at " + Math.round(player.getX()) + ","
                            + Math.round(player.getY()) + "," + Math.round(player.getZ()));
                }
            } else {
                stuckTicks = 0;
            }
            lastX = player.getX();
            lastZ = player.getZ();
        }
        if (ticks > nodes.size() * 30 + 200) {
            Actions.releaseKeys(mc);
            throw new IllegalStateException("path timeout");
        }
        return null;
    }

    private static void aim(Minecraft mc, double x, double y, double z) {
        LocalPlayer player = mc.player;
        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        float max = (float) (double) AgentConfig.TURN_SPEED.get();
        float deltaYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
        float deltaPitch = targetPitch - player.getXRot();
        player.setYRot(player.getYRot() + Mth.clamp(deltaYaw, -max, max));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(deltaPitch, -max, max), -90.0f, 90.0f));
    }

    @Override
    public void cancel(Minecraft mc) {
        Actions.releaseKeys(mc);
    }
}
