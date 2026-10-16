package io.github.lollip0529.piglinpaths;

import io.github.lollip0529.piglinpaths.mixin.EntityNavigationAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathMinHeap;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;

import java.util.Collection;
import java.util.Collections;

/**
 * Where piglins can walk to. A block counts when vanilla's findPathTo, run by that piglin with the block as
 * its target, returns a path that gets there: under its follow range (16 for piglins) and its 255 node budget,
 * with lava and fire penalties and all its quirks. Red is floor next to lava, which vanilla types as lava itself and
 * won't path onto; orange is floor next to fire, magma or a lit campfire, which it avoids. Both only where a piglin
 * could walk if the hazards were safe, so a cliff or a separate floor isn't marked.
 */
public final class Reach {
    // floor block -> how many piglins can path onto it
    public final Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
    // floors next to lava
    public final LongOpenHashSet red = new LongOpenHashSet();
    // floors next to fire, magma or a lit campfire
    public final LongOpenHashSet orange = new LongOpenHashSet();
    // reachable floors touching a red one, neither red nor orange themselves
    public final LongOpenHashSet green = new LongOpenHashSet();

    // one piglin's share, the slow part
    public static final class Piglin {
        public final LongSet reached = new LongOpenHashSet();
        final LongSet lava = new LongOpenHashSet();
        final LongSet fire = new LongOpenHashSet();
    }

    // what makes vanilla refuse a block that's otherwise walkable
    private static final PathNodeType[] HAZARDS = {PathNodeType.LAVA, PathNodeType.DAMAGE_FIRE, PathNodeType.DAMAGE_CACTUS, PathNodeType.DAMAGE_OTHER};

    public static Piglin piglin(World world, MobEntity mob) {
        Piglin p = new Piglin();
        float range = (float) mob.getAttributeValue(EntityAttributes.GENERIC_FOLLOW_RANGE);
        // the box EntityNavigation.findPathTo(BlockPos, int) searches in
        int i = (int) (range + 8);
        BlockPos at = mob.getBlockPos();
        Nodes nodes = new Nodes(mob);
        ChunkCache cache = new ChunkCache(world, at.add(-i, -i, -i), at.add(i, i, i));
        nodes.init(cache, mob);
        LongSet maybe = nodes.reach(nodes.getStart(), range, false);

        // the shortest paths are only a superset: vanilla's A* can run out of nodes or overwrite a path
        // length with a longer one, so each candidate gets the real search
        PathNodeNavigator navigator = new PathNodeNavigator(nodes, MathHelper.floor(range * 16));
        LongIterator it = maybe.iterator();
        while (it.hasNext()) {
            BlockPos target = BlockPos.fromLong(it.nextLong());
            Path path = navigator.findPathToAny(cache, mob, Collections.singleton(target), range, 0, 1);
            if (path != null && path.reachesTarget()) p.reached.add(nodes.floor(target.getX(), target.getY(), target.getZ()));
        }
        nodes.release();

        // the same search with hazards made safe; walking on a lava lake still isn't, so it needs a solid floor.
        // the client's piglin never pathfinds, borrowing its penalties is harmless
        float[] saved = new float[HAZARDS.length];
        for (int h = 0; h < HAZARDS.length; h++) {
            saved[h] = mob.getPathfindingPenalty(HAZARDS[h]);
            mob.setPathfindingPenalty(HAZARDS[h], 0);
        }
        try {
            Nodes safe = new Nodes(mob);
            safe.init(cache, mob);
            it = safe.reach(safe.getStart(), range, true).iterator();
            while (it.hasNext()) {
                BlockPos pos = BlockPos.fromLong(it.nextLong());
                // getNodeTypeFromNeighbors: lava within a block makes the spot LAVA, fire or magma DANGER_FIRE.
                // standing in the lava itself isn't next to it
                PathNodeType type = safe.type(pos);
                long floor = safe.floor(pos.getX(), pos.getY(), pos.getZ());
                if (type == PathNodeType.LAVA && !cache.getFluidState(pos).isIn(FluidTags.LAVA)) p.lava.add(floor);
                else if (type == PathNodeType.DANGER_FIRE) p.fire.add(floor);
            }
            safe.release();
        } finally {
            for (int h = 0; h < HAZARDS.length; h++) mob.setPathfindingPenalty(HAZARDS[h], saved[h]);
        }
        return p;
    }

    public static Reach of(Collection<Piglin> piglins) {
        Reach r = new Reach();
        for (Piglin p : piglins) {
            LongIterator it = p.reached.iterator();
            while (it.hasNext()) r.counts.addTo(it.nextLong(), 1);
            r.red.addAll(p.lava);
            r.orange.addAll(p.fire);
        }
        r.orange.removeAll(r.red);

        LongIterator it = r.counts.keySet().iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            if (!r.red.contains(l) && !r.orange.contains(l) && touches(r.red, l)) r.green.add(l);
        }
        return r;
    }

    private static boolean touches(LongSet set, long l) {
        int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dy != 0 || dz != 0) && set.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) return true;
                }
            }
        }
        return false;
    }

    private static final class Nodes extends LandPathNodeMaker {
        private final PathNode[] successors = new PathNode[32];

        Nodes(MobEntity mob) {
            PathNodeMaker m = ((EntityNavigationAccessor) mob.getNavigation()).getNodeMaker();
            setCanEnterOpenDoors(m.canEnterOpenDoors());
            setCanOpenDoors(m.canOpenDoors());
            setCanSwim(m.canSwim());
        }

        // the navigator clears after every search, that would throw away the node types it shares
        @Override
        public void clear() {
        }

        void release() {
            super.clear();
        }

        // nodes whose shortest path is under range
        LongSet reach(PathNode start, float range, boolean solidFloor) {
            LongSet out = new LongOpenHashSet();
            PathMinHeap heap = new PathMinHeap();
            start.pathLength = 0;
            start.heapWeight = 0;
            heap.push(start);
            while (!heap.isEmpty()) {
                PathNode node = heap.pop();
                // getSuccessors leaves visited nodes out, and a popped node already has its shortest path
                node.visited = true;
                if (solidFloor && !solid(node)) continue;
                out.add(BlockPos.asLong(node.x, node.y, node.z));
                int k = getSuccessors(successors, node);
                for (int j = 0; j < k; j++) {
                    PathNode next = successors[j];
                    float d = node.pathLength + node.getDistance(next);
                    if (d >= range || next.isInHeap() && d >= next.pathLength) continue;
                    next.pathLength = d;
                    if (next.isInHeap()) {
                        heap.setNodeWeight(next, d);
                    } else {
                        next.heapWeight = d;
                        heap.push(next);
                    }
                }
            }
            return out;
        }

        PathNodeType type(BlockPos pos) {
            return getNodeType(cachedWorld, pos.getX(), pos.getY(), pos.getZ(), entity, entityBlockXSize, entityBlockYSize, entityBlockZSize,
                    canOpenDoors(), canEnterOpenDoors());
        }

        private boolean solid(PathNode node) {
            BlockPos pos = BlockPos.fromLong(floor(node.x, node.y, node.z));
            return !cachedWorld.getBlockState(pos).getCollisionShape(cachedWorld, pos).isEmpty();
        }

        // the block the mob stands on: a slab, carpet or stair it's inside of, or else the one below
        long floor(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (cachedWorld.getBlockState(pos).getCollisionShape(cachedWorld, pos).isEmpty()) pos = pos.down();
            return pos.asLong();
        }
    }
}
