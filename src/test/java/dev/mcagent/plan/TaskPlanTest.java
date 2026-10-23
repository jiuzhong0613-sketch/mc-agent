package dev.mcagent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskPlanTest {
    private static String plan(String tasks, String success) {
        return """
                {"version":1,"goal":"test","success":[%s],"tasks":[%s]}
                """.formatted(success, tasks);
    }

    @Test
    void parsesAndOrdersDependencies() throws Exception {
        TaskPlan p = TaskPlan.parse(plan(
                """
                {"id":"t2","type":"action","action":"mine","args":{"x":1,"y":2,"z":3},"dependsOn":["t1"]},
                {"id":"t1","type":"query","tool":"query_env","args":{}}
                """,
                """
                {"type":"action_ok"}
                """));
        List<TaskNode> ordered = p.topological();
        assertEquals("t1", ordered.get(0).id);
        assertEquals("t2", ordered.get(1).id);
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(PlanException.class, () -> TaskPlan.parse(plan(
                """
                {"id":"t1","type":"query","tool":"query_env"},
                {"id":"t1","type":"query","tool":"query_env"}
                """,
                """
                {"type":"action_ok"}
                """)));
    }

    @Test
    void rejectsUnknownDependency() {
        assertThrows(PlanException.class, () -> TaskPlan.parse(plan(
                """
                {"id":"t1","type":"query","tool":"query_env","dependsOn":["nope"]}
                """,
                """
                {"type":"action_ok"}
                """)));
    }

    @Test
    void rejectsCycle() {
        assertThrows(PlanException.class, () -> TaskPlan.parse(plan(
                """
                {"id":"a","type":"query","tool":"query_env","dependsOn":["b"]},
                {"id":"b","type":"query","tool":"query_env","dependsOn":["a"]}
                """,
                """
                {"type":"action_ok"}
                """)));
    }

    @Test
    void requiresSuccessPredicate() {
        assertThrows(PlanException.class, () -> TaskPlan.parse(plan(
                """
                {"id":"t1","type":"query","tool":"query_env"}
                """,
                "")));
    }
}
