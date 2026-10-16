package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the piglin stick's clicks stay on this side: nothing goes to the server, so no chest opens and no button presses
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"), cancellable = true)
    private void piglinpaths$atLocation(PlayerEntity player, Entity entity, EntityHitResult hitResult, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (entity instanceof PiglinEntity && PiglinPaths.isStick(player.getStackInHand(hand))) cir.setReturnValue(ActionResult.PASS);
    }

    @Inject(method = "interactEntity", at = @At("HEAD"), cancellable = true)
    private void piglinpaths$pick(PlayerEntity player, Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (entity instanceof PiglinEntity && PiglinPaths.isStick(player.getStackInHand(hand))) {
            PiglinPaths.pick(entity);
            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void piglinpaths$target(ClientPlayerEntity player, ClientWorld world, Hand hand, BlockHitResult hitResult,
                                    CallbackInfoReturnable<ActionResult> cir) {
        if (PiglinPaths.isStick(player.getStackInHand(hand))) {
            PiglinPaths.target(hitResult.getBlockPos());
            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }
}
