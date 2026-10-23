package dev.mcagent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Deterministic executor. The model is not involved once a plan exists.
 */
public final class PlanExecutor {
    public interface Listener {
        void onNode(TaskNode node);
    }

    private final ActionPort port;
    private final FactSource facts;
    private final Budget budget;
    private final Listener listener;

    public PlanExecutor(ActionPort port, FactSource facts, Budget budget, Listener listener) {
        this.port = port;
        this.facts = facts;
        this.budget = budget;
        this.listener = listener == null ? node -> { } : listener;
    }

    public PlanResult execute(TaskPlan plan) {
        for (TaskNode node : plan.topological()) {
            if (node.status == TaskNode.Status.DONE) {
                continue;
            }
            if (dependencyFailed(plan, node)) {
                node.status = TaskNode.Status.SKIPPED;
                node.error = "dependency failed";
                listener.onNode(node);
                continue;
            }
            if (budget.exhausted()) {
                return result(plan, false, "budget exhausted", node.id);
            }
            node.status = TaskNode.Status.RUNNING;
            listener.onNode(node);
            boolean done = false;
            int attempts = node.retries + 1;
            for (int attempt = 0; attempt < attempts && !done; attempt++) {
                if (!budget.spendAction()) {
                    return result(plan, false, "action budget exhausted", node.id);
                }
                try {
                    JsonObject outcome = run(node);
                    Verifier.Result verification = Verifier.verify(node.verify, facts, outcome);
                    node.evidence = verification.toJson();
                    done = verification.ok;
                    if (!done) {
                        node.error = "verify failed: expected " + verification.expected
                                + ", actual " + verification.actual;
                    }
                } catch (Exception e) {
                    node.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            node.status = done ? TaskNode.Status.DONE : TaskNode.Status.FAILED;
            listener.onNode(node);
            if (!done) {
                return result(plan, false, node.error == null ? "task failed" : node.error, node.id);
            }
        }

        if (!goalVerified(plan)) {
            return result(plan, false, "goal predicate not satisfied", null);
        }
        return result(plan, true, null, null);
    }

    private JsonObject run(TaskNode node) throws Exception {
        return switch (node.type) {
            case "action" -> port.runAction(node.action != null ? node.action : node.tool, node.args);
            case "query" -> port.runQuery(node.tool, node.args);
            case "skill" -> port.runSkill(node.skill, node.args);
            case "macro" -> port.runMacro(node.macro, node.args);
            case "assert" -> {
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                yield o;
            }
            default -> throw new IllegalStateException("unknown task type: " + node.type);
        };
    }

    private boolean goalVerified(TaskPlan plan) {
        for (JsonElement element : plan.success) {
            Verifier.Result result = Verifier.verify(element.getAsJsonObject(), facts, null);
            if (!result.ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean dependencyFailed(TaskPlan plan, TaskNode node) {
        for (String dep : node.dependsOn) {
            for (TaskNode other : plan.tasks) {
                if (other.id.equals(dep)
                        && (other.status == TaskNode.Status.FAILED || other.status == TaskNode.Status.SKIPPED)) {
                    return true;
                }
            }
        }
        return false;
    }

    private PlanResult result(TaskPlan plan, boolean ok, String error, String failedTask) {
        if (failedTask != null) {
            for (TaskNode node : plan.tasks) {
                if (node.status == TaskNode.Status.PENDING || node.status == TaskNode.Status.RUNNING) {
                    node.status = TaskNode.Status.SKIPPED;
                    node.error = "plan aborted";
                }
            }
        }
        JsonArray nodes = new JsonArray();
        for (TaskNode node : plan.tasks) {
            nodes.add(node.toJson());
        }
        return new PlanResult(ok, error, failedTask, nodes, budget.snapshot());
    }
}
