package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcagent.AgentConfig;
import dev.mcagent.bridge.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal OpenAI-compatible chat completions client.
 */
public final class LlmClient {
    public static final class LlmException extends Exception {
        public final int status;

        public LlmException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public JsonObject chat(JsonArray messages, JsonArray tools) throws Exception {
        String key = SecretStore.key();
        if (key.isEmpty()) {
            throw new LlmException(0,
                    "no API key; set " + SecretStore.ENV + " or write config/mcagent/secret.txt");
        }
        String base = AgentConfig.BASE_URL.get().trim();
        ApiGate.check(base);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", AgentConfig.MODEL.get());
        body.add("messages", messages);
        if (tools != null) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        body.addProperty("temperature", AgentConfig.TEMPERATURE.get());
        body.addProperty("max_tokens", AgentConfig.MAX_TOKENS.get());
        body.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(AgentConfig.REQUEST_TIMEOUT.get()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new LlmException(status, "HTTP " + status + ": " + trim(response.body()));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(status, "empty choices in response");
        }
        return choices.get(0).getAsJsonObject().getAsJsonObject("message");
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }
}
