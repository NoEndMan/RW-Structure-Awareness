package net.flameslight.rwstructuresawareness.types.zones;

import java.util.List;
import java.util.Set;

/**
 * One placed Zones zone as RWSA uses it. Zones zones are circles.
 *
 * @param zoneId      the zone's definition id
 * @param ancestorIds the definition ids of the zones this one is nested in (its parentZone chain), nearest
 *                    parent first, empty for a top-level zone. Never null
 * @param centerX     the circle's center X (block)
 * @param centerZ     the circle's center Z (block)
 * @param radius      the circle's radius (blocks)
 */
public record ZonePerimeter(String zoneId, List<String> ancestorIds, int centerX, int centerZ, int radius) {
    /**
     * Tells whether a column lies inside the circle, optionally widened.
     *
     * @param x       the column's X (block)
     * @param z       the column's Z (block)
     * @param widenBy blocks added to the radius, 0 for the exact circle
     * @return true if the column is within radius + widenBy of the center
     */
    public boolean contains(int x, int z, int widenBy) {
        long dx = x - centerX;
        long dz = z - centerZ;
        long r = (long) radius + widenBy;
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Tells whether the circle meets a block-aligned box.
     *
     * @param minX the box's min X (block, inclusive)
     * @param minZ the box's min Z (block, inclusive)
     * @param maxX the box's max X (block, inclusive)
     * @param maxZ the box's max Z (block, inclusive)
     * @return true if any point of the box is inside the circle
     */
    public boolean intersects(int minX, int minZ, int maxX, int maxZ) {
        long dx = centerX - Math.max(minX, Math.min(centerX, maxX));
        long dz = centerZ - Math.max(minZ, Math.min(centerZ, maxZ));
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    /**
     * Finds which of a set of zone ids this zone is, or is nested in.
     *
     * @param ids zone definition ids, not changed
     * @return this zone's own id if listed, otherwise the nearest listed ancestor id, null if none is listed
     */
    public String matchedId(Set<String> ids) {
        if (ids.contains(zoneId)) {
            return zoneId;
        }
        for (String ancestor : ancestorIds) {
            if (ids.contains(ancestor)) {
                return ancestor;
            }
        }
        return null;
    }
}
