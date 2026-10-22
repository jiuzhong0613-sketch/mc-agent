package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.mcagent.act.ActionQueue;
import dev.mcagent.act.Actions;
import dev.mcagent.bridge.BridgeServer;
import dev.mcagent.obs.Observation;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns the bridge, the action queue and the event ring. Driven from the client tick.
 */
public final class AgentRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AgentRuntime INSTANCE = new AgentRuntime();
    private static final int MAX_EVENTS = 40;

    private final ActionQueue queue = new ActionQueue();
    private final Deque<JsonObject> events = new ArrayDeque<>();
    private BridgeServer bridge;
    private int tick;
    private int eventSeq;
    private int retryAt;

    private AgentRuntime() {
    }

    public static AgentRuntime get() {
        return INSTANCE;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        tick++;

        if (!AgentConfig.ENABLED.get() || mc.player == null || mc.level == null) {
            stopBridge();
            return;
        }
        if (bridge == null && tick >= retryAt) {
            startBridge();
        }
        if (bridge != null) {
            queue.tick(mc);
        }
    }

    private void startBridge() {
        int port = AgentConfig.PORT.get();
        try {
            bridge = new BridgeServer(port);
            bridge.start();
            LOGGER.info("[mcagent] bridge listening on http://127.0.0.1:{}", port);
        } catch (Exception e) {
            retryAt = tick + 600;
            LOGGER.warn("[mcagent] bridge failed to start on port {}: {}", port, e.getMessage());
        }
    }

    private void stopBridge() {
        if (bridge == null) {
            return;
        }
        bridge.stop();
        bridge = null;
        queue.cancel(Minecraft.getInstance());
        LOGGER.info("[mcagent] bridge stopped");
    }

    public boolean running() {
        return bridge != null;
    }

    public int tickCount() {
        return tick;
    }

    public JsonObject state() throws Exception {
        return ClientThread.call(() -> Observation.snapshot(tick, eventsCopy()), 3000);
    }

    public JsonObject submit(JsonObject request) throws Exception {
        String type = request.has("type") ? request.get("type").getAsString() : "";
        if (type.isEmpty()) {
            throw new IllegalArgumentException("missing action type");
        }
        if ("stop".equals(type) || "cancel".equals(type)) {
            return ClientThread.call(() -> queue.cancel(Minecraft.getInstance()), 3000);
        }
        return queue.submit(Actions.create(type, request), AgentConfig.MAX_QUEUE.get());
    }

    public JsonObject lastResult() {
        return queue.lastResult();
    }

    public boolean awaitResult(String id, long timeoutMs) throws InterruptedException {
        return queue.awaitResult(id, timeoutMs);
    }

    public JsonObject resultFor(String id) {
        return queue.resultFor(id);
    }

    public void cancelActions() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> queue.cancel(mc));
    }

    public void addEvent(String type, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> addEvent(type, text));
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("seq", ++eventSeq);
        event.addProperty("type", type);
        event.addProperty("text", text);
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    private JsonArray eventsCopy() {
        JsonArray arr = new JsonArray();
        for (JsonObject event : events) {
            arr.add(event.deepCopy());
        }
        return arr;
    }
}
