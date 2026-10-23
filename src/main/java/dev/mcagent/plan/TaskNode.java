package dev.mcagent.plan;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class TaskNode {
    public enum Status {
        PENDING, RUNNING, DONE, FAILED, SKIPPED
    }

    public final String id;
    public final String title;
    public final String type;
    public final String tool;
    public final String action;
    public final String macro;
    public final String skill;
    public final JsonObject args;
    public final List<String> dependsOn;
    public final JsonObject verify;
    public final int retries;

    public Status status = Status.PENDING;
    public String error;
    public JsonObject evidence;

    public TaskNode(String id, String title, String type, String tool, String action, String macro, String skill,
                    JsonObject args, List<String> dependsOn, JsonObject verify, int retries) {
        this.id = id;
        this.title = title == null ? id : title;
        this.type = type;
        this.tool = tool;
        this.action = action;
        this.macro = macro;
        this.skill = skill;
        this.args = args == null ? new JsonObject() : args;
        this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn;
        this.verify = verify;
        this.retries = Math.max(0, retries);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("type", type);
        if (tool != null) {
            o.addProperty("tool", tool);
        }
        if (action != null) {
            o.addProperty("action", action);
        }
        if (macro != null) {
            o.addProperty("macro", macro);
        }
        if (skill != null) {
            o.addProperty("skill", skill);
        }
        o.add("args", args.deepCopy());
        if (!dependsOn.isEmpty()) {
            var arr = new com.google.gson.JsonArray();
            dependsOn.forEach(arr::add);
            o.add("dependsOn", arr);
        }
        if (verify != null) {
            o.add("verify", verify.deepCopy());
        }
        o.addProperty("retries", retries);
        o.addProperty("status", status.name());
        if (error != null) {
            o.addProperty("error", error);
        }
        return o;
    }
}
