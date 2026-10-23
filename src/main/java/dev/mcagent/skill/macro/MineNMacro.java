package dev.mcagent.skill.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcagent.plan.McFactSource;

/**
 * Deterministic "mine N of block X nearby": scan, route check, walk, mine, count.
 * Fails early with evidence instead of wandering.
 */
public final class MineNMacro {
    private MineNMacro() {
    }

    public static JsonObject run(JsonObject args) {
        if (!args.has("block")) {
            return MacroSupport.error("mine_n needs block");
        }
        String block = args.get("block").getAsString();
        int want = Math.max(1, args.has("count") ? args.get("count").getAsInt() : 1);
        int radius = Math.max(1, args.has("radius") ? args.get("radius").getAsInt() : 8);
        McFactSource facts = new McFactSource();
        int start = facts.inventoryCount(block);
        int mined = 0;

        for (int attempt = 0; attempt < want * 3 && mined < want; attempt++) {
            JsonObject scanArgs = new JsonObject();
            scanArgs.addProperty("radius", radius);
            JsonObject scan;
            try {
                scan = MacroSupport.query("query_scan", scanArgs);
            } catch (Exception e) {
                return MacroSupport.error("scan failed: " + e.getMessage());
            }
            double[] target = nearest(scan, block);
            if (target == null) {
                JsonObject out = MacroSupport.error("no " + block + " within " + radius);
                out.add("scan", scan);
                return out;
            }

            JsonObject routeArgs = new JsonObject();
            routeArgs.addProperty("x", target[0]);
            routeArgs.addProperty("y", target[1]);
            routeArgs.addProperty("z", target[2]);
            try {
                JsonObject route = MacroSupport.query("query_route", routeArgs);
                String verdict = route.has("verdict") ? route.get("verdict").getAsString() : "unknown";
                if (!"clear".equals(verdict)) {
                    JsonObject out = MacroSupport.error("route " + verdict);
                    out.add("route", route);
                    return out;
                }
            } catch (Exception e) {
                return MacroSupport.error("route failed: " + e.getMessage());
            }

            try {
                JsonObject walkArgs = new JsonObject();
                walkArgs.addProperty("x", target[0]);
                walkArgs.addProperty("y", target[1]);
                walkArgs.addProperty("z", target[2]);
                walkArgs.addProperty("tolerance", 2.5);
                JsonObject walk = MacroSupport.action("walk_to", walkArgs);
                if (!MacroSupport.ok(walk)) {
                    return walk;
                }
                JsonObject mineArgs = new JsonObject();
                mineArgs.addProperty("x", (int) target[0]);
                mineArgs.addProperty("y", (int) target[1]);
                mineArgs.addProperty("z", (int) target[2]);
                JsonObject mine = MacroSupport.action("mine", mineArgs);
                if (!MacroSupport.ok(mine)) {
                    return mine;
                }
                mined++;
            } catch (Exception e) {
                return MacroSupport.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        int now = facts.inventoryCount(block);
        JsonObject out = new JsonObject();
        out.addProperty("ok", now - start >= want);
        out.addProperty("requested", want);
        out.addProperty("mined", mined);
        out.addProperty("inventoryBefore", start);
        out.addProperty("inventoryAfter", now);
        if (!out.get("ok").getAsBoolean()) {
            out.addProperty("error", "inventory target not reached");
        }
        return out;
    }

    private static double[] nearest(JsonObject scan, String block) {
        if (scan == null || !scan.has("types")) {
            return null;
        }
        JsonArray types = scan.getAsJsonArray("types");
        for (JsonElement element : types) {
            JsonObject type = element.getAsJsonObject();
            if (type.get("id").getAsString().equals(block) && type.has("nearest")) {
                return MacroSupport.point(type.get("nearest").getAsString());
            }
        }
        return null;
    }
}
