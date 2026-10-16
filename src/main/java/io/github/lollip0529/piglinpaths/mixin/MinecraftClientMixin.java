package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "handleInputEvents", at = @At("TAIL"))
    private void piglinpaths$keys(CallbackInfo ci) {
        PiglinPaths.keys();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void piglinpaths$tick(CallbackInfo ci) {
        PiglinPaths.tick((MinecraftClient) (Object) this);
    }
}
