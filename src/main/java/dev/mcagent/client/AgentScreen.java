package dev.mcagent.client;

import dev.mcagent.llm.AgentLoop;
import dev.mcagent.llm.SecretStore;
import dev.mcagent.plan.PlanRunner;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One screen for the whole setup: paste a key, type a goal, start or stop.
 * The key is never shown again after saving.
 */
public final class AgentScreen extends Screen {
    private final Screen parent;
    private EditBox keyField;
    private EditBox goalField;
    private Component status = Component.empty();

    public AgentScreen(Screen parent) {
        super(Component.literal("MC Agent"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        keyField = new EditBox(font, left, 56, 200, 20, Component.literal("API key"));
        keyField.setMaxLength(256);
        keyField.setSuggestion("paste your hosted API key");
        addRenderableWidget(keyField);

        addRenderableWidget(Button.builder(Component.literal("Save key"), b -> saveKey())
                .bounds(left, 80, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            keyField.setValue("");
            status = Component.literal("not saved");
        }).bounds(left + 102, 80, 98, 20).build());

        goalField = new EditBox(font, left, 116, 200, 20, Component.literal("Goal"));
        goalField.setMaxLength(200);
        goalField.setSuggestion("e.g. walk to 120 68 -34");
        addRenderableWidget(goalField);

        addRenderableWidget(Button.builder(Component.literal("Start"), b -> start())
                .bounds(left, 140, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> stop())
                .bounds(left + 102, 140, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + 51, 170, 98, 20).build());
    }

    private void saveKey() {
        String value = keyField.getValue().trim();
        if (value.isEmpty()) {
            status = Component.literal("Key is empty");
            return;
        }
        try {
            SecretStore.save(value);
            keyField.setValue("");
            status = Component.literal("Key saved");
        } catch (Exception e) {
            status = Component.literal("Save failed: " + e.getMessage());
        }
    }

    private void start() {
        String goal = goalField.getValue().trim();
        if (goal.isEmpty()) {
            status = Component.literal("Enter a goal first");
            return;
        }
        PlanRunner.get().start(goal, false);
        onClose();
    }

    private void stop() {
        AgentLoop.get().stop();
        PlanRunner.get().stop();
        status = Component.literal("Stopped");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 100;
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        graphics.drawString(font, "Key: " + (SecretStore.present() ? "set" : "missing"),
                left, 44, 0xAAAAAA);
        graphics.drawString(font, "Goal (natural language)", left, 104, 0xAAAAAA);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, 196, 0xFFFF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
