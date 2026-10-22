package dev.mcagent.query;

import com.google.gson.JsonObject;

/**
 * When an action fails, the framework asks the game a follow-up question
 * instead of handing the model a bare error string.
 */
public final class AutoProbe {
    private AutoProbe() {
    }

    public static JsonObject forFailure(String action, JsonObject args) {
        try {
            return switch (action) {
                case "walk_to" -> QueryRegistry.run("query_route", doubles(args), false);
                case "mine", "place" -> QueryRegistry.run("query_block", ints(args), false);
                case "attack" -> {
                    JsonObject probe = new JsonObject();
                    probe.addProperty("radius", 16);
                    yield QueryRegistry.run("query_entities", probe, false);
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject doubles(JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("x", args.has("x") ? args.get("x").getAsDouble() : 0);
        out.addProperty("y", args.has("y") ? args.get("y").getAsDouble() : 0);
        out.addProperty("z", args.has("z") ? args.get("z").getAsDouble() : 0);
        return out;
    }

    private static JsonObject ints(JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("x", args.has("x") ? args.get("x").getAsInt() : 0);
        out.addProperty("y", args.has("y") ? args.get("y").getAsInt() : 0);
        out.addProperty("z", args.has("z") ? args.get("z").getAsInt() : 0);
        return out;
    }
}
