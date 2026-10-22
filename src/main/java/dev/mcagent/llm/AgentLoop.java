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
import dev.mcagent.skill.SkillRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in agent: observation -> hosted LLM -> tool call -> action -> repeat.
 *
 * Tools are read-only queries, memory operations, executable skills and world
 * actions. Successful runs can be saved as skills; failed runs are reflected on.
 */
public final class AgentLoop {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AgentLoop INSTANCE = new AgentLoop();
    private static final int MAX_SKILL_STEPS = 12;
    private static final String SYSTEM = """
            You play Minecraft by calling tools.
            Rules:
            - Call one tool at a time and wait for its result before deciding the next step.
            - Only use coordinates and entity ids that appear in the state, a query result or memory.
            - The state is partial: limited block radius, loaded chunks only. If a fact is missing, call a query_* tool.
            - Before walking anywhere, call query_route. Do not assume terrain is passable.
            - Interactions need to be within about 4.5 blocks; walk closer first.
            - If an action fails, read the autoProbe field instead of retrying blindly.
            - Use remember for durable facts and lessons.
            - Use list_skills before long repetitive work; run_skill replays a stored sequence.
            - When you finish a repeatable task, pass a skill name to done so the sequence is saved.
            - If an action fails twice, change the approach or call done with the reason.
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
        JsonArray transcript = new JsonArray();
        JsonArray runSteps = new JsonArray();
        boolean usedSkill = false;
        messages.add(message("system", SYSTEM));
        try {
            messages.add(message("user", "Goal: " + goal
                    + "\n\nMemory:\n" + memory.brief(goal)
                    + "\n\nState:\n" + StateSummary.build()));
            while (running.get()) {
                int max = AgentConfig.MAX_STEPS.get();
                if (step >= max) {
                    finish("step limit " + max + " reached", false, transcript);
                    return;
                }
                step++;
                QueryRegistry.resetStepBudget();
                JsonObject reply = request(messages);
                messages.add(reply);
                JsonArray calls = toolCalls(reply);
                if (calls.isEmpty()) {
                    finish(text(reply), true, transcript);
                    return;
                }
                for (JsonElement element : calls) {
                    JsonObject call = element.getAsJsonObject();
                    String callId = call.get("id").getAsString();
                    JsonObject function = call.getAsJsonObject("function");
                    String name = function.get("name").getAsString();
                    JsonObject arguments = arguments(function);

                    if (name.startsWith("query_")) {
                        JsonObject answer = query(name, arguments);
                        transcript.add(transcriptEntry(name, answer));
                        messages.add(toolMessage(callId, answer.toString()));
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
                    if ("list_skills".equals(name)) {
                        JsonObject out = new JsonObject();
                        out.addProperty("ok", true);
                        out.add("skills", memory.listSkills());
                        messages.add(toolMessage(callId, out.toString()));
                        continue;
                    }
                    if ("run_skill".equals(name)) {
                        JsonObject result = runSkill(arguments);
                        usedSkill = true;
                        transcript.add(transcriptEntry(name, result));
                        messages.add(toolMessage(callId, result.toString()));
                        continue;
                    }
                    if ("done".equals(name)) {
                        if (arguments.has("lesson") && !arguments.get("lesson").getAsString().isBlank()) {
                            memory.remember("lesson", arguments.get("lesson").getAsString(), goal);
                        }
                        saveSkill(arguments, runSteps, usedSkill);
                        finish(arguments.has("summary") ? arguments.get("summary").getAsString() : "done", true, transcript);
                        return;
                    }

                    lastAction = name;
                    JsonObject result = execute(name, arguments);
                    memory.recordEpisode(name, arguments, result);
                    transcript.add(transcriptEntry(name, result));
                    boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
                    if (ok) {
                        addSkillStep(runSteps, name, arguments);
                    } else {
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
            finish(friendly(e), false, transcript);
        } catch (Exception e) {
            LOGGER.warn("[mcagent] agent loop failed", e);
            finish("error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false, transcript);
        } finally {
            running.set(false);
            memory.flush();
        }
    }

    private void saveSkill(JsonObject arguments, JsonArray runSteps, boolean usedSkill) {
        if (usedSkill || runSteps.size() < 2 || runSteps.size() > MAX_SKILL_STEPS) {
            return;
        }
        String name;
        if (arguments.has("skill") && !arguments.get("skill").getAsString().isBlank()) {
            name = slug(arguments.get("skill").getAsString());
        } else {
            name = "skill_" + (System.currentTimeMillis() / 1000L % 100000L);
        }
        memory.saveSkill(name, runSteps, goal);
        tell("saved skill " + name + " (" + runSteps.size() + " steps)");
    }

    private static void addSkillStep(JsonArray runSteps, String name, JsonObject arguments) {
        if (runSteps.size() >= MAX_SKILL_STEPS || !isWorldAction(name)) {
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("action", name);
        JsonObject args = arguments.deepCopy();
        args.remove("type");
        step.add("args", args);
        runSteps.add(step);
    }

    private static boolean isWorldAction(String name) {
        return !name.startsWith("query_")
                && !"remember".equals(name)
                && !"recall".equals(name)
                && !"done".equals(name)
                && !"run_skill".equals(name)
                && !"list_skills".equals(name);
    }

    private static String slug(String raw) {
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return clean.length() > 32 ? clean.substring(0, 32) : (clean.isEmpty() ? "skill" : clean);
    }

    private JsonObject runSkill(JsonObject arguments) {
        String name = arguments.has("name") ? arguments.get("name").getAsString() : "";
        JsonObject skill = memory.findSkill(name);
        if (skill == null) {
            return error("unknown skill: " + name);
        }
        if (!skill.has("steps")) {
            return error("skill has no stored steps (text-only entry)");
        }
        JsonObject target = arguments.has("x") && arguments.has("y") && arguments.has("z")
                ? arguments : null;
        JsonObject result = SkillRunner.run(skill, target);
        memory.noteSkill(name, result.has("ok") && result.get("ok").getAsBoolean());
        return result;
    }

    private static JsonObject transcriptEntry(String action, JsonObject result) {
        JsonObject entry = new JsonObject();
        entry.addProperty("action", action);
        boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
        entry.addProperty("ok", ok);
        if (!ok && result.has("error")) {
            entry.addProperty("error", result.get("error").getAsString());
        }
        return entry;
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

    private void finish(String reason, boolean ok, JsonArray transcript) {
        running.set(false);
        if (ok) {
            lastError = "";
            tell("done: " + reason);
        } else {
            lastError = reason;
            tell("stopped: " + reason);
            Reflector.reflect(goal, reason, transcript);
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
