package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mcagent.AgentConfig;
import dev.mcagent.AgentRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in agent: observation -> hosted LLM -> tool call -> action -> repeat.
 * No local models, no key exposure outside this process.
 */
public final class AgentLoop {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AgentLoop INSTANCE = new AgentLoop();
    private static final String SYSTEM = """
            You play Minecraft by calling tools.
            Rules:
            - Call one tool at a time and wait for its result before deciding the next step.
            - Only use coordinates and entity ids that appear in the state. Never invent them.
            - Interactions need to be within about 4.5 blocks; walk closer first.
            - If an action fails, change the approach instead of repeating it.
            - Call done when the goal is reached or clearly impossible.
            - Reply with tool calls only, no prose.
            """;

    private final LlmClient client = new LlmClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcagent-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String goal = "";
    private volatile String lastAction = "";
    private volatile String lastError = "";
    private volatile int step;

    private AgentLoop() {
    }

    public static AgentLoop get() {
        return INSTANCE;
    }

    public synchronized void start(String goal) {
        if (running.get()) {
            tell("already running; use /agent stop");
            return;
        }
        if (!SecretStore.present()) {
            tell("no API key. Set " + SecretStore.ENV + " or write " + SecretStore.file());
            return;
        }
        this.goal = goal;
        this.step = 0;
        this.lastAction = "";
        this.lastError = "";
        running.set(true);
        tell("started (model " + AgentConfig.MODEL.get() + ", max " + AgentConfig.MAX_STEPS.get() + " steps)");
        executor.submit(this::loop);
    }

    public void stop() {
        boolean was = running.getAndSet(false);
        AgentRuntime.get().cancelActions();
        tell(was ? "stopped" : "not running");
    }

    public String status() {
        return "running=" + running.get()
                + " step=" + step + "/" + AgentConfig.MAX_STEPS.get()
                + " model=" + AgentConfig.MODEL.get()
                + " key=" + (SecretStore.present() ? "set" : "missing")
                + " last=" + (lastAction.isEmpty() ? "-" : lastAction)
                + (lastError.isEmpty() ? "" : " error=" + lastError);
    }

    private void loop() {
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM));
        try {
            messages.add(message("user", "Goal: " + goal + "\nState:\n" + StateSummary.build()));
            while (running.get()) {
                int max = AgentConfig.MAX_STEPS.get();
                if (step >= max) {
                    finish("step limit " + max + " reached", true);
                    return;
                }
                step++;
                JsonObject reply = request(messages);
                messages.add(reply);
                JsonArray calls = toolCalls(reply);
                if (calls.isEmpty()) {
                    finish(text(reply), true);
                    return;
                }
                for (JsonElement element : calls) {
                    JsonObject call = element.getAsJsonObject();
                    String callId = call.get("id").getAsString();
                    JsonObject function = call.getAsJsonObject("function");
                    String name = function.get("name").getAsString();
                    JsonObject arguments = arguments(function);
                    if ("done".equals(name)) {
                        finish(arguments.has("summary") ? arguments.get("summary").getAsString() : "done", true);
                        return;
                    }
                    lastAction = name;
                    JsonObject result = execute(name, arguments);
                    messages.add(toolMessage(callId, result.toString()));
                }
                messages.add(message("user", "State now:\n" + StateSummary.build()));
                Thread.sleep(250);
            }
        } catch (LlmClient.LlmException e) {
            finish(friendly(e), false);
        } catch (Exception e) {
            LOGGER.warn("[mcagent] agent loop failed", e);
            finish("error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        } finally {
            running.set(false);
        }
    }

    private JsonObject request(JsonArray messages) throws Exception {
        try {
            return client.chat(messages, Tools.all());
        } catch (LlmClient.LlmException e) {
            if (e.status == 429 || e.status >= 500) {
                Thread.sleep(4000);
                return client.chat(messages, Tools.all());
            }
            throw e;
        }
    }

    private JsonObject execute(String name, JsonObject arguments) {
        arguments.addProperty("type", name);
        try {
            JsonObject queued = AgentRuntime.get().submit(arguments);
            if (!queued.has("ok") || !queued.get("ok").getAsBoolean()) {
                return error(queued.has("error") ? queued.get("error").getAsString() : "rejected");
            }
            String id = queued.get("id").getAsString();
            if (!AgentRuntime.get().awaitResult(id, 45_000)) {
                return error("timeout");
            }
            JsonObject result = AgentRuntime.get().resultFor(id);
            return result == null ? error("no result") : result;
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private static JsonArray toolCalls(JsonObject reply) {
        if (reply.has("tool_calls") && reply.get("tool_calls").isJsonArray()) {
            return reply.getAsJsonArray("tool_calls");
        }
        return new JsonArray();
    }

    private static JsonObject arguments(JsonObject function) {
        if (!function.has("arguments") || function.get("arguments").isJsonNull()) {
            return new JsonObject();
        }
        String raw = function.get("arguments").getAsString();
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject reply) {
        if (reply.has("content") && !reply.get("content").isJsonNull()) {
            return reply.get("content").getAsString();
        }
        return "done";
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject toolMessage(String callId, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "tool");
        message.addProperty("tool_call_id", callId);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message == null ? "unknown" : message);
        return out;
    }

    private static String friendly(LlmClient.LlmException e) {
        if (e.status == 401 || e.status == 403) {
            return "API key rejected (HTTP " + e.status + ")";
        }
        if (e.status == 429) {
            return "rate limited by provider";
        }
        return e.getMessage();
    }

    private void finish(String reason, boolean ok) {
        running.set(false);
        if (ok) {
            lastError = "";
            tell("done: " + reason);
        } else {
            lastError = reason;
            tell("stopped: " + reason);
        }
        LOGGER.info("[mcagent] agent finished: {}", reason);
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[agent] " + message), false);
            }
        });
    }
}
