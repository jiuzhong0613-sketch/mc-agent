package dev.mcagent;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
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
}
