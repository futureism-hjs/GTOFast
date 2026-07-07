package com.gtofast;

import com.gtofast.config.GTOFastConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * GTO Fast -- configurable recipe speed multiplier for GTOCore.
 * Config: config/gtofast.json
 *
 * Two modes:
 *   factor <= 0: 1-tick mode (JVMTI bytecode patch + native watchdog)
 *   factor > 0:  scale mode (OverclockCorrector TickEvent)
 */
@Mod(GTOFast.MOD_ID)
public class GTOFast {

    public static final String MOD_ID = "gtofast";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GTOFast() {
        GTOFastConfig.load();
        MinecraftForge.EVENT_BUS.register(this);

        double factor = GTOFastConfig.getDurationFactor();

        // factor==0: JVMTI + native watchdog (1-tick mode)
        if (factor <= 0.0) {
            LOGGER.info("[GTOFast] Factor=0 (1-tick mode), initializing JVMTI agent...");
            NativeAgent.init();
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        double factor = GTOFastConfig.getDurationFactor();
        LOGGER.info("[GTOFast] Server started, factor={}", factor);

        // factor==0: JVMTI handles overclocking (electric furnace fix)
        // factor>0:  OverclockCorrector TickEvent
        // Both:     Patcher handles recipe data via reflection
        if (factor > 0.0) {
            OverclockCorrector.reset();
        }
        Patcher.originalDurations.clear();
        Patcher.patchGTRecipes(event.getServer());
        Patcher.patchVanillaRecipes(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // OverclockCorrector only for factor>0 (scale mode)
        if (event.phase == TickEvent.Phase.END && GTOFastConfig.getDurationFactor() > 0.0) {
            OverclockCorrector.onTick(ServerLifecycleHooks.getCurrentServer());
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        OverclockCorrector.reset();
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
                        if (server == null) return;

                        double factor = GTOFastConfig.getDurationFactor();
                        if (factor > 0.0) OverclockCorrector.reset();
                        Patcher.originalDurations.clear();
                        Patcher.patchGTRecipes(server);
                        Patcher.patchVanillaRecipes(server);
                    }, applyExec);
            }
        });
    }
}
