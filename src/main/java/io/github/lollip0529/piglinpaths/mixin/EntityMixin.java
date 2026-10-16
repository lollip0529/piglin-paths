package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    // the outline's colour: green rather than glowing's white
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void piglinpaths$green(CallbackInfoReturnable<Integer> cir) {
        if (PiglinPaths.highlighted((Entity) (Object) this)) cir.setReturnValue(PiglinPaths.GREEN_RGB);
    }
}
