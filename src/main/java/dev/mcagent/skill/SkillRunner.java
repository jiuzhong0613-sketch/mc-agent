package dev.mcagent.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcagent.AgentRuntime;

/**
 * Replays a stored action sequence. Steps run through the normal action queue,
 * so the same permission checks and timeouts apply.
 */
public final class SkillRunner {
    private static final int MAX_STEPS = 12;
    private static final long STEP_TIMEOUT_MS = 45_000;

    private SkillRunner() {
    }

    public static JsonObject run(JsonObject skill, JsonObject target) {
        JsonArray steps = skill.has("steps") ? skill.getAsJsonArray("steps") : null;
        if (steps == null || steps.isEmpty()) {
            return error("skill has no executable steps");
        }
        if (steps.size() > MAX_STEPS) {
            return error("skill has too many steps (" + steps.size() + " > " + MAX_STEPS + ")");
        }
        JsonObject anchor = skill.has("anchor") ? skill.getAsJsonObject("anchor") : null;
        double dx = 0;
        double dy = 0;
        double dz = 0;
        if (anchor != null) {
            if (target == null || !target.has("x") || !target.has("y") || !target.has("z")) {
                return error("anchored skill needs target x, y, z");
            }
            dx = target.get("x").getAsDouble() - anchor.get("x").getAsDouble();
            dy = target.get("y").getAsDouble() - anchor.get("y").getAsDouble();
            dz = target.get("z").getAsDouble() - anchor.get("z").getAsDouble();
        }

        JsonArray results = new JsonArray();
        for (int i = 0; i < steps.size(); i++) {
            JsonObject step = steps.get(i).getAsJsonObject();
            String action = step.get("action").getAsString();
            JsonObject args = step.has("args") ? step.getAsJsonObject("args").deepCopy() : new JsonObject();
            shift(args, dx, dy, dz);
            args.addProperty("type", action);
            try {
                JsonObject queued = AgentRuntime.get().submit(args);
                if (!queued.has("ok") || !queued.get("ok").getAsBoolean()) {
                    return fail(i, action, queued.has("error") ? queued.get("error").getAsString() : "rejected", results);
                }
                String id = queued.get("id").getAsString();
                if (!AgentRuntime.get().awaitResult(id, STEP_TIMEOUT_MS)) {
                    return fail(i, action, "timeout", results);
                }
                JsonObject result = AgentRuntime.get().resultFor(id);
                boolean ok = result != null && result.has("ok") && result.get("ok").getAsBoolean();
                JsonObject entry = new JsonObject();
                entry.addProperty("step", i);
                entry.addProperty("action", action);
                entry.addProperty("ok", ok);
                if (!ok && result != null && result.has("error")) {
                    entry.addProperty("error", result.get("error").getAsString());
                }
                results.add(entry);
                if (!ok) {
                    return fail(i, action, result != null && result.has("error")
                            ? result.get("error").getAsString() : "failed", results);
                }
            } catch (Exception e) {
                return fail(i, action, e.getMessage(), results);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("steps", results.size());
        out.add("results", results);
        return out;
    }

    private static JsonObject fail(int index, String action, String message, JsonArray results) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("failedStep", index);
        out.addProperty("failedAction", action);
        out.addProperty("error", message == null ? "failed" : message);
        out.add("results", results);
        return out;
    }

    private static void shift(JsonObject args, double dx, double dy, double dz) {
        shiftKey(args, "x", dx);
        shiftKey(args, "y", dy);
        shiftKey(args, "z", dz);
    }

    private static void shiftKey(JsonObject args, String key, double delta) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
            return;
        }
        double value = args.get(key).getAsDouble() + delta;
        if (isIntegral(args.get(key).getAsString())) {
            args.addProperty(key, (int) Math.round(value));
        } else {
            args.addProperty(key, value);
        }
    }

    private static boolean isIntegral(String raw) {
        return raw != null && !raw.contains(".") && !raw.contains("e") && !raw.contains("E");
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }
}
