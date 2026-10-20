package net.flameslight.rwstructuresawareness.types.roadPathfinder;

import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps the terrain RoadWeaver's pathfinder searches over. Every call passes through unchanged
 * except contains(), which also says "no" over recorded structures. All four of RoadWeaver's
 * pathfinders skip a neighbour whose contains() is false, so this makes structures impassable for
 * every algorithm. Only the raw search sees this wrapper. Heights and bridges later use the original.
 * A road's endpoints lie inside their destinations. A narrow "way out" from each (addWayOut) stays
 * passable so the search can leave them. RoadTrimPlanner later trims the road on those stretches.
 */
public final class ObstacleTerrainField implements PathTerrainField {
    /**
     * The road's height at a column isn't known yet during the search. It follows the terrain, give
     * or take RoadWeaver's cut/fill smoothing. Structures overlapping [height - BELOW, height + clear
     * + ABOVE] block the column. Anything buried deeper (or floating higher) doesn't.
     */
    public static final int BAND_BELOW = 6;
    public static final int BAND_ABOVE = 2;

    private final PathTerrainField terrain;
    private final RoadObstacles obstacles;
    private final int clearHeight;
    /** Ways out of the road's destinations: {x0, z0, x1, z1, halfWidth} segments that stay passable. */
    private final List<int[]> ways = new ArrayList<>(2);

    /**
     * Wraps a search terrain.
     *
     * @param terrain     RoadWeaver's terrain, every call but contains passes through to it
     * @param obstacles   the road's obstacles
     * @param clearHeight the road's clearance (blocks)
     */
    public ObstacleTerrainField(PathTerrainField terrain, RoadObstacles obstacles, int clearHeight) {
        this.terrain = terrain;
        this.obstacles = obstacles;
        this.clearHeight = clearHeight;
    }

    /**
     * Keeps the columns near a segment passable over structures.
     *
     * @param x0        the segment's first X (block)
     * @param z0        the segment's first Z (block)
     * @param x1        the segment's second X (block)
     * @param z1        the segment's second Z (block)
     * @param halfWidth columns within this distance of the segment stay passable (blocks)
     */
    public void addWayOut(int x0, int z0, int x1, int z1, int halfWidth) {
        ways.add(new int[]{x0, z0, x1, z1, halfWidth});
    }

    /**
     * Tells whether the search may use a column: RoadWeaver's own answer, and no recorded structure in the
     * column's height band unless a way out covers it.
     *
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return true if the column is passable
     */
    @Override
    public boolean contains(int x, int z) {
        if (!terrain.contains(x, z)) {
            return false;
        }
        if (!obstacles.mayBlock(x, z)) {
            return true; // nothing here: skip the height sample
        }
        for (int[] w : ways) {
            if (nearSegment(w, x, z)) {
                return true;
            }
        }
        int h = terrain.height(x, z);
        return !obstacles.isBlocked(x, z, h - BAND_BELOW, h + clearHeight + BAND_ABOVE);
    }

    /**
     * Tells whether a column is within a way out's half-width of its segment.
     *
     * @param w a way out, {x0, z0, x1, z1, halfWidth}
     * @param x the column's X (block)
     * @param z the column's Z (block)
     * @return true if the column is near the segment
     */
    private static boolean nearSegment(int[] w, int x, int z) {
        double dx = w[2] - w[0];
        double dz = w[3] - w[1];
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - w[0]) * dx + (z - w[1]) * dz) / len2));
        double ex = x - (w[0] + t * dx);
        double ez = z - (w[1] + t * dz);
        return ex * ex + ez * ez <= (double) w[4] * w[4];
    }

    @Override
    public int seaLevel() {
        return terrain.seaLevel();
    }

    @Override
    public int height(int x, int z) {
        return terrain.height(x, z);
    }

    @Override
    public int oceanFloor(int x, int z) {
        return terrain.oceanFloor(x, z);
    }

    @Override
    public boolean isColumnWater(int x, int z) {
        return terrain.isColumnWater(x, z);
    }

    @Override
    public boolean isNearWater(int x, int z, int neighborDistance) {
        return terrain.isNearWater(x, z, neighborDistance);
    }

    @Override
    public Holder<Biome> biome(int x, int z) {
        return terrain.biome(x, z);
    }

    @Override
    public int step() {
        return terrain.step();
    }

    @Override
    public void dispose() {
        terrain.dispose();
    }

    @Override
    public boolean isWaterBiome(int x, int z) {
        return terrain.isWaterBiome(x, z);
    }

    @Override
    public int waterDepth(int x, int z) {
        return terrain.waterDepth(x, z);
    }

    @Override
    public boolean isBridgeWater(int x, int z, int minWaterDepth) {
        return terrain.isBridgeWater(x, z, minWaterDepth);
    }

    @Override
    public SampleBundle sampleBundle(int x, int z) {
        return terrain.sampleBundle(x, z);
    }
}
