package dev.mcagent.act;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes actions: one at a time, tick-aligned, with a hard timeout.
 */
public final class ActionQueue {
    private static final class Entry {
        final String id;
        final Action action;
        int ticks;

        Entry(String id, Action action) {
            this.id = id;
            this.action = action;
        }
    }

    private final Deque<Entry> pending = new ArrayDeque<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private Entry current;
    private JsonObject lastResult;

    public synchronized JsonObject submit(Action action, int maxQueue) {
        JsonObject out = new JsonObject();
        if (pending.size() >= maxQueue) {
            out.addProperty("ok", false);
            out.addProperty("error", "queue_full");
            return out;
        }
        String id = "a" + sequence.incrementAndGet();
        pending.addLast(new Entry(id, action));
        out.addProperty("ok", true);
        out.addProperty("id", id);
        out.addProperty("queued", pending.size() + (current == null ? 0 : 1));
        return out;
    }

    public synchronized void tick(Minecraft mc) {
        if (current == null) {
            current = pending.pollFirst();
            if (current == null) {
                return;
            }
        }
        current.ticks++;
        try {
            JsonObject data = current.action.tick(mc);
            if (data != null) {
                finish(current, true, data, null);
            } else if (current.ticks > current.action.timeoutTicks()) {
                safeCancel(current.action, mc);
                finish(current, false, null, "timeout");
            }
        } catch (Throwable t) {
            safeCancel(current.action, mc);
            finish(current, false, null, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void safeCancel(Action action, Minecraft mc) {
        try {
            action.cancel(mc);
        } catch (Throwable ignored) {
        }
    }

    private void finish(Entry entry, boolean ok, JsonObject data, String error) {
        JsonObject out = new JsonObject();
        out.addProperty("id", entry.id);
        out.addProperty("type", entry.action.type());
        out.addProperty("ok", ok);
        out.addProperty("ticks", entry.ticks);
        if (data != null) {
            out.add("data", data);
        }
        if (error != null) {
            out.addProperty("error", error);
        }
        lastResult = out;
        current = null;
        Actions.releaseKeys(Minecraft.getInstance());
        notifyAll();
    }

    public synchronized JsonObject cancel(Minecraft mc) {
        JsonObject out = new JsonObject();
        Entry entry = current;
        if (entry == null) {
            pending.clear();
            out.addProperty("ok", true);
            out.addProperty("cancelled", "queue");
            return out;
        }
        safeCancel(entry.action, mc);
        lastResult = new JsonObject();
        lastResult.addProperty("id", entry.id);
        lastResult.addProperty("type", entry.action.type());
        lastResult.addProperty("ok", false);
        lastResult.addProperty("error", "cancelled");
        current = null;
        Actions.releaseKeys(mc);
        notifyAll();
        out.addProperty("ok", true);
        out.addProperty("cancelled", entry.id);
        return out;
    }

    public synchronized JsonObject lastResult() {
        return lastResult;
    }

    public synchronized boolean awaitResult(String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (lastResult != null && lastResult.has("id")
                    && id.equals(lastResult.get("id").getAsString())) {
                return true;
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            wait(Math.min(left, 250));
        }
    }

    public synchronized JsonObject resultFor(String id) {
        if (lastResult != null && lastResult.has("id")
                && id.equals(lastResult.get("id").getAsString())) {
            return lastResult;
        }
        return null;
    }

    public synchronized int size() {
        return pending.size() + (current == null ? 0 : 1);
    }

    public synchronized String currentType() {
        return current == null ? null : current.action.type();
    }
}
