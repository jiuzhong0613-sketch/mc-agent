package dev.mcagent.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Explicit memory tools. The framework also injects a short memory brief at start.
 */
public final class MemoryTools {
    private MemoryTools() {
    }

    public static JsonArray all() {
        JsonArray tools = new JsonArray();
        tools.add(fn("remember", "Store a durable fact or lesson for this world. Use for resource locations, hazards, landmarks and one-line lessons.",
                """
                {"type":"object","properties":{"kind":{"type":"string","enum":["fact","lesson"]},"text":{"type":"string"},"tags":{"type":"string","description":"comma separated"}},"required":["kind","text"]}
                """));
        tools.add(fn("recall", "Search memory by keywords. Call this before exploring an area you may have seen before.",
                """
                {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":12}},"required":["query"]}
                """));
        tools.add(fn("run_skill", "Replay a stored executable skill. Pass x,y,z to shift an anchored skill to a new target.",
                """
                {"type":"object","properties":{"name":{"type":"string"},"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}},"required":["name"]}
                """));
        tools.add(fn("list_skills", "List stored skills with step counts and success/failure stats.",
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
