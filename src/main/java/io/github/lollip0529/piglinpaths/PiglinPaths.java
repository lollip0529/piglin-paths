package io.github.lollip0529.piglinpaths;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lollip0529.piglinpaths.mixin.KeyBindingAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PiglinPaths {
    // plain names: without Fabric API nothing loads a lang file, and the Controls screen shows the key as is
    public static final String CATEGORY = category("Piglin Paths");
    public static final KeyBinding OUTLINES = new KeyBinding("Piglin path outlines", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyBinding COUNTS = new KeyBinding("Piglin path counts", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyBinding STICK = new KeyBinding("Give piglin stick", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final List<KeyBinding> KEYS = Arrays.asList(OUTLINES, COUNTS, STICK);

    private static final long NONE = Long.MIN_VALUE;
    public static final int GREEN_RGB = 0x4DFF4D;

    public static boolean outlines, counts;
    public static Reach reach;
    // the piglin the piglin stick was used on, and everywhere it can path to
    public static int picked = -1;
    public static LongSet pickedBlocks;
    // the block the piglin stick was used on, and the piglins that could get onto it chasing
    public static long target = NONE;
    public static final IntSet targetPiglins = new IntOpenHashSet();
    // what the last frame drew, for the e2e
    public static int blocksDrawn, labelsDrawn;
    private static final float[] RED = {1, 0.25F, 0.25F}, ORANGE = {1, 0.55F, 0.1F}, GREEN = {0.3F, 1, 0.3F}, WHITE = {1, 1, 1};
    // corner marks half a texture pixel thick with arms a quarter of a block long, just off the face
    private static final float W = 1 / 32F, ARM = 1 / 4F, OUT = 0.002F;
    private static final Int2ObjectMap<Tracked> tracked = new Int2ObjectOpenHashMap<>();
    private static boolean dirty;

    private PiglinPaths() {
    }

    // last in Controls; it sorts categories through this map and throws on one it doesn't know
    private static String category(String name) {
        Map<String, Integer> order = KeyBindingAccessor.getCategoryOrderMap();
        order.putIfAbsent(name, Collections.max(order.values()) + 1);
        return name;
    }

    public static void keys() {
        while (OUTLINES.wasPressed()) {
            outlines = !outlines;
            dirty = true;
        }
        while (COUNTS.wasPressed()) {
            counts = !counts;
            dirty = true;
        }
        while (STICK.wasPressed()) giveStick(MinecraftClient.getInstance());
    }

    // a stick tagged PiglinStick:1b; a client-only mod has no item of its own
    public static boolean isStick(ItemStack stack) {
        return stack.getItem() == Items.STICK && stack.getTag() != null && stack.getTag().getBoolean("PiglinStick");
    }

    private static void giveStick(MinecraftClient mc) {
        if (mc.player == null) return;
        if (!mc.player.isCreative()) {
            mc.player.sendChatMessage("/give @s minecraft:stick{PiglinStick:1b,display:{Name:'{\"text\":\"Piglin Stick\",\"italic\":false}'}}");
            return;
        }
        ItemStack stick = new ItemStack(Items.STICK);
        stick.getOrCreateTag().putBoolean("PiglinStick", true);
        stick.setCustomName(new LiteralText("Piglin Stick").setStyle(Style.EMPTY.withItalic(false)));
        PlayerInventory inv = mc.player.inventory;
        int slot = inv.getStack(inv.selectedSlot).isEmpty() ? inv.selectedSlot : inv.getEmptySlot();
        if (slot < 0 || slot > 8) slot = inv.selectedSlot;
        inv.setStack(slot, stick);
        // the creative packet numbers the hotbar 36-44
        mc.interactionManager.clickCreativeStack(stick, 36 + slot);
    }

    // again on the same block lets it go
    public static void target(BlockPos pos) {
        target = target == pos.asLong() ? NONE : pos.asLong();
        // they need their chase worked out, which isn't done while nothing's targeted
        for (Tracked t : tracked.values()) t.stale = true;
        dirty = true;
    }

    public static boolean highlighted(Entity entity) {
        return target != NONE && targetPiglins.contains(entity.getEntityId());
    }

    // again on the same piglin puts it down
    public static void pick(Entity entity) {
        picked = picked == entity.getEntityId() ? -1 : entity.getEntityId();
        pickedBlocks = null;
        dirty = true;
    }

    public static void tick(MinecraftClient mc) {
        boolean overlay = outlines || counts;
        Entity pick = picked == -1 || mc.world == null ? null : mc.world.getEntityById(picked);
        if (!(pick instanceof PiglinEntity) || !pick.isAlive()) {
            pick = null;
            picked = -1;
            pickedBlocks = null;
        }
        if (!overlay && pick == null && target == NONE || mc.world == null || mc.player == null) {
            reach = null;
            tracked.clear();
            targetPiglins.clear();
            return;
        }
        List<PiglinEntity> piglins = overlay || target != NONE
                ? mc.world.getEntities(PiglinEntity.class, mc.player.getBoundingBox().expand(64), Entity::isAlive) : new ArrayList<>();
        if (pick != null && !piglins.contains(pick)) piglins.add((PiglinEntity) pick);
        IntSet ids = new IntOpenHashSet();
        for (PiglinEntity p : piglins) ids.add(p.getEntityId());
        boolean changed = tracked.keySet().retainAll(ids);

        // a pass over every piglin is tens of ms, so about 8 ms a tick: new ones first, then ones a block changed
        // near, then ones that moved, then whichever is oldest once it's 2 s old
        for (PiglinEntity p : piglins) {
            Tracked t = tracked.get(p.getEntityId());
            if (t != null) t.age++;
        }
        long start = System.nanoTime();
        do {
            PiglinEntity next = null;
            int best = -1;
            for (PiglinEntity p : piglins) {
                Tracked t = tracked.get(p.getEntityId());
                int score;
                if (t == null) score = Integer.MAX_VALUE;
                else if (t.stale) score = 200_000 + t.age;
                else if (t.pos != p.getBlockPos().asLong()) score = 100_000 + t.age;
                else score = t.age >= 40 ? t.age : -1;
                if (score > best) {
                    best = score;
                    next = p;
                }
            }
            if (next == null) break;
            Tracked t = new Tracked();
            t.pos = next.getBlockPos().asLong();
            t.result = Reach.piglin(mc.world, next);
            if (target != NONE) t.chase = Reach.chase(mc.world, next, mc.player);
            tracked.put(next.getEntityId(), t);
            changed = true;
        } while (System.nanoTime() - start < 8_000_000);
        if (changed || dirty) {
            dirty = false;
            List<Reach.Piglin> results = new ArrayList<>();
            for (Tracked t : tracked.values()) results.add(t.result);
            reach = overlay ? Reach.of(results) : null;
            Tracked t = tracked.get(picked);
            pickedBlocks = t == null ? null : t.result.reached;
            targetPiglins.clear();
            if (target != NONE) {
                for (Int2ObjectMap.Entry<Tracked> e : tracked.int2ObjectEntrySet()) {
                    LongSet chase = e.getValue().chase;
                    if (chase != null && chase.contains(target)) targetPiglins.add(e.getIntKey());
                }
            }
        }
    }

    public static void render(MatrixStack matrices, Camera camera) {
        blocksDrawn = labelsDrawn = 0;
        Reach r = reach;
        boolean marks = pickedBlocks != null || outlines && r != null || target != NONE;
        if (!marks && (!counts || r == null)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate consumers = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = camera.getPos();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        if (marks) {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
            Matrix4f model = matrices.peek().getModel();
            // a picked piglin takes over the outlines
            if (pickedBlocks != null) {
                marks(model, buffer, mc.world, pickedBlocks, GREEN);
            } else if (outlines && r != null) {
                marks(model, buffer, mc.world, r.green, GREEN);
                marks(model, buffer, mc.world, r.orange, ORANGE);
                marks(model, buffer, mc.world, r.red, RED);
            }
            if (target != NONE) marks(model, buffer, mc.world, LongSets.singleton(target), WHITE);
            RenderSystem.disableTexture();
            RenderSystem.disableCull();
            // vanilla leaves the depth test off by this point; without it the marks show through walls
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1, -10);
            tessellator.draw();
            RenderSystem.polygonOffset(0, 0);
            RenderSystem.disablePolygonOffset();
            RenderSystem.enableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.enableTexture();
        }

        if (counts && r != null) {
            TextRenderer text = mc.textRenderer;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (Long2IntMap.Entry e : r.counts.long2IntEntrySet()) {
                pos.set(e.getLongKey());
                String s = Integer.toString(e.getIntValue());
                matrices.push();
                // flat on the top face, upright for whoever's looking
                matrices.translate(pos.getX() + 0.5, pos.getY() + top(mc.world, pos) + 0.01, pos.getZ() + 0.5);
                matrices.multiply(Vector3f.POSITIVE_Y.getDegreesQuaternion(180 - camera.getYaw()));
                matrices.multiply(Vector3f.POSITIVE_X.getDegreesQuaternion(90));
                matrices.scale(1 / 24F, 1 / 24F, 1 / 24F);
                text.draw(s, -text.getWidth(s) / 2F, -3.5F, 0xFFFFFFFF, false, matrices.peek().getModel(), consumers, false, 0, 0xF000F0);
                matrices.pop();
                labelsDrawn++;
            }
        }
        matrices.pop();
        // the text goes in a fallback buffer, so draw it here rather than wherever vanilla flushes next
        consumers.draw();
    }

    // a placed, broken or flowing block: every piglin whose search box it's in goes again
    public static void blockChanged(BlockPos pos) {
        for (Tracked t : tracked.values()) {
            BlockPos at = BlockPos.fromLong(t.pos);
            if (Math.abs(pos.getX() - at.getX()) <= 25 && Math.abs(pos.getY() - at.getY()) <= 25 && Math.abs(pos.getZ() - at.getZ()) <= 25) {
                t.stale = true;
            }
        }
    }

    // marks in the corners of the face a piglin stands on, at its real height for slabs and stairs.
    // neighbours' marks sit side by side, never on top of each other
    private static void marks(Matrix4f model, BufferBuilder buffer, World world, LongSet set, float[] c) {
        BlockPos.Mutable pos = new BlockPos.Mutable(), above = new BlockPos.Mutable();
        LongIterator it = set.iterator();
        while (it.hasNext()) {
            pos.set(it.nextLong());
            above.set(pos).move(Direction.UP);
            BlockState over = world.getBlockState(above);
            if (over.isOpaqueFullCube(world, above) || !over.getFluidState().isEmpty()) continue;
            float y = pos.getY() + (float) top(world, pos) + OUT;
            for (int cx = 0; cx <= 1; cx++) {
                for (int cz = 0; cz <= 1; cz++) {
                    // an L in this corner, its arms along the two edges that meet there
                    float x = pos.getX() + cx * (1 - ARM), z = pos.getZ() + cz * (1 - W);
                    quad(model, buffer, x, z, x + ARM, z + W, y, c);
                    x = pos.getX() + cx * (1 - W);
                    z = pos.getZ() + (cz == 0 ? W : 1 - ARM);
                    quad(model, buffer, x, z, x + W, z + ARM - W, y, c);
                }
            }
            blocksDrawn++;
        }
    }

    private static void quad(Matrix4f model, BufferBuilder buffer, float x0, float z0, float x1, float z1, float y, float[] c) {
        buffer.vertex(model, x0, y, z0).color(c[0], c[1], c[2], 1).next();
        buffer.vertex(model, x1, y, z0).color(c[0], c[1], c[2], 1).next();
        buffer.vertex(model, x1, y, z1).color(c[0], c[1], c[2], 1).next();
        buffer.vertex(model, x0, y, z1).color(c[0], c[1], c[2], 1).next();
    }

    // where a mob's feet are on this block: 1 for a full one, less for a slab or carpet
    private static double top(World world, BlockPos pos) {
        VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
        return shape.isEmpty() ? 1 : shape.getMax(Direction.Axis.Y);
    }

    private static final class Tracked {
        long pos;
        int age;
        boolean stale;
        Reach.Piglin result;
        LongSet chase;
    }
}
