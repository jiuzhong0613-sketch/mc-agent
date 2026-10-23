package dev.mcagent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Evaluates verify predicates against real game facts, never against model text.
 */
public final class Verifier {
    public static final class Result {
        public final boolean ok;
        public final String type;
        public final String expected;
        public final String actual;

        public Result(boolean ok, String type, String expected, String actual) {
            this.ok = ok;
            this.type = type;
            this.expected = expected;
            this.actual = actual;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("type", type);
            o.addProperty("expected", expected);
            o.addProperty("actual", actual);
            return o;
        }
    }

    private Verifier() {
    }

    public static Result verify(JsonObject spec, FactSource facts, JsonObject lastResult) {
        if (spec == null || spec.size() == 0) {
            return new Result(true, "none", "", "");
        }
        String type = spec.has("type") ? spec.get("type").getAsString() : "";
        try {
            return switch (type) {
                case "inventory_count" -> inventory(spec, facts);
                case "block_at" -> blockAt(spec, facts);
                case "blocks_count" -> blocksCount(spec, facts);
                case "position_near" -> positionNear(spec, facts);
                case "entity_near" -> entityNear(spec, facts);
                case "action_ok" -> actionOk(lastResult);
                case "query_true" -> queryTrue(spec, facts);
                case "all" -> combine(spec, facts, lastResult, true);
                case "any" -> combine(spec, facts, lastResult, false);
                case "not" -> not(spec, facts, lastResult);
                default -> new Result(false, type, "known predicate", "unknown predicate");
            };
        } catch (Exception e) {
            return new Result(false, type, "no exception", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Result inventory(JsonObject spec, FactSource facts) {
        String item = spec.get("item").getAsString();
        int min = spec.has("min") ? spec.get("min").getAsInt() : 1;
        int actual = facts.inventoryCount(item);
        return new Result(actual >= min, "inventory_count", item + " >= " + min, String.valueOf(actual));
    }

    private static Result blockAt(JsonObject spec, FactSource facts) {
        int x = spec.get("x").getAsInt();
        int y = spec.get("y").getAsInt();
        int z = spec.get("z").getAsInt();
        String id = spec.get("id").getAsString();
        String actual = facts.blockAt(x, y, z);
        return new Result(id.equals(actual), "block_at", id + " at " + x + "," + y + "," + z, actual);
    }

    private static Result blocksCount(JsonObject spec, FactSource facts) {
        String id = spec.get("id").getAsString();
        int radius = spec.has("radius") ? spec.get("radius").getAsInt() : 8;
        int min = spec.has("min") ? spec.get("min").getAsInt() : 1;
        int actual = facts.blocksCount(id, radius);
        return new Result(actual >= min, "blocks_count", id + " >= " + min, String.valueOf(actual));
    }

    private static Result positionNear(JsonObject spec, FactSource facts) {
        double x = spec.get("x").getAsDouble();
        double y = spec.get("y").getAsDouble();
        double z = spec.get("z").getAsDouble();
        double tolerance = spec.has("tolerance") ? spec.get("tolerance").getAsDouble() : 2.0;
        boolean ok = facts.positionNear(x, y, z, tolerance);
        return new Result(ok, "position_near", "within " + tolerance, ok ? "inside" : "outside");
    }

    private static Result entityNear(JsonObject spec, FactSource facts) {
        String type = spec.get("type").getAsString();
        int radius = spec.has("radius") ? spec.get("radius").getAsInt() : 16;
        int min = spec.has("min") ? spec.get("min").getAsInt() : 1;
        int actual = facts.entityCount(type, radius);
        return new Result(actual >= min, "entity_near", type + " >= " + min, String.valueOf(actual));
    }

    private static Result actionOk(JsonObject lastResult) {
        boolean ok = lastResult != null && lastResult.has("ok") && lastResult.get("ok").getAsBoolean();
        return new Result(ok, "action_ok", "ok=true", ok ? "ok" : String.valueOf(lastResult));
    }

    private static Result queryTrue(JsonObject spec, FactSource facts) throws Exception {
        String tool = spec.has("tool") ? spec.get("tool").getAsString() : "";
        JsonObject args = spec.has("args") ? spec.getAsJsonObject("args") : new JsonObject();
        JsonObject result = facts.query(tool, args);
        JsonElement value = path(result, spec.has("path") ? spec.get("path").getAsString() : "");
        String actual = value == null ? "missing" : value.toString();
        if (spec.has("contains")) {
            boolean ok = value != null && value.toString().contains(spec.get("contains").getAsString());
            return new Result(ok, "query_true", "contains " + spec.get("contains").getAsString(), actual);
        }
        if (spec.has("equals")) {
            boolean ok = value != null && value.toString().equals(spec.get("equals").getAsString());
            return new Result(ok, "query_true", spec.get("equals").getAsString(), actual);
        }
        if (spec.has("min")) {
            boolean ok = value != null && value.getAsDouble() >= spec.get("min").getAsDouble();
            return new Result(ok, "query_true", ">= " + spec.get("min").getAsDouble(), actual);
        }
        return new Result(value != null, "query_true", "path exists", actual);
    }

    private static Result combine(JsonObject spec, FactSource facts, JsonObject lastResult, boolean all) {
        JsonArray of = spec.getAsJsonArray("of");
        StringBuilder actual = new StringBuilder();
        for (JsonElement element : of) {
            Result r = verify(element.getAsJsonObject(), facts, lastResult);
            actual.append(r.type).append('=').append(r.ok).append(' ');
            if (all && !r.ok) {
                return new Result(false, all ? "all" : "any", "all true", actual.toString());
            }
            if (!all && r.ok) {
                return new Result(true, "any", "any true", actual.toString());
            }
        }
        return new Result(all, all ? "all" : "any", all ? "all true" : "any true", actual.toString());
    }

    private static Result not(JsonObject spec, FactSource facts, JsonObject lastResult) {
        Result r = verify(spec.getAsJsonObject("of"), facts, lastResult);
        return new Result(!r.ok, "not", "false", r.actual);
    }

    static JsonElement path(JsonObject root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (current == null || current.isJsonNull()) {
                return null;
            }
            if (current.isJsonArray()) {
                try {
                    int index = Integer.parseInt(part);
                    JsonArray array = current.getAsJsonArray();
                    current = index >= 0 && index < array.size() ? array.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (current.isJsonObject()) {
                JsonObject obj = current.getAsJsonObject();
                current = obj.has(part) ? obj.get(part) : null;
            } else {
                return null;
            }
        }
        return current;
    }
}
