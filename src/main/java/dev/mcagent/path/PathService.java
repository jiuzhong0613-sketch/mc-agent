package dev.mcagent.path;

import dev.mcagent.ClientThread;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Snapshot on the client thread, search on a worker thread.
 */
public final class PathService {
    public static final int DEFAULT_RADIUS = 32;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcagent-path");
        thread.setDaemon(true);
        return thread;
    });

    private PathService() {
    }

    public static CompletableFuture<Path> planAsync(int gx, int gy, int gz, int radius) {
        CompletableFuture<Path> out = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                BlockGrid grid = GridSnapshot.capture(radius, 8);
                int sx = (int) Math.floor(mc.player.getX());
                int sy = (int) Math.floor(mc.player.getY());
                int sz = (int) Math.floor(mc.player.getZ());
                WORKER.submit(() -> {
                    try {
                        out.complete(AStar.search(grid, sx, sy, sz, gx, gy, gz, AStar.DEFAULT_MAX_EXPANDED));
                    } catch (Throwable t) {
                        out.completeExceptionally(t);
                    }
                });
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    public static Path planNow(int gx, int gy, int gz, int radius) throws Exception {
        return ClientThread.call(() -> {
            BlockGrid grid = GridSnapshot.capture(radius, 8);
            Minecraft mc = Minecraft.getInstance();
            int sx = (int) Math.floor(mc.player.getX());
            int sy = (int) Math.floor(mc.player.getY());
            int sz = (int) Math.floor(mc.player.getZ());
            return AStar.search(grid, sx, sy, sz, gx, gy, gz, AStar.DEFAULT_MAX_EXPANDED);
        }, 6000);
    }
}
