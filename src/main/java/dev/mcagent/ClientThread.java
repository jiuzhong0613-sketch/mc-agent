package dev.mcagent;

import net.minecraft.client.Minecraft;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Game state may only be touched from the client thread. HTTP handlers are not on it.
 */
public final class ClientThread {
    private ClientThread() {
    }

    public static <T> T call(Callable<T> task, long timeoutMs) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return task.call();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("client thread did not respond in " + timeoutMs + "ms");
        }
    }
}
