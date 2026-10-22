package dev.mcagent.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tool schemas for read-only probes. Same JSON shape as action tools.
 */
public final class QueryTools {
    private QueryTools() {
    }

    public static JsonArray all() {
        JsonArray tools = new JsonArray();
        tools.add(fn("query_block", "Read one block. Use when the block is outside the state scan.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}
                """));
        tools.add(fn("query_scan", "Scan a cube and return block type counts plus a sample of solid positions.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},"radius":{"type":"integer","minimum":1,"maximum":12}}}
                """));
        tools.add(fn("query_entities", "List entities within a radius, with distance and line of sight.",
                """
                {"type":"object","properties":{"radius":{"type":"integer","minimum":1,"maximum":48}}}
                """));
        tools.add(fn("query_inventory", "List every non-empty inventory slot with container index.",
                """
                {"type":"object","properties":{}}
                """));
        tools.add(fn("query_raycast", "Cast the crosshair ray and report the first block or entity hit.",
                """
                {"type":"object","properties":{"distance":{"type":"number","minimum":1,"maximum":64}}}
                """));
        tools.add(fn("query_can_stand", "Check whether a position is free at feet and head with solid floor.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}
                """));
        tools.add(fn("query_surface", "Return the surface height and block at a column.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"]}
                """));
        tools.add(fn("query_route", "Probe the straight line to a target: clear, blocked, water, lava or unloaded.",
                """
                {"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}},"required":["x","y","z"]}
                """));
        tools.add(fn("query_env", "Time, weather, dimension, biome, light level and difficulty.",
                """
                {"type":"object","properties":{}}
                """));
        return tools;
    }

    private static JsonObject fn(String name, String description, String parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", JsonParser.parseString(parameters));
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }
}
