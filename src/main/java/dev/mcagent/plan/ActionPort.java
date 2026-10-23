package dev.mcagent.plan;

import com.google.gson.JsonObject;

/**
 * Executes plan nodes. The Minecraft implementation delegates to AgentRuntime,
 * QueryRegistry and SkillRunner.
 */
public interface ActionPort {
    JsonObject runAction(String type, JsonObject args) throws Exception;

    JsonObject runQuery(String name, JsonObject args) throws Exception;

    JsonObject runSkill(String name, JsonObject args) throws Exception;

    JsonObject runMacro(String name, JsonObject args) throws Exception;
}
