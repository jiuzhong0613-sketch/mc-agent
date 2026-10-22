package dev.mcagent.query;

import com.google.gson.JsonObject;
import dev.mcagent.AgentConfig;
import dev.mcagent.AgentRuntime;
import dev.mcagent.ClientThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only queries the model (or an external client) can ask the game.
 *
 * Queries never change the world, so they are cheap to call between actions.
 * Results are cached for a few ticks to make repeated questions free.
 */
public final class QueryRegistry {
    private static final int MAX_PER_STEP_DEFAULT = 6;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger STEP_QUERIES = new AtomicInteger();

    private record Cached(JsonObject value, int tick, int ttl) {
    }

    private QueryRegistry() {
    }

    public static void resetStepBudget() {
        STEP_QUERIES.set(0);
    }

    public static JsonObject run(String name, JsonObject args, boolean enforceBudget) throws Exception {
        return ClientThread.call(() -> {
            String key = name + "|" + args;
            int now = AgentRuntime.get().tickCount();
            Cached cached = CACHE.get(key);
            if (cached != null && now - cached.tick <= cached.ttl) {
                return cached.value.deepCopy();
            }
            if (enforceBudget) {
                int max = AgentConfig.MAX_QUERIES.get();
                if (STEP_QUERIES.incrementAndGet() > max) {
                    throw new IllegalStateException("query budget exhausted (" + max + " per step)");
                }
            }
            JsonObject value = BuiltinQueries.dispatch(name, args);
            int ttl = BuiltinQueries.ttl(name);
            CACHE.put(key, new Cached(value.deepCopy(), now, ttl));
            if (CACHE.size() > 256) {
                CACHE.clear();
            }
            return value;
        }, 2500);
    }

    public static int maxPerStep() {
        return MAX_PER_STEP_DEFAULT;
    }
}
