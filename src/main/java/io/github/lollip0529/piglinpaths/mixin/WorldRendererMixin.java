package io.github.lollip0529.piglinpaths.mixin;

import io.github.lollip0529.piglinpaths.PiglinPaths;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    // just before the block outline; a little later the debug renderer pushes the camera rotation onto the GL
    // matrix, and anything flushed under it is rotated twice
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;checkEmpty(Lnet/minecraft/client/util/math/MatrixStack;)V", ordinal = 2))
    private void piglinpaths$render(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightmapTextureManager lightmap, Matrix4f projection, CallbackInfo ci) {
        PiglinPaths.render(matrices, camera);
    }

    // the outline colour is set just before each entity is drawn, by vanilla from its team and by mods like
    // BastionHelper on top; this runs after both, so a highlighted piglin is green whatever it glowed before
    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void piglinpaths$green(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, MatrixStack matrices,
                                   VertexConsumerProvider consumers, CallbackInfo ci) {
        if (consumers instanceof OutlineVertexConsumerProvider && PiglinPaths.highlighted(entity)) {
            int c = PiglinPaths.GREEN_RGB;
            ((OutlineVertexConsumerProvider) consumers).setColor(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, 255);
        }
    }
}
