package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Version-one permanent storage chart of the continental Earth. Six cube faces each contain disjoint altitude
 * bands; host Y is translated, never scaled. Horizontal coordinates are gnomonic chart meters, not an equal-area
 * or globally unit-metric block grid. This immutable value owns no worlds, chunks, clocks or transfer state.
 */
public record EarthChart(CubeFace face, int band) implements GeographicReference {
    public static final int VERSION = 1;
    public static final int MIN_Y = -2032;
    public static final int HEIGHT = 4064;
    public static final int MIN_BAND = -2;
    public static final int MAX_BAND = 3;
    public static final double RADIUS_METERS = ContinentalTerrain.RADIUS_METERS;
    public static final String GEOGRAPHY_ID = "astraengine:sol/earth/continental_v1";
    private static final PlanetaryTopology TOPOLOGY = new PlanetaryTopology(PlanetaryTopology.VERSION, 12, RADIUS_METERS);
    public static final List<EarthChart> ALL = Stream.of(CubeFace.values()).flatMap(face ->
            IntStream.rangeClosed(MIN_BAND, MAX_BAND).mapToObj(band -> new EarthChart(face, band))).toList();

    public EarthChart {
        if (face == null || band < MIN_BAND || band > MAX_BAND) {
            throw new IllegalArgumentException("Earth storage requires a cube face and altitude band in [-2,3]");
        }
    }

    /** Permanent dimension identity. The new Earth preset binds positive-X band zero to Overworld. */
    public String dimensionId() {
        return face == CubeFace.POSITIVE_X && band == 0 ? "minecraft:overworld"
                : "astraengine:earth/" + face.id() + "/" + (band < 0 ? "below_" : "above_") + Math.abs(band);
    }

    /** Exact physical sea-level altitude to add to host Y, in meters. */
    public int altitudeOriginMeters() { return band * HEIGHT; }

    @Override public String geographyId() { return GEOGRAPHY_ID; }
    @Override public PlanetaryTopology topology() { return TOPOLOGY; }

    /** Unit body-fixed direction. Supports chart extensions for projection, without granting storage ownership. */
    public SpaceVector normal(double hostX, double hostZ) {
        if (!Double.isFinite(hostX) || !Double.isFinite(hostZ)) {
            throw new IllegalArgumentException("Earth chart coordinates must be finite");
        }
        return face.outward().multiply(RADIUS_METERS).add(face.u().multiply(hostX))
                .add(face.v().multiply(hostZ)).normalized();
    }

    /** Geographic feet address; rejects values outside this chart's canonical face or altitude band. */
    public GeographicPosition geographic(SpaceVector feet) {
        if (!contains(feet)) { throw new IllegalArgumentException("Position is outside its Earth storage chart"); }
        var direction = GeographicPosition.fromBody(normal(feet.x(), feet.z()), 1);
        return new GeographicPosition(direction.latitudeRadians(), direction.longitudeRadians(),
                feet.y() + altitudeOriginMeters());
    }

    /** Exact ownership includes face tie-breaking and a half-open vertical interval. Null is never contained. */
    public boolean contains(SpaceVector feet) {
        return feet != null && feet.y() >= MIN_Y && feet.y() < MIN_Y + HEIGHT
                && Math.abs(feet.x()) <= RADIUS_METERS && Math.abs(feet.z()) <= RADIUS_METERS
                && CubeFace.containing(normal(feet.x(), feet.z())) == face;
    }

    /**
     * Resolves a geographic address only in its canonical storage owner. No loading, clipping or fallback.
     * The exact poles are ordinary coordinates in the Y faces. Null is invalid.
     */
    public Optional<SpaceVector> resolve(GeographicPosition address) {
        if (address == null) { throw new IllegalArgumentException("An Earth geographic address is required"); }
        if (!owner(address).map(this::equals).orElse(false)) { return Optional.empty(); }
        SpaceVector direction = address.normal();
        double forward = direction.dot(face.outward());
        return Optional.of(new SpaceVector(direction.dot(face.u()) * RADIUS_METERS / forward,
                address.altitudeMeters() - altitudeOriginMeters(),
                direction.dot(face.v()) * RADIUS_METERS / forward));
    }

    /** Canonical owner of a finite geographic address; absence means outside the stored physical altitude range. */
    public static Optional<EarthChart> owner(GeographicPosition address) {
        if (address == null) { throw new IllegalArgumentException("An Earth geographic address is required"); }
        // Adding half a band before division can round nextDown(boundary) into the following band.
        // Comparing the original altitude to exact integer boundaries preserves half-open ownership.
        for (int band = MIN_BAND; band <= MAX_BAND; band++) {
            int lower = MIN_Y + band * HEIGHT;
            if (address.altitudeMeters() >= lower && address.altitudeMeters() < lower + HEIGHT) {
                return Optional.of(new EarthChart(CubeFace.containing(address.normal()), band));
            }
        }
        return Optional.empty();
    }

    /**
     * Physical pose with exact projection differential velocity. Host velocity is chart meters per tick.
     * Face-X projected onto the local tangent plane defines heading, including at the poles. No orbital or
     * spin transport velocity is included. Caller owns logical-side and transfer policy.
     */
    public PlanetaryPose pose(SpaceVector feet, SpaceVector velocity, FlightOrientation orientation) {
        if (!contains(feet) || velocity == null || orientation == null) {
            throw new IllegalArgumentException("Earth pose requires a contained position, velocity and orientation");
        }
        SpaceVector plane = face.outward().multiply(RADIUS_METERS).add(face.u().multiply(feet.x()))
                .add(face.v().multiply(feet.z()));
        SpaceVector up = plane.normalized();
        double radial = RADIUS_METERS + feet.y() + altitudeOriginMeters();
        SpaceVector horizontal = face.u().multiply(velocity.x()).add(face.v().multiply(velocity.z()));
        SpaceVector physicalVelocity = horizontal.subtract(up.multiply(up.dot(horizontal)))
                .multiply(radial / plane.length()).add(up.multiply(velocity.y()));
        SpaceVector east = face.u().subtract(up.multiply(up.dot(face.u()))).normalized();
        var frame = new PlanetaryFrame(up.multiply(radial), east, up, PlanetaryFrame.cross(east, up));
        return new PlanetaryPose(frame.originMeters(), physicalVelocity, frame.toBodyOrientation(orientation));
    }
}
