package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.SectionPos;

/** Immutable bounded section request around a nearby storage seam. Planning never reads or generates a world. */
public record EarthBoundaryPlan(CubeStorageChart source, SpaceVector anchorFeet, SpaceVector focusFeet,
        int radiusMeters, List<Address> sections) {
    public static final double PREPARE_DISTANCE = 48;

    /** A canonical section owner, not a copy in the observing chart. */
    public record Address(CubeStorageChart chart, SectionPos section) {
        public Address {
            if (chart == null || section == null) { throw new IllegalArgumentException("Boundary section address is required"); }
            section = SectionPos.of(section.x(), section.y(), section.z());
        }
    }

    public EarthBoundaryPlan {
        if (source == null || !source.contains(anchorFeet) || !source.contains(focusFeet)
                || radiusMeters < 4 || radiusMeters > 16 || sections == null || sections.isEmpty()
                || sections.size() > EarthBoundarySnapshot.MAX_SECTIONS) {
            throw new IllegalArgumentException("Invalid Earth boundary section plan");
        }
        sections = List.copyOf(sections);
    }

    /**
     * Plans a nearby seam while feet still belong to source. Empty means no boundary is nearby, or the outer
     * altitude edge has no stored neighbor. Smaller radii bound corner observations to the packet budget.
     * Null/outside source positions throw; the caller owns prefetch tickets and preparation status.
     */
    public static Optional<EarthBoundaryPlan> around(CubeStorageChart source, SpaceVector feet) {
        if (source == null || !source.contains(feet)) { throw new IllegalArgumentException("Boundary planning requires canonical feet"); }
        double radius = source.radiusMeters();
        double x = feet.x(), y = feet.y(), z = feet.z(); boolean near = false;
        if (Math.abs(x) > radius - PREPARE_DISTANCE) { x = Math.copySign(radius - .001, x); near = true; }
        if (Math.abs(z) > radius - PREPARE_DISTANCE) { z = Math.copySign(radius - .001, z); near = true; }
        if (y < source.minY() + PREPARE_DISTANCE && source.chart(source.face(), source.band() - 1).isPresent()) {
            y = source.minY() + .001; near = true;
        }
        if (y > source.minY() + source.height() - PREPARE_DISTANCE && source.chart(source.face(), source.band() + 1).isPresent()) {
            y = source.minY() + source.height() - .001; near = true;
        }
        if (!near) { return Optional.empty(); }
        var focus = new SpaceVector(x, y, z);
        for (int extent : new int[]{16, 12, 8, 4}) {
            List<Address> sections = sections(source, focus, extent);
            if (sections.size() <= EarthBoundarySnapshot.MAX_SECTIONS) {
                return Optional.of(new EarthBoundaryPlan(source, feet, focus, extent, sections));
            }
        }
        throw new IllegalStateException("Earth seam neighborhood cannot fit its bounded section budget");
    }

    /** A real bounded destination view for a free space crossing, including any adjacent canonical owners. */
    public static EarthBoundaryPlan arrival(CubeStorageChart source, SpaceVector feet) {
        if (source == null || !source.contains(feet)) { throw new IllegalArgumentException("Arrival planning requires canonical feet"); }
        for (int extent : new int[]{16, 12, 8, 4}) {
            var addresses = sections(source, feet, extent);
            if (addresses.size() <= EarthBoundarySnapshot.MAX_SECTIONS) {
                return new EarthBoundaryPlan(source, feet, feet, extent, addresses);
            }
        }
        throw new IllegalStateException("Arrival neighborhood cannot fit its bounded section budget");
    }

    private static List<Address> sections(CubeStorageChart source, SpaceVector focus, int extent) {
        var owners = new LinkedHashSet<CubeStorageChart>();
        for (int x : new int[]{-extent, 0, extent}) {
            for (int y : new int[]{-extent, 0, extent}) {
                for (int z : new int[]{-extent, 0, extent}) {
                    CubeChartRebase.resolve(source, focus.add(new SpaceVector(x, y, z)), SpaceVector.ZERO,
                            FlightOrientation.IDENTITY).ifPresent(value -> owners.add(value.chart()));
                }
            }
        }
        var result = new ArrayList<Address>();
        for (CubeStorageChart owner : owners) {
            var transform = new EarthChartTransform(source, owner);
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int x : new int[]{-extent, extent}) {
                for (int y : new int[]{-extent, extent}) {
                    for (int z : new int[]{-extent, extent}) {
                        var point = transform.position(focus.add(new SpaceVector(x, y, z)));
                        minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y()); minZ = Math.min(minZ, point.z());
                        maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y()); maxZ = Math.max(maxZ, point.z());
                    }
                }
            }
            double radius = source.radiusMeters();
            minX = Math.max(-radius, minX); minZ = Math.max(-radius, minZ); minY = Math.max(source.minY(), minY);
            maxX = Math.min(radius, maxX); maxZ = Math.min(radius, maxZ); maxY = Math.min(source.minY() + source.height(), maxY);
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) { continue; }
            int x0 = section(minX), y0 = section(minY), z0 = section(minZ);
            int x1 = section(Math.nextDown(maxX)), y1 = section(Math.nextDown(maxY)), z1 = section(Math.nextDown(maxZ));
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) { result.add(new Address(owner, SectionPos.of(x, y, z))); }
                }
            }
        }
        return List.copyOf(result);
    }

    private static int section(double coordinate) { return Math.floorDiv((int) Math.floor(coordinate), 16); }
}
