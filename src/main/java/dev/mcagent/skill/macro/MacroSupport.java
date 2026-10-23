package dev.mcagent.skill.macro;

import com.google.gson.JsonObject;
import dev.mcagent.AgentRuntime;
import dev.mcagent.query.QueryRegistry;

final class MacroSupport {
    private MacroSupport() {
    }

    static JsonObject action(String type, JsonObject args) throws Exception {
        JsonObject request = args.deepCopy();
        request.addProperty("type", type);
        JsonObject queued = AgentRuntime.get().submit(request);
        if (!queued.has("ok") || !queued.get("ok").getAsBoolean()) {
            return queued;
        }
        String id = queued.get("id").getAsString();
        if (!AgentRuntime.get().awaitResult(id, 60_000)) {
            return error("timeout");
        }
        JsonObject result = AgentRuntime.get().resultFor(id);
        return result == null ? error("no result") : result;
    }

    static JsonObject query(String name, JsonObject args) throws Exception {
        return QueryRegistry.run(name, args, false);
    }

    static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    static boolean ok(JsonObject result) {
        return result != null && result.has("ok") && result.get("ok").getAsBoolean();
    }

    static double[] point(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
