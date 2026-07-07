package com.gtofast.mixin;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gtofast.GTOFast;
import com.gtofast.NativeAgent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for RecipeLogic: registers/unregisters each instance
 * into the native linked list for watchdog correction.
 *
 * Duration patching is handled by JVMTI bytecode injection
 * (ClassFileLoadHook patches RecipeModifier.overclocking()).
 */
@Mixin(value = RecipeLogic.class, remap = false)
public abstract class RecipeLogicMixin {

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtofast$onConstruct(CallbackInfo ci) {
        try {
            NativeAgent.nativeRegisterRecipeLogic(this);
        } catch (Throwable t) {
            GTOFast.LOGGER.debug("[GTOFast-Mixin] register err: {}", t.getMessage());
        }
    }

    @Inject(method = "onMachineUnLoad", at = @At("HEAD"), remap = false)
    private void gtofast$onUnload(CallbackInfo ci) {
        try {
            NativeAgent.nativeUnregisterRecipeLogic(this);
        } catch (Throwable ignored) {
        }
    }
}
