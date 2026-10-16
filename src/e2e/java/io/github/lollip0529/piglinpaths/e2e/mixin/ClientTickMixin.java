package io.github.lollip0529.piglinpaths.e2e.mixin;

import io.github.lollip0529.piglinpaths.e2e.E2e;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class ClientTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void e2e$tick(CallbackInfo ci) {
        E2e.tick((MinecraftClient) (Object) this);
    }
}
