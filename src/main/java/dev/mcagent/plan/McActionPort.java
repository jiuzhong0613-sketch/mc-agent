package dev.mcagent.plan;

import com.google.gson.JsonObject;
import dev.mcagent.AgentRuntime;
import dev.mcagent.memory.MemoryStore;
import dev.mcagent.query.QueryRegistry;
import dev.mcagent.skill.SkillRunner;
import dev.mcagent.skill.macro.GotoMacro;
import dev.mcagent.skill.macro.MineNMacro;

public final class McActionPort implements ActionPort {
    @Override
    public JsonObject runAction(String type, JsonObject args) throws Exception {
        JsonObject request = args.deepCopy();
        request.addProperty("type", type);
        JsonObject queued = AgentRuntime.get().submit(request);
        if (!queued.has("ok") || !queued.get("ok").getAsBoolean()) {
            return queued;
        }
        String id = queued.get("id").getAsString();
        if (!AgentRuntime.get().awaitResult(id, 60_000)) {
            JsonObject timeout = new JsonObject();
            timeout.addProperty("ok", false);
            timeout.addProperty("error", "timeout");
            return timeout;
        }
        JsonObject result = AgentRuntime.get().resultFor(id);
        return result == null ? error("no result") : result;
    }

    @Override
    public JsonObject runQuery(String name, JsonObject args) throws Exception {
        return QueryRegistry.run(name, args, false);
    }

    @Override
    public JsonObject runSkill(String name, JsonObject args) throws Exception {
        JsonObject skill = MemoryStore.get().findSkill(name);
        if (skill == null) {
            return error("unknown skill: " + name);
        }
        JsonObject target = args.has("x") && args.has("y") && args.has("z") ? args : null;
        return SkillRunner.run(skill, target);
    }

    @Override
    public JsonObject runMacro(String name, JsonObject args) {
        return switch (name) {
            case "mine_n" -> MineNMacro.run(args);
            case "goto" -> GotoMacro.run(args);
            default -> error("unknown macro: " + name);
        };
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }
}
