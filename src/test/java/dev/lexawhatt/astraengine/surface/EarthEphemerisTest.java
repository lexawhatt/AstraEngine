package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthEphemerisTest {
    @Test
    void physicalSunAgreesWithTheGeographicSkyAtEverySeasonAndPole() {
        for (var profile : new PlanetarySkyProfile[] {PlanetarySkyProfile.EARTH,
                PlanetarySkyProfile.EARTH.withYearDays(96).withSeasonOffsetDays(17),
                PlanetarySkyProfile.EARTH.withAxialTiltDegrees(0),
                new PlanetarySkyProfile(365, 45, 65, .6, 213, 0, 3)}) {
            long yearTicks = (long) profile.yearDays() * 24_000;
            for (long tick : new long[] {-yearTicks - 10, -1, 0, 6000, 12_000, 18_000,
                    yearTicks / 4, yearTicks / 2, yearTicks * 3 / 4, yearTicks - 1, yearTicks, yearTicks + 1}) {
                var physical = EarthEphemeris.sample(profile, tick, .37);
                var light = physical.frame().toBodyDirection(physical.frame().centerMeters().multiply(-1)).normalized();
                for (double latitude : new double[] {-90, -60, 0, 45, 90}) {
                    for (double longitude : new double[] {-180, -90, 0, 80, 179}) {
                        var observer = new GeographicPosition(Math.toRadians(latitude), Math.toRadians(longitude), 0);
                        var chart = EarthChart.owner(observer, 2).orElseThrow();
                        var feet = chart.resolve(observer).orElseThrow();
                        var localLight = chart.tangentFrame(feet.x(), feet.z(), 0).toLocalDirection(light);
                        var sky = SkyEphemeris.sampleAt(profile, tick, .37, observer);
                        assertTrue(localLight.distance(chart.localSkyDirection(observer, sky.sunDirection())) < 1e-10,
                                () -> "Orbital Sun disagrees with ground sky at " + tick + " / " + observer);
                    }
                }
            }
        }
    }

    @Test
    void oneGameYearAdvancesOneCanonicalOrbitWithoutResettingOtherPlanets() {
        var earth = CosmosGenerator.sol().bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
        var profile = PlanetarySkyProfile.EARTH;
        long yearTicks = 365 * 24_000L;
        var first = EarthEphemeris.sample(profile, 0, 0);
        var next = EarthEphemeris.sample(profile, yearTicks, 0);
        assertEquals(earth.orbitalPeriodSeconds(), next.orbitalSeconds() - first.orbitalSeconds(), 1e-7);
        assertTrue(first.frame().centerMeters().distance(next.frame().centerMeters()) < .001);
        double last = EarthEphemeris.sample(profile, -yearTicks, 0).orbitalSeconds();
        for (long tick = -yearTicks + 1000; tick <= yearTicks * 2; tick += 1000) {
            double current = EarthEphemeris.sample(profile, tick, 0).orbitalSeconds();
            assertTrue(current > last, "Calendar-to-orbit mapping jumped backwards");
            assertEquals(1000 * earth.orbitalPeriodSeconds() / yearTicks, current - last, 1e-6);
            last = current;
        }
    }

    @Test
    void framePreservesPhysicalScaleAndRejectsInvalidSampling() {
        var profile = PlanetarySkyProfile.EARTH;
        var frame = EarthEphemeris.sample(profile, 6000, .5).frame();
        var point = new GeographicPosition(.8, 2.1, 7123).toBody(EarthChart.RADIUS_METERS);
        assertEquals(6_371_000, frame.radiusMeters());
        assertTrue(frame.toBodyPoint(frame.toSystemPoint(point)).distance(point) < .0001);
        assertEquals(1, frame.toSystemDirection(new SpaceVector(0, 1, 0)).length(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> EarthEphemeris.sample(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EarthEphemeris.sample(profile, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> EarthEphemeris.sample(profile, 0, 1.1));
    }
}
