package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mcagent.AgentConfig;
import dev.mcagent.AgentRuntime;
import dev.mcagent.memory.MemoryStore;
import dev.mcagent.memory.MemoryTools;
import dev.mcagent.query.AutoProbe;
import dev.mcagent.query.QueryRegistry;
import dev.mcagent.query.QueryTools;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in agent: observation -> hosted LLM -> tool call -> action -> repeat.
 *
 * Tools are split into read-only queries, memory operations and world actions.
 * Failed actions automatically trigger a follow-up probe.
 */
public final class AgentLoop {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AgentLoop INSTANCE = new AgentLoop();
    private static final String SYSTEM = """
            You play Minecraft by calling tools.
            Rules:
            - Call one tool at a time and wait for its result before deciding the next step.
            - Only use coordinates and entity ids that appear in the state, a query result or memory.
            - The state is partial: limited block radius, loaded chunks only. If a fact is missing, call a query_* tool.
            - Before walking anywhere, call query_route. Do not assume terrain is passable.
            - Interactions need to be within about 4.5 blocks; walk closer first.
            - If an action fails, read the autoProbe field instead of retrying blindly.
            - Use remember for durable facts (resource locations, hazards, landmarks) and lessons.
            - Use recall before exploring an area you may have seen before.
            - If an action fails twice, change the approach or call done with the reason.
            - Call done when the goal is reached or clearly impossible. Include a lesson when you learned something.
            - Reply with tool calls only, no prose.
            """;

    private final LlmClient client = new LlmClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcagent-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final MemoryStore memory = MemoryStore.get();
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
        memory.flush();
        tell(was ? "stopped" : "not running");
    }

    public String status() {
        return "running=" + running.get()
                + " step=" + step + "/" + AgentConfig.MAX_STEPS.get()
                + " model=" + AgentConfig.MODEL.get()
                + " key=" + (SecretStore.present() ? "set" : "missing")
                + " memory=" + memory.worldKey()
                + " last=" + (lastAction.isEmpty() ? "-" : lastAction)
                + (lastError.isEmpty() ? "" : " error=" + lastError);
    }

    private void loop() {
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM));
        try {
            messages.add(message("user", "Goal: " + goal
                    + "\n\nMemory:\n" + memory.brief(goal)
                    + "\n\nState:\n" + StateSummary.build()));
            while (running.get()) {
                int max = AgentConfig.MAX_STEPS.get();
                if (step >= max) {
                    finish("step limit " + max + " reached", true);
                    return;
                }
                step++;
                QueryRegistry.resetStepBudget();
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

                    if (name.startsWith("query_")) {
                        messages.add(toolMessage(callId, query(name, arguments).toString()));
                        continue;
                    }
                    if ("remember".equals(name)) {
                        messages.add(toolMessage(callId, remember(arguments).toString()));
                        continue;
                    }
                    if ("recall".equals(name)) {
                        messages.add(toolMessage(callId, recall(arguments).toString()));
                        continue;
                    }
                    if ("done".equals(name)) {
                        if (arguments.has("lesson") && !arguments.get("lesson").getAsString().isBlank()) {
                            memory.remember("lesson", arguments.get("lesson").getAsString(), goal);
                        }
                        finish(arguments.has("summary") ? arguments.get("summary").getAsString() : "done", true);
                        return;
                    }

                    lastAction = name;
                    JsonObject result = execute(name, arguments);
                    memory.recordEpisode(name, arguments, result);
                    if (!result.has("ok") || !result.get("ok").getAsBoolean()) {
                        JsonObject probe = AutoProbe.forFailure(name, arguments);
                        if (probe != null) {
                            result.add("autoProbe", probe);
                        }
                    }
                    messages.add(toolMessage(callId, result.toString()));
                }
                messages.add(message("user", "State now:\n" + StateSummary.build()
                        + "\nMemory hint: call recall if you need earlier facts."));
                memory.flushIfDirty();
                if (AgentConfig.STEP_DELAY.get() > 0) {
                    Thread.sleep(AgentConfig.STEP_DELAY.get());
                }
            }
        } catch (LlmClient.LlmException e) {
            finish(friendly(e), false);
        } catch (Exception e) {
            LOGGER.warn("[mcagent] agent loop failed", e);
            finish("error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        } finally {
            running.set(false);
            memory.flush();
        }
    }

    private static JsonObject query(String name, JsonObject arguments) {
        try {
            return QueryRegistry.run(name, arguments, true);
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private static JsonObject remember(JsonObject arguments) {
        String text = arguments.has("text") ? arguments.get("text").getAsString() : "";
        String kind = arguments.has("kind") ? arguments.get("kind").getAsString() : "fact";
        String tags = arguments.has("tags") ? arguments.get("tags").getAsString() : "";
        MemoryStore.get().remember(kind, text, tags);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("stored", kind);
        return out;
    }

    private static JsonObject recall(JsonObject arguments) {
        String query = arguments.has("query") ? arguments.get("query").getAsString() : "";
        int limit = arguments.has("limit") ? Math.min(12, arguments.get("limit").getAsInt()) : 6;
        return MemoryStore.get().recall(query, limit);
    }

    private JsonObject request(JsonArray messages) throws Exception {
        try {
            return client.chat(messages, allTools());
        } catch (LlmClient.LlmException e) {
            if (e.status == 429 || e.status >= 500) {
                Thread.sleep(4000);
                return client.chat(messages, allTools());
            }
            throw e;
        }
    }

    private static JsonArray allTools() {
        JsonArray tools = new JsonArray();
        tools.addAll(QueryTools.all());
        tools.addAll(MemoryTools.all());
        tools.addAll(Tools.all());
        return tools;
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
        memory.flush();
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
