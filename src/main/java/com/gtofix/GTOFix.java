package com.gtofix;

import com.gtofix.config.GTOFixConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * GTO Fix — configurable recipe speed multiplier for GTOCore.
 * Config: config/gtofix.json
 */
@Mod(GTOFix.MOD_ID)
public class GTOFix {

    public static final String MOD_ID = "gtofast";
    static final Logger LOGGER = LogUtils.getLogger();

    public GTOFix() {
        GTOFixConfig.load();

        // RecipeModifier overclocking hook — wraps static final modifiers
        // with Proxy to apply durationFactor post-overclocking.
        // Fixes electric furnace and standard machines that go through
        // the overclocking path.
        Patcher.hookOverclocking();

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("[GTOFix] Server started, factor={}", GTOFixConfig.getDurationFactor());
        applyRecipeSpeedModifier(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        Scanner.stop();
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager rm,
                                                   ProfilerFiller prepProfiler, ProfilerFiller applyProfiler,
                                                   Executor prepExec, Executor applyExec) {
                return CompletableFuture.supplyAsync(() -> null, prepExec)
                    .thenCompose(barrier::wait)
                    .thenAcceptAsync(v -> {
                        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                        if (server != null) applyRecipeSpeedModifier(server);
                    }, applyExec);
            }
        });
    }

    static void applyRecipeSpeedModifier(MinecraftServer server) {
        if (server == null) return;

        Patcher.originalDurations.clear();
        Patcher.patchGTRecipes(server);
        Patcher.patchVanillaRecipes(server);

        double factor = GTOFixConfig.getDurationFactor();
        if (factor <= 0.0 || factor != 1.0) {
            Scanner.start(server);
        } else {
            Scanner.stop();
        }
    }
}
