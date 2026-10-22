package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mcagent.AgentConfig;
import dev.mcagent.memory.MemoryStore;
import org.slf4j.Logger;

/**
 * One extra model call after a failed run: turn the transcript into at most two
 * durable lessons. Failures here are ignored on purpose.
 */
public final class Reflector {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SYSTEM = """
            You review a failed Minecraft agent run.
            Output strict JSON only, no markdown:
            {"lessons":[{"text":"one generalizable sentence","tags":"comma,separated"}]}
            Rules:
            - at most 2 lessons;
            - each lesson must be actionable next time, not a restatement of the error;
            - avoid coordinates unless the location itself is the lesson;
            - do not blame the user.
            """;

    private Reflector() {
    }

    public static void reflect(String goal, String failure, JsonArray transcript) {
        if (!AgentConfig.REFLECT.get() || transcript == null || transcript.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Goal: ").append(goal).append('\n');
            sb.append("Failure: ").append(failure).append('\n');
            sb.append("Last steps:\n");
            int from = Math.max(0, transcript.size() - 10);
            for (int i = from; i < transcript.size(); i++) {
                sb.append("- ").append(transcript.get(i).toString()).append('\n');
            }
            JsonArray messages = new JsonArray();
            messages.add(message("system", SYSTEM));
            messages.add(message("user", sb.toString()));

            JsonObject reply = new LlmClient().chat(messages, null);
            String content = reply.has("content") && !reply.get("content").isJsonNull()
                    ? reply.get("content").getAsString() : "";
            JsonObject parsed = JsonParser.parseString(stripFences(content)).getAsJsonObject();
            JsonArray lessons = parsed.has("lessons") ? parsed.getAsJsonArray("lessons") : new JsonArray();
            for (JsonElement element : lessons) {
                JsonObject lesson = element.getAsJsonObject();
                String text = lesson.has("text") ? lesson.get("text").getAsString() : "";
                String tags = lesson.has("tags") ? lesson.get("tags").getAsString() : goal;
                MemoryStore.get().remember("lesson", text, tags);
            }
            MemoryStore.get().flush();
        } catch (Exception e) {
            LOGGER.debug("[mcagent] reflection skipped: {}", e.getMessage());
        }
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
