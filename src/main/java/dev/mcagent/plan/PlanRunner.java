package dev.mcagent.plan;

import com.mojang.logging.LogUtils;
import dev.mcagent.llm.SecretStore;
import dev.mcagent.memory.MemoryStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates planner -> executor -> replanner. Runs off the client thread;
 * only the executor's ports touch the game.
 */
public final class PlanRunner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PlanRunner INSTANCE = new PlanRunner();
    private static final int MAX_REPLANS = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcagent-plan");
        thread.setDaemon(true);
        return thread;
    });
    private final Planner planner = new Planner();
    private final AtomicBoolean running = new AtomicBoolean();
    private final McFactSource facts = new McFactSource();
    private final McActionPort port = new McActionPort();

    private volatile TaskPlan plan;
    private volatile PlanResult lastResult;
    private volatile String planId;
    private volatile String goal = "";
    private volatile String lastWhy = "";

    private PlanRunner() {
    }

    public static PlanRunner get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(String goal, boolean dryRun) {
        if (running.get()) {
            tell("a plan is already running; /agent stop first");
            return;
        }
        if (!SecretStore.present() && !dryRun) {
            tell("no API key. Set " + SecretStore.ENV + " or write " + SecretStore.file());
            return;
        }
        this.goal = goal;
        this.lastWhy = "";
        running.set(true);
        executor.submit(() -> {
            try {
                TaskPlan built = planner.plan(goal);
                List<String> warnings = planner.warnings(built);
                if (dryRun) {
                    tell("dry run: " + built.tasks.size() + " tasks");
                    for (String warning : warnings) {
                        tell("warning: " + warning);
                    }
                    tell("plan: " + trim(built.raw.toString(), 600));
                    return;
                }
                for (String warning : warnings) {
                    tell("warning: " + warning);
                }
                plan = built;
                planId = store().save(built);
                execute(goal, built);
            } catch (Exception e) {
                lastWhy = e.getClass().getSimpleName() + ": " + e.getMessage();
                tell("planning failed: " + lastWhy);
                LOGGER.warn("[mcagent] planning failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    public void resume() {
        if (running.get()) {
            tell("a plan is already running");
            return;
        }
        try {
            Path latest = store().latest();
            if (latest == null) {
                tell("no saved plan to resume");
                return;
            }
            TaskPlan loaded = store().load(latest);
            this.plan = loaded;
            this.goal = loaded.goal;
            this.planId = latest.getFileName().toString().replace(".json", "");
            running.set(true);
            executor.submit(() -> {
                try {
                    execute(goal, loaded);
                } catch (Exception e) {
                    lastWhy = e.getMessage();
                    tell("resume failed: " + lastWhy);
                } finally {
                    running.set(false);
                }
            });
        } catch (Exception e) {
            tell("resume failed: " + e.getMessage());
        }
    }

    private void execute(String goal, TaskPlan initial) throws Exception {
        TaskPlan current = initial;
        for (int attempt = 0; attempt <= MAX_REPLANS; attempt++) {
            final TaskPlan running = current;
            PlanResult result = new PlanExecutor(port, facts, running.budget, node -> {
                saveQuietly(running);
                if (node.status == TaskNode.Status.FAILED) {
                    tell("task failed: " + node.id + " " + (node.error == null ? "" : node.error));
                }
            }).execute(running);
            lastResult = result;
            store().saveResult(planId, result);
            if (result.ok) {
                tell("plan done: " + goal);
                return;
            }
            lastWhy = (result.failedTask == null ? "" : result.failedTask + ": ")
                    + (result.error == null ? "failed" : result.error);
            if (attempt == MAX_REPLANS) {
                break;
            }
            tell("replanning (" + (attempt + 1) + "/" + MAX_REPLANS + "): " + lastWhy);
            current = planner.replan(goal, current, result.failedTask,
                    result.error == null ? "failed" : result.error, attempt + 1);
            plan = current;
            store().saveState(planId, current);
        }
        tell("plan failed: " + lastWhy);
    }

    public void stop() {
        boolean was = running.getAndSet(false);
        var actions = dev.mcagent.AgentRuntime.get();
        actions.cancelActions();
        tell(was ? "plan stopped" : "no plan running");
    }

    public String show() {
        TaskPlan current = plan;
        if (current == null) {
            return "no plan loaded";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("goal=").append(current.goal).append(" tasks=").append(current.tasks.size());
        if (lastResult != null) {
            sb.append(" lastOk=").append(lastResult.ok);
        }
        for (TaskNode node : current.tasks) {
            sb.append("\n  ").append(node.id).append(" [").append(node.status).append("] ")
                    .append(node.title);
            if (node.error != null && !node.error.isBlank()) {
                sb.append(" - ").append(node.error);
            }
        }
        return sb.toString();
    }

    public String why() {
        if (lastWhy == null || lastWhy.isBlank()) {
            return lastResult == null ? "no failure recorded" : "last plan ok=" + lastResult.ok;
        }
        return lastWhy;
    }

    private PlanStore store() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("mcagent").resolve("tasks")
                .resolve(MemoryStore.get().worldKey());
        return new PlanStore(dir);
    }

    private void saveQuietly(TaskPlan current) {
        try {
            if (planId != null) {
                store().saveState(planId, current);
            }
        } catch (Exception e) {
            LOGGER.debug("[mcagent] checkpoint failed: {}", e.getMessage());
        }
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[agent] " + message), false);
            }
        });
        LOGGER.info("[mcagent] {}", message);
    }
}
