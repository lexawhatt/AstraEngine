package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.SectionPos;

/** Immutable bounded section request around a nearby storage seam. Planning never reads or generates a world. */
public record EarthBoundaryPlan(EarthChart source, SpaceVector anchorFeet, SpaceVector focusFeet,
        int radiusMeters, List<Address> sections) {
    public static final double PREPARE_DISTANCE = 48;

    /** A canonical section owner, not a copy in the observing chart. */
    public record Address(EarthChart chart, SectionPos section) {
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
    public static Optional<EarthBoundaryPlan> around(EarthChart source, SpaceVector feet) {
        if (source == null || !source.contains(feet)) { throw new IllegalArgumentException("Boundary planning requires canonical feet"); }
        double radius = EarthChart.RADIUS_METERS;
        double x = feet.x(), y = feet.y(), z = feet.z(); boolean near = false;
        if (Math.abs(x) > radius - PREPARE_DISTANCE) { x = Math.copySign(radius - .001, x); near = true; }
        if (Math.abs(z) > radius - PREPARE_DISTANCE) { z = Math.copySign(radius - .001, z); near = true; }
        if (y < EarthChart.MIN_Y + PREPARE_DISTANCE && source.band() > EarthChart.MIN_BAND) {
            y = EarthChart.MIN_Y + .001; near = true;
        }
        if (y > EarthChart.MIN_Y + EarthChart.HEIGHT - PREPARE_DISTANCE && source.band() < EarthChart.MAX_BAND) {
            y = EarthChart.MIN_Y + EarthChart.HEIGHT - .001; near = true;
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

    private static List<Address> sections(EarthChart source, SpaceVector focus, int extent) {
        var owners = new LinkedHashSet<EarthChart>();
        for (int x : new int[]{-extent, 0, extent}) {
            for (int y : new int[]{-extent, 0, extent}) {
                for (int z : new int[]{-extent, 0, extent}) {
                    EarthChartRebase.resolve(source, focus.add(new SpaceVector(x, y, z)), SpaceVector.ZERO,
                            FlightOrientation.IDENTITY).ifPresent(value -> owners.add(value.chart()));
                }
            }
        }
        var result = new ArrayList<Address>();
        for (EarthChart owner : owners) {
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
            double radius = EarthChart.RADIUS_METERS;
            minX = Math.max(-radius, minX); minZ = Math.max(-radius, minZ); minY = Math.max(EarthChart.MIN_Y, minY);
            maxX = Math.min(radius, maxX); maxZ = Math.min(radius, maxZ); maxY = Math.min(EarthChart.MIN_Y + EarthChart.HEIGHT, maxY);
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
