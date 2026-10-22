package dev.mcagent;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.mcagent.llm.AgentLoop;
import dev.mcagent.llm.SecretStore;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

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
        LOGGER.info("[mcagent] loaded, waiting for a world");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            AgentRuntime.get().tick();
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        AgentRuntime.get().addEvent("chat", event.getMessage().getString());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("agent")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("start")
                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    AgentLoop.get().start(StringArgumentType.getString(ctx, "goal"));
                                    return 1;
                                })))
                .then(Commands.literal("stop").executes(ctx -> {
                    AgentLoop.get().stop();
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("key").executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "[agent] key=" + (SecretStore.present() ? "set" : "missing")
                                    + "; env " + SecretStore.ENV
                                    + "; file " + SecretStore.file()));
                    return 1;
                })));
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("[agent] " + AgentLoop.get().status()));
        return 1;
    }
}
