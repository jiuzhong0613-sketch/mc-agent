package dev.mcagent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed and validated plan. Pure data: no Minecraft types, unit-testable.
 */
public final class TaskPlan {
    public final int version;
    public final String goal;
    public final JsonArray success;
    public final Budget budget;
    public final List<TaskNode> tasks;
    public final JsonObject raw;

    private static final Set<String> VERIFY_TYPES = Set.of(
            "inventory_count", "block_at", "blocks_count", "position_near", "entity_near",
            "query_true", "query_ok", "action_ok", "all", "any", "not");
    private static final Map<String, String> VERIFY_ALIASES = Map.ofEntries(
            Map.entry("inventory_has", "inventory_count"),
            Map.entry("inventory", "inventory_count"),
            Map.entry("item_count", "inventory_count"),
            Map.entry("block", "block_at"),
            Map.entry("block_exists", "blocks_count"),
            Map.entry("position", "position_near"),
            Map.entry("entity", "entity_near"),
            Map.entry("query", "query_true"),
            Map.entry("always", "action_ok"));

    private TaskPlan(int version, String goal, JsonArray success, Budget budget,
                     List<TaskNode> tasks, JsonObject raw) {
        this.version = version;
        this.goal = goal;
        this.success = success;
        this.budget = budget;
        this.tasks = tasks;
        this.raw = raw;
    }

    public static TaskPlan parse(String json) throws PlanException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new PlanException("plan is not a JSON object");
        }
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version != 1) {
            throw new PlanException("unsupported plan version: " + version);
        }
        String goal = root.has("goal") ? root.get("goal").getAsString() : "";
        if (goal.isBlank()) {
            throw new PlanException("plan has no goal");
        }
        JsonArray success = root.has("success") ? root.getAsJsonArray("success") : new JsonArray();
        if (success.isEmpty()) {
            throw new PlanException("plan has no success predicate");
        }
        JsonArray taskArray = root.has("tasks") ? root.getAsJsonArray("tasks") : new JsonArray();
        if (taskArray.isEmpty()) {
            throw new PlanException("plan has no tasks");
        }

        List<TaskNode> tasks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : taskArray) {
            if (!element.isJsonObject()) {
                throw new PlanException("task is not an object");
            }
            JsonObject t = element.getAsJsonObject();
            String id = text(t, "id");
            if (id.isBlank()) {
                throw new PlanException("task without id");
            }
            if (!ids.add(id)) {
                throw new PlanException("duplicate task id: " + id);
            }
            String type = text(t, "type");
            if (!Set.of("action", "query", "skill", "macro", "assert").contains(type)) {
                throw new PlanException("unknown task type: " + type);
            }
            List<String> depends = new ArrayList<>();
            if (t.has("dependsOn")) {
                for (JsonElement dep : t.getAsJsonArray("dependsOn")) {
                    depends.add(dep.getAsString());
                }
            }
            tasks.add(new TaskNode(
                    id,
                    text(t, "title"),
                    type,
                    t.has("tool") ? t.get("tool").getAsString() : null,
                    t.has("action") ? t.get("action").getAsString() : null,
                    t.has("macro") ? t.get("macro").getAsString() : null,
                    t.has("skill") ? t.get("skill").getAsString() : null,
                    t.has("args") ? t.getAsJsonObject("args") : new JsonObject(),
                    depends,
                    t.has("verify") ? t.getAsJsonObject("verify") : null,
                    t.has("retries") ? t.get("retries").getAsInt() : 1));
        }

        for (TaskNode task : tasks) {
            for (String dep : task.dependsOn) {
                if (!ids.contains(dep)) {
                    throw new PlanException("task " + task.id + " depends on unknown " + dep);
                }
            }
        }
        for (TaskNode task : tasks) {
            normalize(task);
        }
        detectCycle(tasks);
        restoreState(root, tasks);
        return new TaskPlan(version, goal, success, Budget.from(root), tasks, root);
    }

    private static void normalize(TaskNode task) {
        if ("macro".equals(task.type) && task.macro == null) {
            if (task.action != null) {
                task.type = "action";
            } else if (task.tool != null) {
                task.type = "query";
            }
        }
        task.verify = normalizeVerify(task.verify, task.type);
    }

    private static JsonObject normalizeVerify(JsonObject spec, String taskType) {
        if (spec == null || !spec.has("type")) {
            return defaultVerify(taskType);
        }
        String type = spec.get("type").getAsString();
        String mapped = VERIFY_ALIASES.get(type);
        if (mapped != null) {
            spec.addProperty("type", mapped);
            type = mapped;
        }
        if (!VERIFY_TYPES.contains(type)) {
            return defaultVerify(taskType);
        }
        if (("all".equals(type) || "any".equals(type)) && spec.has("of") && spec.get("of").isJsonArray()) {
            JsonArray of = spec.getAsJsonArray("of");
            for (int i = 0; i < of.size(); i++) {
                if (of.get(i).isJsonObject()) {
                    of.set(i, normalizeVerify(of.get(i).getAsJsonObject(), taskType));
                }
            }
        }
        if ("not".equals(type) && spec.has("of") && spec.get("of").isJsonObject()) {
            spec.add("of", normalizeVerify(spec.getAsJsonObject("of"), taskType));
        }
        return spec;
    }

    private static JsonObject defaultVerify(String taskType) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "query".equals(taskType) ? "query_ok" : "action_ok");
        return o;
    }

    private static void restoreState(JsonObject root, List<TaskNode> tasks) {
        if (!root.has("taskState") || !root.get("taskState").isJsonArray()) {
            return;
        }
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement element : root.getAsJsonArray("taskState")) {
            if (element.isJsonObject()) {
                JsonObject state = element.getAsJsonObject();
                if (state.has("id")) {
                    byId.put(state.get("id").getAsString(), state);
                }
            }
        }
        for (TaskNode task : tasks) {
            JsonObject state = byId.get(task.id);
            if (state == null) {
                continue;
            }
            if (state.has("status")) {
                try {
                    task.status = TaskNode.Status.valueOf(state.get("status").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (state.has("error")) {
                task.error = state.get("error").getAsString();
            }
        }
    }

    private static void detectCycle(List<TaskNode> tasks) throws PlanException {
        Map<String, TaskNode> byId = new HashMap<>();
        tasks.forEach(t -> byId.put(t.id, t));
        Map<String, Integer> state = new HashMap<>();
        for (TaskNode task : tasks) {
            visit(task, byId, state);
        }
    }

    private static void visit(TaskNode task, Map<String, TaskNode> byId, Map<String, Integer> state)
            throws PlanException {
        Integer mark = state.get(task.id);
        if (mark != null && mark == 1) {
            throw new PlanException("dependency cycle at " + task.id);
        }
        if (mark != null && mark == 2) {
            return;
        }
        state.put(task.id, 1);
        for (String dep : task.dependsOn) {
            visit(byId.get(dep), byId, state);
        }
        state.put(task.id, 2);
    }

    /** Dependency-first order, preserving declaration order where possible. */
    public List<TaskNode> topological() {
        Map<String, TaskNode> byId = new HashMap<>();
        tasks.forEach(t -> byId.put(t.id, t));
        List<TaskNode> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Deque<TaskNode> queue = new ArrayDeque<>(tasks);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < tasks.size() * tasks.size() + 1) {
            TaskNode task = queue.pollFirst();
            if (done.contains(task.id)) {
                continue;
            }
            if (done.containsAll(task.dependsOn)) {
                ordered.add(task);
                done.add(task.id);
            } else {
                queue.addLast(task);
            }
        }
        for (TaskNode t : tasks) {
            if (!done.contains(t.id)) {
                ordered.add(t);
            }
        }
        return ordered;
    }

    public boolean successVerified(Verifier.Result result) {
        return result.ok;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        JsonArray statuses = new JsonArray();
        for (TaskNode task : tasks) {
            statuses.add(task.toJson());
        }
        o.add("taskState", statuses);
        return o;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
