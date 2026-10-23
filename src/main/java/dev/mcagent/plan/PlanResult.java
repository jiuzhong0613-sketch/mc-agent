package dev.mcagent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class PlanResult {
    public final boolean ok;
    public final String error;
    public final String failedTask;
    public final JsonArray nodes;
    public final JsonObject budget;

    public PlanResult(boolean ok, String error, String failedTask, JsonArray nodes, JsonObject budget) {
        this.ok = ok;
        this.error = error;
        this.failedTask = failedTask;
        this.nodes = nodes;
        this.budget = budget;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        if (error != null) {
            o.addProperty("error", error);
        }
        if (failedTask != null) {
            o.addProperty("failedTask", failedTask);
        }
        o.add("nodes", nodes);
        o.add("budget", budget);
        return o;
    }
}
