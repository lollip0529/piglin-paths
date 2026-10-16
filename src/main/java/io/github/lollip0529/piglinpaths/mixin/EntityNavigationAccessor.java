package io.github.lollip0529.piglinpaths.mixin;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityNavigation.class)
public interface EntityNavigationAccessor {
    @Accessor
    PathNodeMaker getNodeMaker();
}
