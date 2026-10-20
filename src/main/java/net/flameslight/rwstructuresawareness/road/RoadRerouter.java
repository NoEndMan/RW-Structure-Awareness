package net.flameslight.rwstructuresawareness.road;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.flameslight.rwstructuresawareness.types.road.RoadObstacles;
import net.flameslight.rwstructuresawareness.types.road.RoadLevel;
import net.flameslight.rwstructuresawareness.logger.ModLogger;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.flameslight.rwstructuresawareness.types.roadPathfinder.ObstacleTerrainField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Makes RoadWeaver's road searches route around recorded structures. Reached only from mixins. */
public final class RoadRerouter {
    /** Cached coarse paths are checked at least this often along each leg. */
    private static final int COARSE_PATH_SAMPLE_STEP = 4;
    /** Upper bound of the node-step part of searchClearance. */
    private static final int MAX_STEP_CLEARANCE = 16;

    /**
     * Runs one Pathfinder.findRawPath call (both RoadWeaver call sites) around recorded structures.
     * <p>
     * Runs it over a terrain where recorded structures are impassable. If no route exists that way, runs it
     * again unchanged, and RoadTunnelPlanner later tunnels under whatever the road still crosses (RoadTrimPlanner
     * trims what can't be tunnelled).
     * <p>
     * A road's endpoints are its destinations' locate positions, inside their own pieces once those
     * have generated. The endpoints stay as they are (RoadWeaver's searches size their search box from
     * them), and a narrow way out of each destination stays passable: the straight line from the
     * endpoint to the destination's edge (edgeOf). RoadTrimPlanner then trims the road where it runs
     * on its destinations, so it ends at their edge and never runs through their pieces.
     *
     * @param pathfinder the pathfinder findRawPath is called on
     * @param start      the road's start, may be null (then the call runs unchanged)
     * @param end        the road's end, may be null (then the call runs unchanged)
     * @param level      the level
     * @param maxSteps   the search's step budget
     * @param cache      RoadWeaver's terrain cache
     * @param terrain    the terrain the search runs over, may be null (then the call runs unchanged)
     * @param config     RoadWeaver's pathfinding config, may be null
     * @param original   Pathfinder.findRawPath
     * @return the rerouted result when it succeeded, otherwise the unchanged search's result
     */
    public static PathResult findRawPath(Pathfinder pathfinder, BlockPos start, BlockPos end, ServerLevel level,
                                         int maxSteps, TerrainSamplingCache cache, PathTerrainField terrain,
                                         PathfindingCostConfig config, Operation<PathResult> original) {
        RoadObstacles obstacles = !RoadLevel.isRoadLevel(level) || start == null || end == null || terrain == null
                ? null
                : RoadObstacles.forRoute(start.getX(), start.getZ(), end.getX(), end.getZ(), searchClearance(terrain));
        if (obstacles == null) {
            return original.call(pathfinder, start, end, level, maxSteps, cache, terrain, config);
        }
        ObstacleTerrainField field = new ObstacleTerrainField(terrain, obstacles, RoadWeaverAccessor.roadClearHeight());
        int halfWidth = Math.max(2, config == null ? 2 : config.effectiveAStarStep() + 1);
        int[] startEdge = edgeOf(field, start, end);
        int[] endEdge = edgeOf(field, end, start);
        if (startEdge != null) {
            field.addWayOut(start.getX(), start.getZ(), startEdge[0], startEdge[1], halfWidth);
        }
        if (endEdge != null) {
            field.addWayOut(end.getX(), end.getZ(), endEdge[0], endEdge[1], halfWidth);
        }
        if (startEdge != null || endEdge != null) {
            ModLogger.debug("Road search {} -> {}: endpoint(s) inside their structures, way out to {} / {}.",
                    start.toShortString(), end.toShortString(),
                    startEdge == null ? "-" : startEdge[0] + ", " + startEdge[1],
                    endEdge == null ? "-" : endEdge[0] + ", " + endEdge[1]);
        }
        PathResult rerouted = original.call(pathfinder, start, end, level, maxSteps, cache, field, config);
        if (rerouted != null && rerouted.success() && rerouted.hasRawPath()) {
            ModLogger.debug("Road search {} -> {} kept clear of recorded structures ({} path nodes).",
                    start.toShortString(), end.toShortString(), rerouted.rawPath().size());
            return rerouted;
        }
        ModLogger.debug("No route from {} to {} avoids every structure. It will tunnel under or stop before them.",
                start.toShortString(), end.toShortString());
        return original.call(pathfinder, start, end, level, maxSteps, cache, terrain, config);
    }

    /**
     * Finds the edge of the structures around a road endpoint.
     *
     * @param field  the search's terrain
     * @param from   the endpoint
     * @param toward the other endpoint, the direction looked in
     * @return null if the search may pass at {@code from}, otherwise {x, z} of the first free column on the
     *         straight line toward {@code toward}. Looks at most half-way, null past that (the search then
     *         falls back as before)
     */
    @Nullable
    private static int[] edgeOf(ObstacleTerrainField field, BlockPos from, BlockPos toward) {
        if (field.contains(from.getX(), from.getZ())) {
            return null;
        }
        int dx = toward.getX() - from.getX();
        int dz = toward.getZ() - from.getZ();
        int len = Math.max(Math.abs(dx), Math.abs(dz));
        for (int k = 1; k <= len / 2; k++) {
            int x = from.getX() + dx * k / len;
            int z = from.getZ() + dz * k / len;
            if (field.contains(x, z)) {
                return new int[]{x, z};
            }
        }
        return null;
    }

    /**
     * Returns how far the search keeps path nodes from recorded structures.
     * <p>
     * The pathfinder only tests its nodes (every step() blocks), and RoadWeaver then smooths the line between
     * them (PathPostProcessor), which cuts corners by up to about a step. Without the extra step, a road going
     * around a structure's corner clips its edge. Capped so a coarse search can still pass between nearby
     * structures.
     *
     * @param terrain the search's terrain
     * @return the road margin plus one node step, at most MAX_STEP_CLEARANCE for the step part (blocks)
     */
    private static int searchClearance(PathTerrainField terrain) {
        return RoadWeaverAccessor.configuredRoadMargin() + Math.max(0, Math.min(terrain.step(), MAX_STEP_CLEARANCE));
    }

    /**
     * Drops a cached coarse path that crosses structures. Wraps CoarsePathCache.get in Road.generateRoad.
     * <p>
     * A coarse path cached at planning time may cross structures generated afterwards, and the fine search
     * only looks at a narrow band of chunks around it. Returning null for such a path makes RoadWeaver
     * compute a fresh full path, which findRawPath above reroutes.
     *
     * @param level      the level
     * @param connection the connection being built, may be null
     * @param cached     the cached path, not changed, may be null
     * @return {@code cached} itself, or null when it crosses a recorded structure or no-road zone
     */
    @Nullable
    public static List<BlockPos> dropIfBlocked(ServerLevel level, StructureConnection connection,
                                               @Nullable List<BlockPos> cached) {
        if (cached == null || cached.isEmpty() || connection == null || !RoadLevel.isRoadLevel(level)) {
            return cached;
        }
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        int minX = Math.min(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxZ = Math.max(from.getZ(), to.getZ());
        for (BlockPos p : cached) {
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxZ = Math.max(maxZ, p.getZ());
        }
        RoadObstacles obstacles = RoadObstacles.forArea(minX, minZ, maxX, maxZ, from.getX(), from.getZ(),
                to.getX(), to.getZ(), RoadWeaverAccessor.configuredRoadMargin());
        if (obstacles == null || !crosses(cached, obstacles)) {
            return cached;
        }
        ModLogger.debug("Cached coarse road path {} -> {} crosses structures, recomputing it.",
                from.toShortString(), to.toShortString());
        return null;
    }

    /**
     * Tells whether a coarse path crosses a structure.
     * <p>
     * Coarse path points carry the terrain height, the same height band as the search applies. The road's own
     * destinations don't count: the path always starts and ends inside them, and the fine search (findRawPath)
     * and RoadTrimPlanner keep the road out of them.
     *
     * @param path      the coarse path, not changed
     * @param obstacles the road's obstacles
     * @return true if a point, or a sample between two points, is blocked
     */
    private static boolean crosses(List<BlockPos> path, RoadObstacles obstacles) {
        int clear = RoadWeaverAccessor.roadClearHeight();
        BlockPos previous = null;
        for (BlockPos p : path) {
            if (previous != null) {
                int dx = p.getX() - previous.getX();
                int dy = p.getY() - previous.getY();
                int dz = p.getZ() - previous.getZ();
                int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)) / COARSE_PATH_SAMPLE_STEP);
                for (int s = 1; s < steps; s++) {
                    if (blocked(obstacles, previous.getX() + dx * s / steps, previous.getY() + dy * s / steps,
                            previous.getZ() + dz * s / steps, clear)) {
                        return true;
                    }
                }
            }
            if (blocked(obstacles, p.getX(), p.getY(), p.getZ(), clear)) {
                return true;
            }
            previous = p;
        }
        return false;
    }

    /**
     * Tells whether a coarse path point is blocked.
     *
     * @param obstacles the road's obstacles
     * @param x         the point's X (block)
     * @param y         the point's terrain Y
     * @param z         the point's Z (block)
     * @param clear     the road's clearance (blocks)
     * @return true if a structure blocks the point's height band and it isn't on the road's own destination
     */
    private static boolean blocked(RoadObstacles obstacles, int x, int y, int z, int clear) {
        return obstacles.isBlocked(x, z, y - ObstacleTerrainField.BAND_BELOW, y + clear + ObstacleTerrainField.BAND_ABOVE)
                && !obstacles.isDestinationCell(x, z);
    }
}
