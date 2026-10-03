package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Bounded presentation weather, in body-fixed directions. Regional fronts and vertical cloud types share one
 * density with local billows, orbital columns and shadows. This is an authored climatic approximation, not a forecast.
 */
public final class EarthCloudWeather {
    private static final double SPIRAL_CORE = .12;
    private static final double SPIRAL_REFERENCE = .6;
    /** Local optical-mass weights: coherent low sheets, frontal cumulus and deeper convection. */
    public record Region(double stratus, double cumulus, double convection) { }
    private record System(SpaceVector center, SpaceVector east, SpaceVector north,
                          double radiusKm, double rotation, double strength, boolean tropical) { }
    private static final System[] SYSTEMS = {
            system(48, -28, 2100, -.3, 1, false), system(-43, 52, 1900, 1.1, .9, false),
            system(54, 120, 2300, .8, .85, false), system(-50, -132, 1800, -.7, .9, false),
            system(40, -110, 1500, 2.2, .7, false), system(17, 154, 800, .4, .8, true)
    };

    private EarthCloudWeather() { }

    /** Samples deterministic fronts and belts using the existing host wind and seasonal snapshot only. */
    public static Region sample(CloudNoiseField noise, EarthCloudState state, SpaceVector bodyPointKm) {
        if (noise == null || state == null || bodyPointKm == null || bodyPointKm.length() == 0) {
            throw new IllegalArgumentException("Cloud noise, state and nonzero body direction required");
        }
        SpaceVector n = state.advected(bodyPointKm).normalized();
        double longitude = Math.atan2(n.z(), n.x());
        double regional = noise.sample(n.x() * 7 + 17, n.y() * 7 + 3, n.z() * 7 + 29);
        double broken = noise.sample(n.x() * 19 + 41, n.y() * 19 + 13, n.z() * 19 + 7);
        double wet = (state.coverage(bodyPointKm) - .45) * .65 + state.rain() * .2;
        double tropicalCenter = state.season() * .075 + Math.sin(longitude * 3 + .8) * .045
                + Math.sin(longitude * 5 - .7) * .035;
        double convergence = 1 - smooth(.050, .140 + wet * .04, Math.abs(n.y() - tropicalCenter));
        double tropical = convergence * smooth(.49 - wet, .78 - wet, regional);
        double middle = smooth(.25, .40, Math.abs(n.y())) * (1 - smooth(.68, .85, Math.abs(n.y())));
        double stratus = middle * smooth(.60 - wet, .84 - wet, regional) * .35;
        double cumulus = tropical * (.30 + .40 * broken);
        double convection = tropical * smooth(.40, .76, broken) * .75;
        var belt = textured(noise, state.advected(bodyPointKm), state.advected(bodyPointKm), new Region(stratus, cumulus, convection));
        stratus = belt.stratus; cumulus = belt.cumulus; convection = belt.convection;
        for (System system : SYSTEMS) {
            Region region = system(noise, n, system, regional, broken);
            stratus = Math.max(stratus, region.stratus);
            cumulus = Math.max(cumulus, region.cumulus);
            convection = Math.max(convection, region.convection);
        }
        double activity = (.65 + state.coverage(bodyPointKm) * .65) * (.8 + broken * .4);
        return new Region(clamp(stratus * activity), clamp(cumulus * activity), clamp(convection * activity));
    }

    private static Region system(CloudNoiseField noise, SpaceVector n, System system, double regional, double broken) {
        double facing = n.dot(system.center);
        if (facing < .82) { return new Region(0, 0, 0); }
        double x = n.dot(system.east) / facing * 6371 / system.radiusKm;
        double y = n.dot(system.north) / facing * 6371 / system.radiusKm;
        double c = Math.cos(system.rotation), s = Math.sin(system.rotation);
        double qx = c * x - s * y, qy = (s * x + c * y) * Math.signum(system.center.y());
        qx += (regional - .5) * .13;
        qy += (broken - .5) * .13;
        double r = Math.hypot(qx, qy);
        if (r > 1.8) { return new Region(0, 0, 0); }
        double pitch = system.tropical ? 2.4 : 1.8;
        var spiral = spiralCoordinates(qx, qy, pitch);
        var flow = scaleFlow(spiral, system.radiusKm);
        double armDistance = spiralArmDistance(spiral, r, pitch, 0);
        if (system.tropical) {
            double arm = 1 - smooth(.055, .20, armDistance);
            double eye = smooth(.045, .11, r);
            double envelope = 1 - smooth(.4, 1.35, r);
            double mass = eye * envelope * Math.max(arm, (1 - smooth(.20, .45, r)) * .75) * system.strength;
            return textured(noise, flow, n.multiply(6371), new Region(mass * .25, mass * .6, mass));
        }
        // An asymmetric comma head, one curved cold-front tail and its dry slot; no repeated clear hurricane eye.
        double arm = (1 - smooth(.055, .16, armDistance))
                * smooth(.12, .27, r) * (1 - smooth(.85, 1.35, r)) * smooth(-.5, .15, qy + qx * .65);
        double head = (1 - smooth(.40, .77, Math.hypot(qx * .9, qy * 1.2)))
                * (.3 + .7 * smooth(-.45, .10, qy + qx * .65));
        double tail = (1 - smooth(.075, .17, Math.abs(qy + .38 + qx * qx * .35)))
                * smooth(-.15, .18, qx) * (1 - smooth(1.15, 1.65, qx));
        double dry = (1 - smooth(.045, .12, spiralArmDistance(spiral, r, pitch, .75)))
                * smooth(.20, .45, r) * (1 - smooth(.8, 1.4, r));
        double sheet = Math.max(head, Math.max(arm * .85, tail * .55)) * (1 - dry * .85);
        return textured(noise, flow, n.multiply(6371), new Region(sheet * system.strength,
                Math.max(tail, arm * .45) * system.strength, tail * smooth(.8, 1.4, qx) * .22 * system.strength));
    }

    // The same regularized logarithmic phase unwinds the arm and its texture. Its planar Jacobian
    // preserves area and has shear pitch*r^2/(r^2+core^2), bounded even at the occupied center.
    static SpaceVector spiralCoordinates(double x, double y, double pitch) {
        double squared = x * x + y * y;
        if (squared == 0) { return SpaceVector.ZERO; }
        double twist = -.5 * pitch * Math.log((squared + SPIRAL_CORE * SPIRAL_CORE)
                / (SPIRAL_REFERENCE * SPIRAL_REFERENCE + SPIRAL_CORE * SPIRAL_CORE));
        double c = Math.cos(twist), s = Math.sin(twist);
        // Rationalization retains the quadratic core instead of subtracting nearly equal roots.
        double radial = squared / (Math.sqrt(squared + SPIRAL_CORE * SPIRAL_CORE) + SPIRAL_CORE);
        return new SpaceVector(c * x - s * y, s * x + c * y, radial);
    }

    static SpaceVector flowCoordinates(double x, double y, double radiusKm, double pitch) {
        return scaleFlow(spiralCoordinates(x, y, pitch), radiusKm);
    }

    private static SpaceVector scaleFlow(SpaceVector spiral, double radiusKm) {
        return new SpaceVector(spiral.x() * radiusKm * .65, spiral.y() * radiusKm * .65,
                spiral.z() * radiusKm * .35);
    }

    // Half-chord distance is periodic without atan's branch cut. Normalizing by the phase gradient
    // keeps a finite transverse arm width as its pitch changes; the radial core is regularized above.
    static double spiralArmDistance(SpaceVector spiral, double radius, double pitch, double offset) {
        double along = Math.cos(offset) * spiral.x() - Math.sin(offset) * spiral.y();
        double shear = pitch * radius * radius / (radius * radius + SPIRAL_CORE * SPIRAL_CORE);
        return Math.sqrt(Math.max(0, .5 * radius * (radius - along)) / (1 + shear * shear));
    }

    // Conservative full3D flow norm: exact planar largest singular value plus bounded radial derivative.
    static double flowFootprintScale(double radius, double pitch) {
        double shear = Math.abs(pitch) * radius * radius / (radius * radius + SPIRAL_CORE * SPIRAL_CORE);
        double singular = (Math.sqrt(shear * shear + 4) + shear) * .5;
        return Math.sqrt(.65 * .65 * singular * singular + .35 * .35);
    }

    private static Region textured(CloudNoiseField noise, SpaceVector pointKm, SpaceVector bodyPointKm, Region region) {
        if (Math.max(region.stratus, Math.max(region.cumulus, region.convection)) < .00001) { return region; }
        double broad = noise.sample(pointKm.x() * .005 + 3, pointKm.y() * .005 + 23, pointKm.z() * .005 + 11);
        double warp = noise.sample(pointKm.x() * .005 + 37, pointKm.y() * .005 + 7, pointKm.z() * .005 + 29);
        SpaceVector p = pointKm.add(new SpaceVector(broad - .5, warp - .5, broad - warp).multiply(80));
        double middle = noise.sample(p.x() / 60 + 13, p.y() / 60 + 43, p.z() / 60 + 5);
        double cellular = noise.sample(bodyPointKm.x() / 90 + 31, bodyPointKm.y() / 90 + 17, bodyPointKm.z() / 90 + 47);
        double field = broad * .30 + warp * .15 + middle * .25 + cellular * .30;
        return new Region(texturedMass(region.stratus, field), texturedMass(region.cumulus, field),
                texturedMass(region.convection, field));
    }

    private static double texturedMass(double regionalMass, double field) {
        return filteredMass(regionalMass, field, 0);
    }

    // Box-filtered expectation of nonlinear coverage; variance is that of the unresolved scalar field.
    static double filteredMass(double regionalMass, double meanField, double variance) {
        double threshold = .58 - regionalMass * .20;
        double spread = Math.sqrt(Math.max(0, 3 * variance));
        if (spread < .00001) { return regionalMass * smooth(threshold - .12, threshold + .12, meanField); }
        double start = (meanField - spread - threshold + .12) / .24;
        double finish = (meanField + spread - threshold + .12) / .24;
        return regionalMass * Math.clamp((smoothIntegral(finish) - smoothIntegral(start)) / (finish - start), 0, 1);
    }

    /** Regional cloud tops in kilometers, tapering cloud banks without changing the global integration bounds. */
    public static SpaceVector tops(Region region) {
        if (region == null) { throw new IllegalArgumentException("Cloud region required"); }
        return new SpaceVector(1.25 + .6 * smooth(.02, .6, region.stratus),
                2.4 + 1.8 * smooth(.02, .7, region.cumulus),
                4.8 + 3.7 * smooth(.02, .7, region.convection));
    }

    /** Vertically averaged smooth cloud profile. Thin decks keep their optical mass between distant ray steps. */
    public static double profile(double base, double top, double minimumAltitude, double maximumAltitude) {
        double span = top - base;
        if (!Double.isFinite(base) || !Double.isFinite(top) || !(span > 0) || !Double.isFinite(minimumAltitude) || !Double.isFinite(maximumAltitude)
                || maximumAltitude < minimumAltitude) { throw new IllegalArgumentException("Ordered finite cloud profile required"); }
        if (maximumAltitude - minimumAltitude < 1e-6) {
            return smooth(base, base + span * .15, minimumAltitude)
                    - smooth(base + span * .70, top, minimumAltitude);
        }
        return (profileIntegral(maximumAltitude, base, top) - profileIntegral(minimumAltitude, base, top))
                / (maximumAltitude - minimumAltitude);
    }

    private static double profileIntegral(double altitude, double base, double top) {
        altitude = Math.clamp(altitude, base, top);
        double span = top - base;
        return smoothIntegral((altitude - base) / (span * .15)) * (span * .15)
                - smoothIntegral((altitude - base - span * .70) / (span * .30)) * (span * .30);
    }

    private static double smoothIntegral(double x) {
        if (x <= 0) { return 0; }
        if (x >= 1) { return x - .5; }
        return x * x * x * (1 - x * .5);
    }

    private static System system(double latitude, double longitude, double radius, double rotation,
                                 double strength, boolean tropical) {
        double lat = Math.toRadians(latitude), lon = Math.toRadians(longitude);
        var center = new SpaceVector(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon));
        var east = new SpaceVector(-Math.sin(lon), 0, Math.cos(lon));
        var north = new SpaceVector(-Math.sin(lat) * Math.cos(lon), Math.cos(lat), -Math.sin(lat) * Math.sin(lon));
        return new System(center, east, north, radius, rotation, strength, tropical);
    }

    static double smooth(double low, double high, double value) {
        double x = Math.clamp((value - low) / (high - low), 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double clamp(double value) { return Math.clamp(value, 0, 1); }
}
