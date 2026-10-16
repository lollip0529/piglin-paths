package io.github.lollip0529.piglinpaths.e2e;

import com.google.gson.GsonBuilder;
import io.github.lollip0529.piglinpaths.PiglinPaths;
import io.github.lollip0529.piglinpaths.Reach;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.options.ControlsListWidget;
import net.minecraft.client.gui.screen.options.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.tutorial.TutorialStep;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.Structure;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.processor.JigsawReplacementStructureProcessor;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Drives a dev client through two scenes and checks Piglin Paths against vanilla, writing e2e-artifacts/.
 *
 * Arena, superflat floor at y 3, piglins at 0 4 0 and 6 4 0:
 *   pillar  3 4 -6 .. 4 6 -5, too tall to climb: not red, that's the ground itself
 *   island  -8 3 -1 .. -6 3 1 inside a ring of lava 2 deep: the shore next to the lava is red, 2 out green
 *   step    -3 4 -4, one block up, reachable
 *   pool    lava at -4 4 6 on the floor inside a stone rim: the rim is red, the floor under the lava isn't
 *   magma   4 3 6: the floor round it is orange, the magma itself isn't
 *   wall    -12 4 9 .. 9 6 9, the floor behind it is past a long detour: not red
 *   pit     12 1 12 .. 14 3 14, 3 deep with a piglin in it that can't climb out
 * Bridge, vanilla's bastion/bridge/bridge_pieces/bridge at 60 40 -8 over a lava basin, three piglins on the deck.
 *
 * Ways it can be wrong, each covered by a check:
 *   the keys aren't in their Controls section, or break its sorting, or don't toggle anything
 *   a block is counted differently from what vanilla's findPathTo says, per piglin, or red is reachable
 *   green isn't exactly the reachable blocks touching red
 *   lava placed or removed near a piglin takes longer than 2 ticks to show
 *   the marks aren't on the blocks, are hidden by a neighbour's, or show through a wall
 *   the renderer doesn't draw what the sets hold, or keeps drawing once toggled off
 *   the stick key doesn't give a piglin stick, or its right-click on a piglin doesn't show that piglin's findPathTo blocks
 *   its right-click on a block outlines other piglins than the ones that get onto it chasing, path after path,
 *   or doesn't turn a piglin already glowing in a team colour green
 */
public final class E2e {
    private static final File OUT = new File(System.getProperty("piglinpaths.e2e.out", "e2e-artifacts"));
    private static final int BRIDGE_X = 60, BRIDGE_Y = 40, BRIDGE_Z = -8;
    private static final Box SCENES = new Box(-100, 0, -100, 200, 255, 100);
    private static final int TEAM_RED = 0xFF5555;
    private static final String PIGLIN = " {NoAI:1b,PersistenceRequired:1b,IsImmuneToZombification:1b}";
    private static final InputUtil.Key OUTLINE_KEY = InputUtil.fromKeyCode(GLFW.GLFW_KEY_KP_1, 0);
    private static final InputUtil.Key COUNT_KEY = InputUtil.fromKeyCode(GLFW.GLFW_KEY_KP_2, 0);
    private static final InputUtil.Key STICK_KEY = InputUtil.fromKeyCode(GLFW.GLFW_KEY_KP_3, 0);

    private static final List<Map<String, Object>> checks = new ArrayList<>();
    private static final Map<String, Object> facts = new LinkedHashMap<>();
    private static int step, wait, ticks;
    private static boolean done;
    private static LongSet everything;

    private E2e() {
    }

    private static void next(int ticks) {
        step++;
        wait = ticks;
    }

    public static void tick(MinecraftClient mc) {
        if (done) return;
        try {
            if (++ticks > 20 * 60 * 5) throw new IllegalStateException("timed out at step " + step);
            // tp'd to look down from the air; a creative player who lands stops flying and drops out of the shot
            if (mc.player != null) mc.player.abilities.flying = true;
            if (wait > 0) {
                wait--;
                return;
            }
            step(mc);
        } catch (Throwable t) {
            t.printStackTrace();
            finish(mc, t);
        }
    }

    private static void step(MinecraftClient mc) throws Exception {
        switch (step) {
            case 0: {
                if (!(mc.currentScreen instanceof TitleScreen)) return;
                GameOptions o = mc.options;
                o.viewDistance = 8;
                o.fov = 70;
                o.guiScale = 2;
                o.pauseOnLostFocus = false;
                o.tutorialStep = TutorialStep.NONE;
                o.write();
                mc.onResolutionChanged();

                Properties p = new Properties();
                p.setProperty("level-type", "flat");
                p.setProperty("generate-structures", "false");
                p.setProperty("level-seed", "piglinpaths");
                // not peaceful, that removes piglins
                LevelInfo info = new LevelInfo("piglinpaths-e2e", GameMode.CREATIVE, false, Difficulty.EASY, true, new GameRules(), DataPackSettings.SAFE_MODE);
                next(0);
                mc.method_29607("piglinpaths-e2e", info, RegistryTracker.create(), GeneratorOptions.fromProperties(p));
                return;
            }
            case 1: {
                if (mc.world == null || mc.player == null || mc.currentScreen != null) return;
                server(mc, "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
                        "gamerule doFireTick false", "gamerule randomTickSpeed 0", "time set 6000", "weather clear");
                mc.player.abilities.flying = true;
                String[] scene = {
                        "fill 3 4 -6 4 6 -5 stone",
                        "fill -9 2 -2 -5 3 2 lava", "fill -8 2 -1 -6 3 1 stone",
                        "setblock -3 4 -4 stone",
                        "fill -5 4 5 -3 4 7 stone", "setblock -4 4 6 lava",
                        "setblock 4 3 6 magma_block",
                        "fill -12 4 9 9 6 9 stone",
                        "summon piglin 0.5 4 0.5" + PIGLIN, "summon piglin 6.5 4 0.5" + PIGLIN,
                        "fill 12 1 12 14 3 14 air", "summon piglin 13.5 1 13.5" + PIGLIN,
                        "tp @p 40 30 0 90 0"};
                int[] r = server(mc, scene);
                List<String> failed = new ArrayList<>();
                for (int i = 0; i < r.length; i++) if (r[i] <= 0) failed.add(scene[i]);
                check("scene commands that did nothing", "[]", failed.toString());
                next(40);
                return;
            }
            case 2: {
                // after the tp, so the chunks out here are loaded
                int[] r = server(mc, "fill 54 38 -14 97 39 14 stone", "fill 55 39 -13 96 39 13 lava");
                check("lava basin under the bridge", "[2552, 1134]", Arrays.toString(r));
                IntegratedServer server = mc.getServer();
                server.submit(() -> {
                    ServerWorld w = server.getOverworld();
                    Structure s = w.getStructureManager().getStructure(new Identifier("bastion/bridge/bridge_pieces/bridge"));
                    s.place(w, new BlockPos(BRIDGE_X, BRIDGE_Y, BRIDGE_Z),
                            new StructurePlacementData().addProcessor(JigsawReplacementStructureProcessor.INSTANCE), new Random(1));
                }).join();
                // three spots on the deck, picked from the template's heightmap
                List<String> summons = new ArrayList<>();
                for (int[] xz : new int[][]{{6, 8}, {17, 10}, {26, 8}}) {
                    double y = deck(mc, BRIDGE_X + xz[0], BRIDGE_Z + xz[1]);
                    summons.add("summon piglin " + (BRIDGE_X + xz[0] + 0.5) + " " + y + " " + (BRIDGE_Z + xz[1] + 0.5) + PIGLIN);
                }
                r = server(mc, summons.toArray(new String[0]));
                check("bridge piglins summoned", Arrays.toString(new int[]{1, 1, 1}), Arrays.toString(r));
                next(100);
                return;
            }
            case 3: {
                int listed = 0;
                for (KeyBinding k : mc.options.keysAll) if (PiglinPaths.KEYS.contains(k)) listed++;
                check("keys in options.keysAll", 3, listed);
                check("keys unbound by default", "key.keyboard.unknown / key.keyboard.unknown / key.keyboard.unknown",
                        PiglinPaths.OUTLINES.getBoundKeyTranslationKey() + " / " + PiglinPaths.COUNTS.getBoundKeyTranslationKey() + " / " + PiglinPaths.STICK.getBoundKeyTranslationKey());
                boolean sorts = true;
                try {
                    Arrays.sort(mc.options.keysAll.clone());
                } catch (RuntimeException e) {
                    sorts = false;
                }
                check("Controls can sort the keys", true, sorts);
                check("nothing computed while both are off", null, PiglinPaths.reach);
                mc.openScreen(new OptionsScreen(null, mc.options));
                next(5);
                return;
            }
            case 4: {
                // the way a player gets there: Options, then the Controls button, whichever screen that opens
                for (Element e : mc.currentScreen.children()) {
                    if (e instanceof ButtonWidget && ((ButtonWidget) e).getMessage().getString().startsWith("Controls")) ((ButtonWidget) e).onPress();
                }
                next(5);
                return;
            }
            case 5: {
                if (!(mc.currentScreen instanceof ControlsOptionsScreen)) {
                    throw new IllegalStateException("the Controls button opened " + mc.currentScreen);
                }
                ControlsListWidget list = get(mc.currentScreen, ControlsOptionsScreen.class, "keyBindingListWidget");
                List<String> rows = new ArrayList<>();
                int first = -1;
                for (int i = 0; i < list.children().size(); i++) {
                    Object entry = list.children().get(i);
                    if (!(entry instanceof ControlsListWidget.KeyBindingEntry)) continue;
                    KeyBinding k = get(entry, ControlsListWidget.KeyBindingEntry.class, "binding");
                    if (!PiglinPaths.KEYS.contains(k)) continue;
                    rows.add(k.getTranslationKey() + " in " + k.getCategory());
                    if (first < 0) first = i;
                }
                // rows are 20px apart; bring ours to the middle for the screenshot
                list.setScrollAmount(first * 20 - 100);
                rows.sort(null);
                check("rows on the Controls screen", "[Give piglin stick in Piglin Paths, Piglin path counts in Piglin Paths, Piglin path outlines in Piglin Paths]", rows.toString());
                // the widget puts a header row before each new category, ours has to be the last one
                Object last = null;
                for (Object entry : list.children()) if (entry instanceof ControlsListWidget.CategoryEntry) last = entry;
                check("Piglin Paths is the last section", "Piglin Paths", last == null ? null : categoryText(last));
                next(5);
                return;
            }
            case 6: {
                shot(mc, "controls").close();
                mc.openScreen(null);

                // what the Controls screen does when a key is set
                mc.options.setKeyCode(PiglinPaths.OUTLINES, OUTLINE_KEY);
                mc.options.setKeyCode(PiglinPaths.COUNTS, COUNT_KEY);
                mc.options.setKeyCode(PiglinPaths.STICK, STICK_KEY);
                KeyBinding.updateKeysByCode();
                KeyBinding.onKeyPressed(OUTLINE_KEY);
                next(5);
                return;
            }
            case 7:
                check("outline key turns outlines on", "true / false", PiglinPaths.outlines + " / " + PiglinPaths.counts);
                KeyBinding.onKeyPressed(COUNT_KEY);
                next(30);
                return;
            case 8: {
                check("count key turns counts on", "true / true", PiglinPaths.outlines + " / " + PiglinPaths.counts);
                Reach r = PiglinPaths.reach;
                if (r == null) throw new IllegalStateException("no reach computed with both on");
                check("every piglin has a result by now", 6, E2e.<Map<?, ?>>get(null, PiglinPaths.class, "tracked").size());
                facts.put("red / orange / green / counted", r.red.size() + " / " + r.orange.size() + " / " + r.green.size() + " / " + r.counts.size());
                facts.put("on the bridge, red / green / counted", bridge(r.red) + " / " + bridge(r.green) + " / " + bridge(r.counts.keySet()));
                compare(mc, r);
                arena(r);

                // the most one tick spends
                List<PiglinEntity> piglins = mc.world.getEntities(PiglinEntity.class, mc.player.getBoundingBox().expand(64), e -> true);
                Map<Integer, Double> ms = new LinkedHashMap<>();
                for (PiglinEntity p : piglins) {
                    long t = System.nanoTime();
                    for (int i = 0; i < 5; i++) Reach.piglin(mc.world, p);
                    ms.put(p.getEntityId(), (System.nanoTime() - t) / 5e6);
                }
                facts.put("ms per piglin", ms);

                // a block change near a piglin has to show within a few ticks, not at the next 2 s refresh
                server(mc, "setblock 0 4 -8 lava");
                next(4);
                return;
            }
            case 9:
                // the update reaches the client when it reaches it; from then on it's the mod's time
                if (!mc.world.getBlockState(new BlockPos(0, 4, -8)).getFluidState().isEmpty()) {
                    next(2);
                }
                return;
            case 10:
                check("lava placed: the floor beside it red 2 ticks after the client has it", true, PiglinPaths.reach.red.contains(BlockPos.asLong(1, 3, -8)));
                server(mc, "setblock 0 4 -8 air");
                next(0);
                return;
            case 11:
                if (mc.world.getBlockState(new BlockPos(0, 4, -8)).isAir()) {
                    next(2);
                }
                return;
            case 12:
                check("lava removed: red gone 2 ticks after the client has it", false, PiglinPaths.reach.red.contains(BlockPos.asLong(1, 3, -8)));
                // eye level, south of the lava moat, looking at its red shore
                mc.options.hudHidden = true;
                server(mc, "tp @p -6.5 4 -8.5 0 10");
                next(30);
                return;
            case 13: {
                if (mc.player.squaredDistanceTo(-6.5, 4, -8.5) > 0.5) {
                    server(mc, "tp @p -6.5 4 -8.5 0 10");
                    wait = 10;
                    return;
                }
                NativeImage img = shot(mc, "wall_before");
                facts.put("red pixels before the wall", count(img, 0xFF4040));
                img.close();
                // a wall between the camera and the moat, clear of the pillar at x 3
                server(mc, "fill -20 4 -6 1 7 -6 stone");
                next(30);
                return;
            }
            case 14: {
                NativeImage img = shot(mc, "wall_after");
                int before = (Integer) facts.get("red pixels before the wall"), after = count(img, 0xFF4040);
                img.close();
                check("red marks behind a wall don't show through it: before / after", "seen / 0", (before > 50 ? "seen" : "none " + before) + " / " + after);
                server(mc, "fill -20 4 -6 1 7 -6 air");
                mc.options.hudHidden = false;
                next(20);
                return;
            }
            case 15:
                server(mc, "tp @p 1 24 -1 0 75");
                next(60);
                return;
            case 16: {
                Reach r = PiglinPaths.reach;
                check("blocks drawn last frame = red + orange + green", r.red.size() + r.orange.size() + r.green.size(), PiglinPaths.blocksDrawn);
                check("labels drawn last frame = counted blocks", r.counts.size(), PiglinPaths.labelsDrawn);
                shot(mc, "arena").close();
                server(mc, "tp @p 75 66 -16 0 60");
                next(60);
                return;
            }
            case 17:
                shot(mc, "bridge").close();
                server(mc, "tp @p 4 7 -10 200 35");
                next(60);
                return;
            case 18:
                shot(mc, "arena_close").close();
                // straight above 3 3 6, orange grass beside the magma (whose own texture is orange)
                mc.options.hudHidden = true;
                server(mc, "tp @p 3.5 8 6.5 0 90");
                next(60);
                return;
            case 19: {
                // the block is ~100px across from 4 up, so its corner marks sit 25-50px out from the crosshair
                NativeImage img = shot(mc, "above_orange");
                int[] n = ring(img, 0xFF8C1A);
                facts.put("above orange: orange in each corner, anything coloured inside", Arrays.toString(n));
                check("orange block under the camera: marks in all four corners, nothing on the middle of the face", true,
                        n[0] > 10 && n[1] > 10 && n[2] > 10 && n[3] > 10 && n[4] == 0);
                server(mc, "tp @p -2.5 8 0.5 0 90");
                next(20);
                return;
            }
            case 20: {
                // -3 3 0 is green with -4 3 0 red beside it: both blocks' marks have to show
                NativeImage img = shot(mc, "above_green");
                mc.options.hudHidden = false;
                int[] g = ring(img, 0x4DFF4D);
                int w = img.getWidth(), h = img.getHeight(), red = 0;
                for (int x = w / 2 - 80; x < w / 2 + 80; x++) {
                    for (int y = h / 2 - 52; y < h / 2 + 52; y++) {
                        if (Math.abs(x - w / 2) < 52) continue;
                        int c = img.getPixelColor(x, y);
                        if (is(c, 0xFF4040)) red++;
                    }
                }
                img.close();
                facts.put("above green: green in each corner, coloured inside; red just past one side", Arrays.toString(g) + "; " + red);
                check("green and red neighbours each keep their own marks", true,
                        g[0] > 10 && g[1] > 10 && g[2] > 10 && g[3] > 10 && g[4] == 0 && red > 10);
                KeyBinding.onKeyPressed(OUTLINE_KEY);
                KeyBinding.onKeyPressed(COUNT_KEY);
                next(5);
                return;
            }
            case 21:
                check("both keys again turn everything off", "false / false / null / 0 / 0", PiglinPaths.outlines + " / " + PiglinPaths.counts
                        + " / " + PiglinPaths.reach + " / " + PiglinPaths.blocksDrawn + " / " + PiglinPaths.labelsDrawn);
                // 3 blocks in front of the piglin at 0 4 0, looking at its chest
                mc.player.inventory.selectedSlot = 0;
                KeyBinding.onKeyPressed(STICK_KEY);
                server(mc, "tp @p 0.5 4 -2.5 0 15");
                next(20);
                return;
            case 22:
                check("stick key puts a piglin stick in hand", "true / Piglin Stick", PiglinPaths.isStick(mc.player.getMainHandStack())
                        + " / " + mc.player.getMainHandStack().getName().getString());
                check("the server has it too", true, mc.getServer().submit(() ->
                        PiglinPaths.isStick(mc.getServer().getPlayerManager().getPlayerList().get(0).getMainHandStack())).join());
                check("crosshair on the piglin", "PiglinEntity", mc.targetedEntity == null ? "nothing" : mc.targetedEntity.getClass().getSimpleName());
                rightClick(mc);
                next(10);
                return;
            case 23: {
                check("piglin stick picks the piglin it's used on", mc.targetedEntity.getEntityId(), PiglinPaths.picked);
                check("picking shows outlines with both keys off", true, PiglinPaths.pickedBlocks != null && !PiglinPaths.outlines && !PiglinPaths.counts);
                int id = PiglinPaths.picked;
                IntegratedServer server = mc.getServer();
                LongSet vanilla = server.submit(() -> {
                    ServerWorld w = server.getOverworld();
                    PiglinEntity p = (PiglinEntity) w.getEntityById(id);
                    LongSet out = new LongOpenHashSet();
                    for (long l : everything) {
                        BlockPos pos = BlockPos.fromLong(l);
                        VoxelShape shape = w.getBlockState(pos).getCollisionShape(w, pos);
                        boolean low = !shape.isEmpty() && shape.getMax(Direction.Axis.Y) < 1;
                        if (reaches(p, pos.up()) || low && reaches(p, pos)) out.add(l);
                    }
                    return out;
                }).join();
                facts.put("blocks the picked piglin reaches", vanilla.size());
                check("picked outline is every block vanilla findPathTo reaches for that piglin", true,
                        vanilla.equals(PiglinPaths.pickedBlocks));
                check("blocks drawn = the picked piglin's, no counts", vanilla.size() + " / 0", PiglinPaths.blocksDrawn + " / " + PiglinPaths.labelsDrawn);
                server(mc, "tp @p 0.5 18 -9 0 55");
                next(40);
                return;
            }
            case 24:
                shot(mc, "picked").close();
                server(mc, "tp @p 0.5 4 -2.5 0 15");
                next(20);
                return;
            case 25:
                check("crosshair on the piglin again", PiglinPaths.picked, mc.targetedEntity == null ? -1 : mc.targetedEntity.getEntityId());
                rightClick(mc);
                next(5);
                return;
            case 26:
                check("piglin stick on the same piglin puts it down", "-1 / 0", PiglinPaths.picked + " / " + PiglinPaths.blocksDrawn);
                // the piglin at 0 4 0 already glows red from a team; picking a block it can reach has to make it green
                server(mc, "team add red", "team modify red color red",
                        "team join red @e[type=piglin,x=0.5,y=4,z=0.5,distance=..1]",
                        "effect give @e[type=piglin,x=0.5,y=4,z=0.5,distance=..1] glowing 1000000 0 true",
                        "tp @p 3.5 14 -12 0 45");
                mc.options.hudHidden = true;
                next(30);
                return;
            case 27: {
                NativeImage img = shot(mc, "glow_before");
                facts.put("team red outline pixels before the stick", count(img, TEAM_RED));
                img.close();
                // straight down onto 3 3 -3, between the two arena piglins
                server(mc, "tp @p 3.5 5 -2.5 0 90");
                next(20);
                return;
            }
            case 28:
                check("crosshair on 3 3 -3", "3 3 -3", mc.crosshairTarget instanceof BlockHitResult
                        ? str(((BlockHitResult) mc.crosshairTarget).getBlockPos()) : String.valueOf(mc.crosshairTarget));
                rightClick(mc);
                next(40);
                return;
            case 29:
                check("piglin stick on a block targets it", BlockPos.asLong(3, 3, -3), PiglinPaths.target);
                check("piglins outlined = the ones a chase gets onto 3 3 -3", chase(mc, new BlockPos(3, 4, -3)).toString(), outlined(mc).toString());
                check("the target block is marked", 1, PiglinPaths.blocksDrawn);
                server(mc, "tp @p 3.5 14 -12 0 45");
                next(30);
                return;
            case 30: {
                NativeImage img = shot(mc, "target");
                int before = (Integer) facts.get("team red outline pixels before the stick");
                int red = count(img, TEAM_RED), green = count(img, PiglinPaths.GREEN_RGB);
                img.close();
                check("glowing red piglin turns green: red before / red now / green now", "seen / 0 / seen",
                        (before > 100 ? "seen" : "none " + before) + " / " + red + " / " + (green > 100 ? "seen" : "none " + green));
                // 25 out on open floor: past one path's 16 blocks, so only a chase gets there
                server(mc, "tp @p 0.5 6 -24.5 0 90");
                next(20);
                return;
            }
            case 31:
                rightClick(mc);
                next(40);
                return;
            case 32: {
                check("piglin stick on another block moves the target", BlockPos.asLong(0, 3, -25), PiglinPaths.target);
                Set<Integer> chased = chase(mc, new BlockPos(0, 4, -25));
                check("piglins outlined = the ones a chase gets onto 0 3 -25", chased.toString(), outlined(mc).toString());
                check("both arena piglins chase that far, the one in the pit can't", 2, chased.size());
                rightClick(mc);
                mc.options.hudHidden = false;
                next(5);
                return;
            }
            case 33:
                check("piglin stick on the same block lets it go: targeted / outlined / marked", "false / [] / 0",
                        (PiglinPaths.target == BlockPos.asLong(0, 3, -25)) + " / " + outlined(mc) + " / " + PiglinPaths.blocksDrawn);
                finish(mc, null);
                return;
            default:
        }
    }

    // every block the mod has an opinion on, against vanilla's own findPathTo run by each server piglin
    private static void compare(MinecraftClient mc, Reach r) {
        LongSet all = new LongOpenHashSet(r.red);
        all.addAll(r.counts.keySet());
        everything = all;
        IntegratedServer server = mc.getServer();
        List<String> wrong = server.submit(() -> {
            ServerWorld w = server.getOverworld();
            List<PiglinEntity> piglins = w.getEntities(PiglinEntity.class, SCENES, e -> true);
            // NoAI piglins never touch down on the server; findPathTo gives up on mobs in the air
            for (PiglinEntity p : piglins) p.setOnGround(true);
            List<String> out = new ArrayList<>();
            int reached = 0;
            for (long l : all) {
                BlockPos pos = BlockPos.fromLong(l);
                VoxelShape shape = w.getBlockState(pos).getCollisionShape(w, pos);
                boolean low = !shape.isEmpty() && shape.getMax(Direction.Axis.Y) < 1;
                int n = 0;
                for (PiglinEntity p : piglins) {
                    if (reaches(p, pos.up()) || low && reaches(p, pos)) n++;
                }
                if (n > 0) reached++;
                int mod = r.counts.get(l);
                if (n != mod) out.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " vanilla " + n + " mod " + mod);
            }
            facts.put("piglins on the server", piglins.size());
            facts.put("blocks vanilla reaches", reached);
            return out;
        }).join();
        facts.put("blocks vanilla and the mod disagree on (first 40)", wrong.subList(0, Math.min(40, wrong.size())));
        check("blocks counted differently from vanilla findPathTo", 0, wrong.size());

        int badGreen = 0;
        for (long l : r.green) if (!r.counts.containsKey(l) || r.red.contains(l) || !touches(r.red, l)) badGreen++;
        for (long l : r.counts.keySet()) if (!r.green.contains(l) && !r.red.contains(l) && touches(r.red, l)) badGreen++;
        check("green is exactly the reachable blocks touching red", 0, badGreen);
        int overlap = 0;
        for (long l : r.red) if (r.counts.containsKey(l)) overlap++;
        check("red blocks vanilla still reaches", 0, overlap);
        int orangeReached = 0;
        for (long l : r.orange) if (r.counts.containsKey(l)) orangeReached++;
        facts.put("orange blocks vanilla still reaches", orangeReached + " of " + r.orange.size());
    }

    private static void arena(Reach r) {
        check("pillar top isn't red", false, r.red.contains(BlockPos.asLong(3, 6, -6)));
        check("floor behind the wall isn't red", false, r.red.contains(BlockPos.asLong(0, 3, 11)));
        check("past the range on -z isn't red", false, r.red.contains(BlockPos.asLong(0, 3, -16)));
        check("shore next to the lava moat is red", true, r.red.contains(BlockPos.asLong(-4, 3, 0)));
        check("two out from the moat is green", true, r.green.contains(BlockPos.asLong(-3, 3, 0)));
        check("pool rim is red", true, r.red.contains(BlockPos.asLong(-3, 4, 6)));
        check("floor under the pool's lava isn't red", false, r.red.contains(BlockPos.asLong(-4, 3, 6)));
        check("floor next to the magma is orange", true, r.orange.contains(BlockPos.asLong(3, 3, 6)));
        check("magma itself isn't marked", false, r.orange.contains(BlockPos.asLong(4, 3, 6)) || r.red.contains(BlockPos.asLong(4, 3, 6)));
        check("one block step is reachable", true, r.counts.get(BlockPos.asLong(-3, 4, -4)) > 0);
        check("between both piglins counts 2", 2, r.counts.get(BlockPos.asLong(3, 3, 0)));
        check("open floor in the middle isn't green", false, r.green.contains(BlockPos.asLong(0, 3, -5)));
    }

    private static int bridge(LongSet set) {
        int n = 0;
        for (long l : set) if (BlockPos.unpackLongX(l) >= 50) n++;
        return n;
    }

    // what the use key does: MinecraftClient.handleInputEvents runs doItemUse on the crosshair target
    private static void rightClick(MinecraftClient mc) {
        KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(mc.options.keyUse.getBoundKeyTranslationKey()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object o, Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static String categoryText(Object categoryEntry) throws ReflectiveOperationException {
        return E2e.<Text>get(categoryEntry, ControlsListWidget.CategoryEntry.class, "text").getString();
    }

    // pixels of one colour in the corners a block's marks make from 4 above (it's ~100px across): top left, top
    // right, bottom left, bottom right; then any of the three colours in the middle of the face
    private static int[] ring(NativeImage img, int rgb) {
        int w = img.getWidth(), h = img.getHeight();
        int[] n = new int[5];
        for (int x = w / 2 - 60; x < w / 2 + 60; x++) {
            for (int y = h / 2 - 60; y < h / 2 + 60; y++) {
                int dx = x - w / 2, dy = y - h / 2;
                int c = img.getPixelColor(x, y);
                if (Math.abs(dx) > 25 && Math.abs(dx) < 53 && Math.abs(dy) > 25 && Math.abs(dy) < 53 && is(c, rgb)) {
                    n[(dy < 0 ? 0 : 2) + (dx < 0 ? 0 : 1)]++;
                }
                if (Math.abs(dx) < 36 && Math.abs(dy) < 36 && (is(c, 0xFF4040) || is(c, 0xFF8C1A) || is(c, 0x4DFF4D))) n[4]++;
            }
        }
        return n;
    }

    private static int count(NativeImage img, int rgb) {
        int n = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) if (is(img.getPixelColor(x, y), rgb)) n++;
        }
        return n;
    }

    // NativeImage pixels are ABGR; fog and filtering shift colours a little
    private static boolean is(int abgr, int rgb) {
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        return Math.abs(r - (rgb >> 16 & 0xFF)) < 40 && Math.abs(g - (rgb >> 8 & 0xFF)) < 40 && Math.abs(b - (rgb & 0xFF)) < 40;
    }

    // client piglins the mod outlines; vanilla glowing on its own doesn't count, the screenshots check colour
    private static Set<Integer> outlined(MinecraftClient mc) {
        Set<Integer> out = new TreeSet<>();
        for (PiglinEntity p : mc.world.getEntities(PiglinEntity.class, SCENES, e -> true)) {
            if (PiglinPaths.highlighted(p) && mc.method_27022(p)) out.add(p.getEntityId());
        }
        return out;
    }

    // the server's piglins chasing the player onto target the way vanilla does: a path, walk to its end, path again.
    // having a target is also what lets them take longer drops
    private static Set<Integer> chase(MinecraftClient mc, BlockPos target) {
        IntegratedServer server = mc.getServer();
        return server.submit(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
            Set<Integer> out = new TreeSet<>();
            for (PiglinEntity p : server.getOverworld().getEntities(PiglinEntity.class, SCENES, e -> true)) {
                double x = p.getX(), y = p.getY(), z = p.getZ();
                p.setTarget(player);
                for (int i = 0; i < 12; i++) {
                    Path path = p.getNavigation().findPathTo(target, 0);
                    if (path == null) break;
                    if (path.reachesTarget()) {
                        out.add(p.getEntityId());
                        break;
                    }
                    PathNode end = path.getEnd();
                    if (end.getPos().equals(p.getBlockPos())) break;
                    p.refreshPositionAndAngles(end.x + 0.5, end.y, end.z + 0.5, 0, 0);
                    p.setOnGround(true);
                }
                p.setTarget(null);
                p.refreshPositionAndAngles(x, y, z, 0, 0);
                p.setOnGround(true);
            }
            return out;
        }).join();
    }

    private static String str(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static boolean reaches(PiglinEntity p, BlockPos target) {
        Path path = p.getNavigation().findPathTo(target, 0);
        return path != null && path.reachesTarget();
    }

    private static boolean touches(LongSet set, long l) {
        BlockPos p = BlockPos.fromLong(l);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dy != 0 || dz != 0) && set.contains(p.add(dx, dy, dz).asLong())) return true;
                }
            }
        }
        return false;
    }

    // feet height on the highest block of the bridge at x z
    private static double deck(MinecraftClient mc, int x, int z) {
        IntegratedServer server = mc.getServer();
        return server.submit(() -> {
            ServerWorld w = server.getOverworld();
            for (int y = BRIDGE_Y + 19; y >= BRIDGE_Y; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                VoxelShape shape = w.getBlockState(pos).getCollisionShape(w, pos);
                if (!shape.isEmpty()) return y + shape.getMax(Direction.Axis.Y);
            }
            throw new IllegalStateException("no deck at " + x + " " + z);
        }).join();
    }

    private static int[] server(MinecraftClient mc, String... commands) {
        IntegratedServer server = mc.getServer();
        int[] results = new int[commands.length];
        server.submit(() -> {
            for (int i = 0; i < commands.length; i++) {
                results[i] = server.getCommandManager().execute(server.getCommandSource(), commands[i]);
            }
        }).join();
        return results;
    }

    private static NativeImage shot(MinecraftClient mc, String name) throws Exception {
        NativeImage img = ScreenshotUtils.takeScreenshot(mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(), mc.getFramebuffer());
        OUT.mkdirs();
        img.writeFile(new File(OUT, name + ".png"));
        return img;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean pass = expected == null ? actual == null : expected.equals(actual);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("expected", expected);
        c.put("actual", actual);
        c.put("pass", pass);
        checks.add(c);
        System.out.println("[piglinpaths e2e] " + (pass ? "pass" : "FAIL") + "  " + name + "  expected=" + expected + " actual=" + actual);
    }

    private static void finish(MinecraftClient mc, Throwable error) {
        done = true;
        boolean passed = error == null && !checks.isEmpty();
        for (Map<String, Object> c : checks) passed &= (Boolean) c.get("pass");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", passed);
        report.put("error", error == null ? null : error.toString());
        report.put("checks", checks);
        report.put("facts", facts);
        OUT.mkdirs();
        try (Writer w = new FileWriter(new File(OUT, "report.json"))) {
            new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("[piglinpaths e2e] " + (passed ? "PASSED" : "FAILED") + ", report in " + OUT);
        mc.scheduleStop();
    }
}
