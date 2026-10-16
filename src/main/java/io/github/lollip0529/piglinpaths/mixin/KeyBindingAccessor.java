package io.github.lollip0529.piglinpaths.mixin;

import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor
    static Map<String, Integer> getCategoryOrderMap() {
        throw new AssertionError();
    }
}
