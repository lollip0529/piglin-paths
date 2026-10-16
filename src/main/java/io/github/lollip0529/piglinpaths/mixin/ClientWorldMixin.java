package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {
    // every client-side block change ends up here, the player's own placements and the server's updates alike
    @Inject(method = "updateListeners", at = @At("HEAD"))
    private void piglinpaths$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) PiglinPaths.blockChanged(pos);
    }
}
