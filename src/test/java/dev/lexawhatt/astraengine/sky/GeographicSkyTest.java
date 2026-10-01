package dev.lexawhatt.astraengine.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeographicSkyTest {
    @Test
    void observerLongitudeChangesLocalHourWithoutChangingOrbitalDate() {
        for (long time : new long[]{0, 6000, 24000L * 91, 24000L * 274, Long.MAX_VALUE, Long.MIN_VALUE}) {
            var zero = SkyEphemeris.sampleAt(PlanetarySkyProfile.EARTH, time, .25, new GeographicPosition(0, 0, 10));
            for (double longitude : new double[]{-Math.PI, -1, 0, 1, Math.PI - 1e-6}) {
                var sample = SkyEphemeris.sampleAt(PlanetarySkyProfile.EARTH, time, .25,
                        new GeographicPosition(0, longitude, 9000));
                assertEquals(zero.seasonPhase(), sample.seasonPhase(), 0);
                assertEquals(zero.orbitalDistanceAu(), sample.orbitalDistanceAu(), 0);
                assertEquals(zero.declinationRadians(), sample.declinationRadians(), 0);
                assertEquals(12, sample.daylightHours(), 1e-12);
                assertEquals(Math.cos(longitude), Math.cos(sample.siderealAngleRadians() - zero.siderealAngleRadians()), 1e-14);
            }
            var antipode = SkyEphemeris.sampleAt(PlanetarySkyProfile.EARTH, time, .25,
                    new GeographicPosition(0, -Math.PI, 0));
            assertEquals(-zero.sunDirection().y(), antipode.sunDirection().y(), 1e-14);
        }
    }

    @Test
    void polarChartSkyHasNoArbitraryLongitudeSpinOrAltitudeBandJump() {
        for (var face : new CubeFace[]{CubeFace.POSITIVE_Y, CubeFace.NEGATIVE_Y}) {
            double latitude = face == CubeFace.POSITIVE_Y ? Math.PI / 2 : -Math.PI / 2;
            SpaceVector expected = null;
            for (var chart : EarthChart.ALL.stream().filter(value -> value.face() == face).toList()) {
                for (double longitude : new double[]{-Math.PI, -.43, 0, .91, 2.87}) {
                    var address = new GeographicPosition(latitude, longitude, chart.altitudeOriginMeters() + 150);
                    var sample = SkyEphemeris.sampleAt(PlanetarySkyProfile.EARTH, 1345757, .9, address);
                    var local = chart.localSkyDirection(address, sample.sunDirection());
                    assertEquals(1, local.length(), 1e-14);
                    assertEquals(sample.sunDirection().y(), local.y(), 1e-14);
                    if (expected != null) { assertTrue(local.distance(expected) < 1e-14); }
                    expected = local;
                }
            }
        }
    }

    @Test
    void latitudeControlsSeasonalDaylightAndOriginalProfileRemainsImmutable() {
        var profile = SkyEphemeris.withSeasonAtTime(PlanetarySkyProfile.EARTH, 0, .25);
        var north = SkyEphemeris.sampleAt(profile, 0, 0, new GeographicPosition(Math.toRadians(52), 0, 0));
        var south = SkyEphemeris.sampleAt(profile, 0, 0, new GeographicPosition(Math.toRadians(-52), 1, 0));
        assertTrue(north.daylightHours() > 16 && south.daylightHours() < 8);
        assertEquals(24, north.daylightHours() + south.daylightHours(), 1e-12);
        assertEquals(45, profile.latitudeDegrees(), 0);
        assertThrows(IllegalArgumentException.class, () -> SkyEphemeris.sampleAt(profile, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new EarthChart(CubeFace.POSITIVE_X, 0)
                .localSkyDirection(new GeographicPosition(0, -Math.PI, 0), new SpaceVector(1, 0, 0)));
    }
}
