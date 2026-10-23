package dev.mcagent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcagent.llm.LlmClient;
import dev.mcagent.llm.StateSummary;
import dev.mcagent.memory.MemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one sentence into a validated TaskPlan. At most two model calls:
 * the plan and one repair. No tools: the planner reasons from text only.
 */
public final class Planner {
    private static final Set<String> ACTIONS = Set.of(
            "look", "look_at", "move", "walk_to", "mine", "place", "use", "attack",
            "select_slot", "chat", "drop", "respawn", "wait");
    private static final Set<String> QUERIES = Set.of(
            "query_block", "query_scan", "query_entities", "query_inventory", "query_raycast",
            "query_can_stand", "query_surface", "query_route", "query_env");
    private static final Set<String> MACROS = Set.of("mine_n", "goto");

    private static final String SYSTEM = """
            You are the planner for a Minecraft client agent. Output ONE JSON object, no markdown.
            The executor is deterministic and can only do the capabilities below.

            Allowed task types: action, query, skill, macro, assert.
            Actions: look, look_at, move, walk_to, mine, place, use, attack, select_slot, chat, drop, respawn, wait.
            Queries: query_block, query_scan, query_entities, query_inventory, query_raycast, query_can_stand, query_surface, query_route, query_env.
            Macros: mine_n {block, count, radius}, goto {x, y, z, tolerance}.
            Skills: only names listed in the user message.

            Hard limits:
            - no GUI, no crafting, no chests, no villagers, no doors, no beds;
            - no pathfinding: walk_to is a straight line that fails on water, cliffs and walls;
            - interactions must be within 4.5 blocks;
            - block scan radius is 5 by default.

            Plan rules:
            - 3 to 8 tasks, each at least one macro-sized step;
            - every task must have a "verify" predicate;
            - coordinates must come from the state, a query, or the goal; never invent them;
            - if a fact is missing, add a query task first;
            - success must contain deterministic predicates, not opinions.

            Schema:
            {"version":1,"goal":"...","success":[...],"budget":{"llmCalls":8,"actions":400,"seconds":600},
             "tasks":[{"id":"t1","title":"...","type":"query","tool":"query_scan","args":{},"verify":{...}}]}

            Verify predicates:
            inventory_count {item,min}; block_at {x,y,z,id}; blocks_count {id,radius,min};
            position_near {x,y,z,tolerance}; entity_near {type,radius,min};
            query_true {tool,args,path,contains|equals|min}; action_ok;
            all/any {of:[...]}; not {of:{...}}.
            """;

    private final LlmClient client = new LlmClient();

    public TaskPlan plan(String goal) throws Exception {
        String request = "Goal: " + goal + "\n\n" + context(goal)
                + "\n\nReturn the plan JSON.";
        return callAndParse(SYSTEM, request, null);
    }

    public TaskPlan replan(String goal, TaskPlan previous, String failedTask,
                           String failure, int attempt) throws Exception {
        String request = "Goal: " + goal + "\n\n" + context(goal)
                + "\n\nPrevious plan:\n" + previous.raw
                + "\n\nFailed task: " + failedTask
                + "\nFailure: " + failure
                + "\nReplan attempt: " + attempt
                + "\nReturn a revised plan JSON. Keep what worked, replace what failed.";
        return callAndParse(SYSTEM, request, previous);
    }

    public List<String> warnings(TaskPlan plan) {
        List<String> out = new ArrayList<>();
        for (TaskNode task : plan.tasks) {
            switch (task.type) {
                case "action" -> {
                    String name = task.action != null ? task.action : task.tool;
                    if (name == null || !ACTIONS.contains(name)) {
                        out.add(task.id + ": unsupported action " + name);
                    }
                }
                case "query" -> {
                    if (task.tool == null || !QUERIES.contains(task.tool)) {
                        out.add(task.id + ": unsupported query " + task.tool);
                    }
                }
                case "macro" -> {
                    if (task.macro == null || !MACROS.contains(task.macro)) {
                        out.add(task.id + ": unsupported macro " + task.macro);
                    }
                }
                case "skill" -> {
                    if (task.skill == null || MemoryStore.get().findSkill(task.skill) == null) {
                        out.add(task.id + ": unknown skill " + task.skill);
                    }
                }
                case "assert" -> {
                }
                default -> out.add(task.id + ": unknown type " + task.type);
            }
            if (task.verify == null || task.verify.isEmpty()) {
                out.add(task.id + ": missing verify predicate");
            }
        }
        if (plan.tasks.size() > 8) {
            out.add("plan has more than 8 tasks");
        }
        return out;
    }

    private TaskPlan callAndParse(String system, String user, TaskPlan previous) throws Exception {
        String content = ask(system, user);
        try {
            return TaskPlan.parse(extract(content));
        } catch (Exception first) {
            String repair = user + "\n\nYour previous output was invalid: " + first.getMessage()
                    + "\nPrevious output:\n" + trim(content, 4000)
                    + "\nReturn corrected JSON only.";
            String second = ask(SYSTEM, repair);
            return TaskPlan.parse(extract(second));
        }
    }

    private String ask(String system, String user) throws Exception {
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", user);
        messages.add(msg);
        JsonObject reply = client.chat(messages, null);
        if (!reply.has("content") || reply.get("content").isJsonNull()) {
            throw new IllegalStateException("planner returned no content");
        }
        return reply.get("content").getAsString();
    }

    private String context(String goal) throws Exception {
        String state = StateSummary.build();
        String memory = MemoryStore.get().brief(goal);
        StringBuilder skills = new StringBuilder();
        for (JsonElement element : MemoryStore.get().listSkills()) {
            JsonObject skill = element.getAsJsonObject();
            skills.append("- ").append(skill.get("name").getAsString())
                    .append(" (steps=").append(skill.get("steps").getAsInt())
                    .append(", ok=").append(skill.get("ok").getAsInt())
                    .append(", fail=").append(skill.get("fail").getAsInt())
                    .append(skill.get("executable").getAsBoolean() ? ", executable" : ", text only")
                    .append(")\n");
        }
        return "State:\n" + state + "\n\nMemory:\n" + memory
                + "\n\nSkills:\n" + (skills.length() == 0 ? "(none)" : skills.toString());
    }

    static String extract(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            text = text.substring(first, last + 1);
        }
        return text;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
