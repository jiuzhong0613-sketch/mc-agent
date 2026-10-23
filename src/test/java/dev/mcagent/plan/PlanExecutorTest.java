package dev.mcagent.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutorTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final class FakePort implements ActionPort {
        int calls;
        int failFirst;
        JsonObject last = new JsonObject();

        @Override
        public JsonObject runAction(String type, JsonObject args) {
            calls++;
            boolean ok = calls > failFirst;
            JsonObject out = new JsonObject();
            out.addProperty("ok", ok);
            last = out;
            return out;
        }

        @Override
        public JsonObject runQuery(String name, JsonObject args) {
            return json("{\"ok\":true}");
        }

        @Override
        public JsonObject runSkill(String name, JsonObject args) {
            return json("{\"ok\":true}");
        }

        @Override
        public JsonObject runMacro(String name, JsonObject args) {
            return json("{\"ok\":true}");
        }
    }

    private static TaskPlan plan(String tasks, String success) throws PlanException {
        return TaskPlan.parse("""
                {"version":1,"goal":"test","success":[%s],"budget":{"actions":10,"llmCalls":2,"seconds":30},"tasks":[%s]}
                """.formatted(success, tasks));
    }

    @Test
    void executesInOrderAndVerifiesGoal() throws Exception {
        FakeFacts facts = new FakeFacts();
        facts.inventory = 2;
        FakePort port = new FakePort();
        TaskPlan p = plan(
                """
                {"id":"a","type":"action","action":"mine","args":{"x":1,"y":2,"z":3}},
                {"id":"b","type":"action","action":"mine","args":{"x":1,"y":2,"z":4},"dependsOn":["a"],"verify":{"type":"inventory_count","item":"x","min":2}}
                """,
                """
                {"type":"inventory_count","item":"x","min":2}
                """);
        PlanResult r = new PlanExecutor(port, facts, p.budget, null).execute(p);
        assertTrue(r.ok, r.error);
        assertEquals(2, port.calls);
        assertEquals(TaskNode.Status.DONE, p.tasks.get(1).status);
    }

    @Test
    void retriesOnceThenSucceeds() throws Exception {
        FakeFacts facts = new FakeFacts();
        facts.near = true;
        FakePort port = new FakePort();
        port.failFirst = 1;
        TaskPlan p = plan(
                """
                {"id":"a","type":"action","action":"mine","args":{},"retries":1,"verify":{"type":"action_ok"}}
                """,
                """
                {"type":"position_near","x":0,"y":0,"z":0,"tolerance":2}
                """);
        PlanResult r = new PlanExecutor(port, facts, p.budget, null).execute(p);
        assertTrue(r.ok, r.error);
        assertEquals(2, port.calls);
    }

    @Test
    void abortsOnPersistentFailure() throws Exception {
        FakeFacts facts = new FakeFacts();
        FakePort port = new FakePort();
        port.failFirst = 99;
        TaskPlan p = plan(
                """
                {"id":"a","type":"action","action":"mine","args":{},"retries":1,"verify":{"type":"action_ok"}},
                {"id":"b","type":"action","action":"mine","args":{},"dependsOn":["a"]}
                """,
                """
                {"type":"action_ok"}
                """);
        PlanResult r = new PlanExecutor(port, facts, p.budget, null).execute(p);
        assertFalse(r.ok);
        assertEquals("a", r.failedTask);
        assertEquals(TaskNode.Status.SKIPPED, p.tasks.get(1).status);
    }

    @Test
    void stopsWhenBudgetExhausted() throws Exception {
        FakeFacts facts = new FakeFacts();
        FakePort port = new FakePort();
        TaskPlan p = TaskPlan.parse("""
                {"version":1,"goal":"test","success":[{"type":"action_ok"}],"budget":{"actions":1,"llmCalls":2,"seconds":30},"tasks":[
                  {"id":"a","type":"action","action":"mine","args":{}},
                  {"id":"b","type":"action","action":"mine","args":{}}
                ]}
                """);
        PlanResult r = new PlanExecutor(port, facts, p.budget, null).execute(p);
        assertFalse(r.ok);
        assertTrue(r.error.contains("budget"), r.error);
    }
}
