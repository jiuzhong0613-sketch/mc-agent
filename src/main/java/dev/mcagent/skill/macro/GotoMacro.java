package dev.mcagent.skill.macro;

import com.google.gson.JsonObject;
import dev.mcagent.plan.McFactSource;

/**
 * Deterministic goto: route check first, then walk, then verify position.
 */
public final class GotoMacro {
    private GotoMacro() {
    }

    public static JsonObject run(JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            return MacroSupport.error("goto needs x, y, z");
        }
        double x = args.get("x").getAsDouble();
        double y = args.get("y").getAsDouble();
        double z = args.get("z").getAsDouble();
        double tolerance = args.has("tolerance") ? args.get("tolerance").getAsDouble() : 1.0;

        JsonObject routeArgs = new JsonObject();
        routeArgs.addProperty("x", x);
        routeArgs.addProperty("y", y);
        routeArgs.addProperty("z", z);
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
            walkArgs.addProperty("x", x);
            walkArgs.addProperty("y", y);
            walkArgs.addProperty("z", z);
            walkArgs.addProperty("tolerance", tolerance);
            JsonObject walk = MacroSupport.action("walk_to", walkArgs);
            if (!MacroSupport.ok(walk)) {
                return walk;
            }
        } catch (Exception e) {
            return MacroSupport.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        boolean arrived = new McFactSource().positionNear(x, y, z, tolerance + 1.0);
        JsonObject out = new JsonObject();
        out.addProperty("ok", arrived);
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("tolerance", tolerance);
        if (!arrived) {
            out.addProperty("error", "not within tolerance");
        }
        return out;
    }
}
