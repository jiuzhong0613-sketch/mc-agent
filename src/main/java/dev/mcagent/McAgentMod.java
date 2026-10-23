package dev.mcagent;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.mcagent.client.AgentKeys;
import dev.mcagent.client.AgentScreen;
import dev.mcagent.llm.AgentLoop;
import dev.mcagent.llm.SecretStore;
import dev.mcagent.plan.PlanRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side entry point. This mod must never load on a dedicated server.
 */
@Mod(McAgentMod.MODID)
public final class McAgentMod {
    public static final String MODID = "mcagent";
    private static final Logger LOGGER = LogUtils.getLogger();

    public McAgentMod() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AgentConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onRegisterKeys);
        LOGGER.info("[mcagent] loaded, waiting for a world");
    }

    public void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(AgentKeys.OPEN);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AgentRuntime.get().tick();
        Minecraft mc = Minecraft.getInstance();
        while (AgentKeys.OPEN.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new AgentScreen(null));
            }
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        boolean plan = PlanRunner.get().isRunning();
        boolean agent = AgentLoop.get().isRunning();
        if (!plan && !agent) {
            return;
        }
        String text = plan ? "MC Agent: plan running" : "MC Agent: agent running";
        event.getGuiGraphics().drawString(Minecraft.getInstance().font, text, 4, 4, 0x55FF55);
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Path marker = FMLPaths.CONFIGDIR.get().resolve("mcagent").resolve("guide_shown");
        if (Files.exists(marker)) {
            return;
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "1");
        } catch (IOException ignored) {
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "[mcagent] Press K for the panel. /agent plan \"goal\" to run a plan. "
                                + "/agent goto walks to the block you look at."), false);
            }
        });
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        AgentRuntime.get().addEvent("chat", event.getMessage().getString());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("agent")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("plan")
                        .executes(ctx -> {
                            send(ctx.getSource(), PlanRunner.get().show());
                            return 1;
                        })
                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    PlanRunner.get().start(StringArgumentType.getString(ctx, "goal"), false);
                                    return 1;
                                })))
                .then(Commands.literal("dry")
                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    PlanRunner.get().start(StringArgumentType.getString(ctx, "goal"), true);
                                    return 1;
                                })))
                .then(Commands.literal("goto").executes(ctx -> gotoLook(ctx.getSource())))
                .then(Commands.literal("why").executes(ctx -> {
                    send(ctx.getSource(), PlanRunner.get().why());
                    return 1;
                }))
                .then(Commands.literal("resume").executes(ctx -> {
                    PlanRunner.get().resume();
                    return 1;
                }))
                .then(Commands.literal("start")
                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    AgentLoop.get().start(StringArgumentType.getString(ctx, "goal"));
                                    return 1;
                                })))
                .then(Commands.literal("stop").executes(ctx -> {
                    AgentLoop.get().stop();
                    PlanRunner.get().stop();
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("key").executes(ctx -> {
                    send(ctx.getSource(), "key=" + (SecretStore.present() ? "set" : "missing")
                            + "; press K to set it; env " + SecretStore.ENV
                            + "; file " + SecretStore.file());
                    return 1;
                })));
    }

    private static int gotoLook(net.minecraft.commands.CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        BlockPos target;
        if (hit instanceof BlockHitResult block) {
            target = block.getBlockPos().above();
        } else if (hit instanceof EntityHitResult entity) {
            target = entity.getEntity().blockPosition();
        } else {
            send(source, "look at a block or entity first");
            return 0;
        }
        JsonObject request = new JsonObject();
        request.addProperty("type", "path_to");
        request.addProperty("x", target.getX());
        request.addProperty("y", target.getY());
        request.addProperty("z", target.getZ());
        try {
            JsonObject result = AgentRuntime.get().submit(request);
            send(source, "path_to queued: " + result);
        } catch (Exception e) {
            send(source, "failed: " + e.getMessage());
            return 0;
        }
        return 1;
    }

    private static void send(net.minecraft.commands.CommandSourceStack source, String message) {
        source.sendSystemMessage(Component.literal("[agent] " + message));
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("[agent] " + AgentLoop.get().status()));
        return 1;
    }
}
