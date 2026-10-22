package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tool schema handed to the model. Names match action types exactly.
 */
public final class Tools {
    private Tools() {
    }

    public static JsonArray all() {
        JsonArray tools = new JsonArray();
        tools.add(fn("look_at", "Aim the camera at a world position.",
                """
                {"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"}},"required":["x","y","z"]}
                """));
        tools.add(fn("move", "Apply raw movement input for N ticks (20 ticks = 1 second).",
                """
                {"type":"object","properties":{"forward":{"type":"number","description":"-1..1"},"strafe":{"type":"number","description":"-1..1, positive is right"},"jump":{"type":"boolean"},"sneak":{"type":"boolean"},"sprint":{"type":"boolean"},"ticks":{"type":"integer","minimum":1,"maximum":400}},"required":["ticks"]}
                """));
        tools.add(fn("walk_to", "Walk straight toward a position. Naive steering, not pathfinding.",
                """
                {"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number"},"tolerance":{"type":"number","description":"default 0.8"},"sprint":{"type":"boolean"}},"required":["x","y","z"]}
                """));
        tools.add(fn("mine", "Dig the block at a position until it breaks. Must be in reach.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]}
                """));
        tools.add(fn("place", "Place the held block against the given position.",
                """
                {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},"face":{"type":"string","enum":["up","down","north","south","east","west"]}},"required":["x","y","z"]}
                """));
        tools.add(fn("use", "Right click with the current or off hand.",
                """
                {"type":"object","properties":{"hand":{"type":"string","enum":["main","offhand"]}}}
                """));
        tools.add(fn("attack", "Attack an entity id from the state.",
                """
                {"type":"object","properties":{"entity":{"type":"integer"}},"required":["entity"]}
                """));
        tools.add(fn("select_slot", "Select a hotbar slot 0..8.",
                """
                {"type":"object","properties":{"slot":{"type":"integer","minimum":0,"maximum":8}},"required":["slot"]}
                """));
        tools.add(fn("chat", "Send a chat message.",
                """
                {"type":"object","properties":{"text":{"type":"string","maxLength":256}},"required":["text"]}
                """));
        tools.add(fn("wait", "Idle for N ticks.",
                """
                {"type":"object","properties":{"ticks":{"type":"integer","minimum":1,"maximum":400}},"required":["ticks"]}
                """));
        tools.add(fn("done", "Finish the task. Call this when the goal is reached or impossible.",
                """
                {"type":"object","properties":{"summary":{"type":"string"},"lesson":{"type":"string","description":"one durable lesson for next time"},"skill":{"type":"string","description":"optional name for saving this successful sequence as a reusable skill"}},"required":["summary"]}
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
