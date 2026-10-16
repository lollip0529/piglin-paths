package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Mutable
    @Final
    @Shadow
    public KeyBinding[] keysAll;

    // before load() so the bindings are read back from options.txt
    @Inject(method = "load", at = @At("HEAD"))
    private void piglinpaths$addKeys(CallbackInfo ci) {
        for (KeyBinding key : PiglinPaths.KEYS) {
            if (!ArrayUtils.contains(this.keysAll, key)) {
                this.keysAll = ArrayUtils.add(this.keysAll, key);
            }
        }
    }
}
