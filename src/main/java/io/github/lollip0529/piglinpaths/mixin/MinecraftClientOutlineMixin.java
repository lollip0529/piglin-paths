package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientOutlineMixin {
    // WorldRenderer asks this per entity whether to draw the glowing outline
    @Inject(method = "method_27022", at = @At("HEAD"), cancellable = true)
    private void piglinpaths$outline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (PiglinPaths.highlighted(entity)) cir.setReturnValue(true);
    }
}
