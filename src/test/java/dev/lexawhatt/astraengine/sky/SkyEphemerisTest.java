package dev.lexawhatt.astraengine.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seasonal geometry, clock precision, orbital continuity and display/physical separation. */
class SkyEphemerisTest {
    @Test
    void defaultYearAndOrbitStartAtNorthernSpringEquinox() {
        PlanetarySkyProfile profile = PlanetarySkyProfile.DEFAULT;
        assertEquals(365, profile.yearDays());
        assertEquals(3, profile.sunSizeMultiplier());
        SkySample dawn = SkyEphemeris.sample(profile, 0, 0);
        assertEquals(0, dawn.seasonPhase(), 1.0e-12);
        assertEquals(0, dawn.declinationRadians(), 1.0e-12);
        assertEquals(12, dawn.daylightHours(), 1.0e-10);
        assertEquals(1, dawn.sunDirection().x(), 1.0e-10);
        assertEquals(0, dawn.sunDirection().y(), 1.0e-10);
    }

    @Test
    void winterIsShorterAndSunLowerThanSummerAtDefaultLatitude() {
        long noon = 6000;
        SkySample winter = sampleSeason(PlanetarySkyProfile.DEFAULT, noon, 0.75);
        SkySample summer = sampleSeason(PlanetarySkyProfile.DEFAULT, noon, 0.25);
        assertEquals(-23.44, Math.toDegrees(winter.declinationRadians()), 1.0e-9);
        assertEquals(23.44, Math.toDegrees(summer.declinationRadians()), 1.0e-9);
        assertTrue(winter.daylightHours() > 8.5 && winter.daylightHours() < 8.7);
        assertTrue(summer.daylightHours() > 15.3 && summer.daylightHours() < 15.5);
        assertEquals(24, winter.daylightHours() + summer.daylightHours(), 1.0e-9);
        assertTrue(summer.solarAltitudeRadians() > winter.solarAltitudeRadians() + 0.8);
    }

    @Test
    void southernSeasonsInvertAndEquatorRemainsTwelveHours() {
        PlanetarySkyProfile north = PlanetarySkyProfile.DEFAULT;
        PlanetarySkyProfile south = north.withLatitudeDegrees(-north.latitudeDegrees());
        assertEquals(sampleSeason(north, 6000, 0.25).daylightHours(),
                sampleSeason(south, 6000, 0.75).daylightHours(), 1.0e-9);
        for (double phase : new double[] {0, 0.25, 0.5, 0.75}) {
            assertEquals(12, sampleSeason(north.withLatitudeDegrees(0), 6000, phase).daylightHours(), 1.0e-9);
        }
    }

    @Test
    void polarDayNightAndEquinoxAreFinite() {
        PlanetarySkyProfile pole = PlanetarySkyProfile.DEFAULT.withLatitudeDegrees(90);
        assertEquals(24, sampleSeason(pole, 6000, 0.25).daylightHours());
        assertEquals(0, sampleSeason(pole, 6000, 0.75).daylightHours());
        assertEquals(12, sampleSeason(pole, 6000, 0).daylightHours());
        for (double latitude : new double[] {-90, -89.99999, 0, 89.99999, 90}) {
            for (double tilt : new double[] {0, 23.44, 90}) {
                for (double phase : new double[] {0, 0.25, 0.5, 0.75}) {
                    SkySample sample = sampleSeason(pole.withLatitudeDegrees(latitude).withAxialTiltDegrees(tilt), 0, phase);
                    assertEquals(1, sample.sunDirection().length(), 1.0e-12);
                    assertTrue(Double.isFinite(sample.daylightHours()));
                    assertTrue(Double.isFinite(sample.solarAltitudeRadians()));
                }
            }
        }
    }

    @Test
    void siderealBasisAgreesWithProjectedSolarEquatorialCoordinates() {
        PlanetarySkyProfile profile = PlanetarySkyProfile.DEFAULT;
        for (long tick = 0; tick < 365L * SkyEphemeris.TICKS_PER_DAY; tick += 7919) {
            SkySample sample = SkyEphemeris.sample(profile, tick, 0.5);
            double latitude = Math.toRadians(profile.latitudeDegrees());
            double theta = sample.siderealAngleRadians();
            double longitude = sample.seasonPhase() * Math.PI * 2;
            double tilt = Math.toRadians(profile.axialTiltDegrees());
            SpaceVector raZero = new SpaceVector(-Math.sin(theta), Math.cos(latitude) * Math.cos(theta),
                    Math.sin(latitude) * Math.cos(theta));
            SpaceVector raQuarter = new SpaceVector(Math.cos(theta), Math.cos(latitude) * Math.sin(theta),
                    Math.sin(latitude) * Math.sin(theta));
            SpaceVector northPole = new SpaceVector(0, Math.sin(latitude), -Math.cos(latitude));
            SpaceVector projected = raZero.multiply(Math.cos(longitude))
                    .add(raQuarter.multiply(Math.cos(tilt) * Math.sin(longitude)))
                    .add(northPole.multiply(Math.sin(tilt) * Math.sin(longitude)));
            assertTrue(projected.distance(sample.sunDirection()) < 1.0e-12);
        }
    }

    @Test
    void yearRolloverAndFractionalTicksAreContinuousWithoutPrecisionLoss() {
        PlanetarySkyProfile profile = PlanetarySkyProfile.DEFAULT;
        long yearTicks = (long) profile.yearDays() * SkyEphemeris.TICKS_PER_DAY;
        assertEquals(SkyEphemeris.sample(profile, 0, 0), SkyEphemeris.sample(profile, yearTicks, 0));
        assertEquals(SkyEphemeris.sample(profile, -1, 0.75), SkyEphemeris.sample(profile, yearTicks - 1, 0.75));
        assertTrue(SkyEphemeris.sample(profile, yearTicks - 1, 0.999).sunDirection()
                .distance(SkyEphemeris.sample(profile, yearTicks, 0).sunDirection()) < 1.0e-6);
        assertEquals(SkyEphemeris.sample(profile, Math.floorMod(Long.MAX_VALUE, yearTicks), 0.5),
                SkyEphemeris.sample(profile, Long.MAX_VALUE, 0.5));
        assertEquals(SkyEphemeris.sample(profile, Math.floorMod(Long.MIN_VALUE, yearTicks), 0.5),
                SkyEphemeris.sample(profile, Long.MIN_VALUE, 0.5));
        assertNotEquals(SkyEphemeris.sample(profile, Long.MAX_VALUE, 0),
                SkyEphemeris.sample(profile, Long.MAX_VALUE, 0.5));
        assertEquals(SkyEphemeris.sample(profile, 23999, 1), SkyEphemeris.sample(profile, 24000, 0));
    }

    @Test
    void keplerDistanceIsBoundedAndAngularRateIncreasesNearPerihelion() {
        PlanetarySkyProfile profile = new PlanetarySkyProfile(365, 45, 23.44, 0.8, 0, 0, 1);
        double perihelionPhase = 282.94 / 360;
        double aphelionPhase = (perihelionPhase + 0.5) % 1;
        PlanetarySkyProfile perihelion = SkyEphemeris.withSeasonAtTime(profile, 0, perihelionPhase);
        PlanetarySkyProfile aphelion = SkyEphemeris.withSeasonAtTime(profile, 0, aphelionPhase);
        assertEquals(0.2, SkyEphemeris.sample(perihelion, 0, 0).orbitalDistanceAu(), 1.0e-10);
        assertEquals(1.8, SkyEphemeris.sample(aphelion, 0, 0).orbitalDistanceAu(), 1.0e-10);
        double fast = SkyEphemeris.sample(perihelion, 100, 0).seasonPhase() - perihelionPhase;
        double slow = SkyEphemeris.sample(aphelion, 100, 0).seasonPhase() - aphelionPhase;
        assertTrue(fast > slow * 50);
        for (long tick = 0; tick < 365L * 24000; tick += 7039) {
            double distance = SkyEphemeris.sample(profile, tick, 0).orbitalDistanceAu();
            assertTrue(distance >= 0.2 - 1.0e-12 && distance <= 1.8 + 1.0e-12);
        }
    }

    @Test
    void seasonSelectionIsExactWithoutChangingRotationTickOrDisplayPhysics() {
        for (long time : new long[] {0, 6000, 23999, 123456789, Long.MAX_VALUE, Long.MIN_VALUE}) {
            for (double phase : new double[] {0, 0.25, 0.5, 0.75}) {
                SkySample sample = sampleSeason(PlanetarySkyProfile.DEFAULT, time, phase);
                assertEquals(phase, sample.seasonPhase(), 1.0e-11);
            }
        }
        PlanetarySkyProfile profile = PlanetarySkyProfile.DEFAULT;
        assertEquals(SkyEphemeris.sample(profile, 6000, 0),
                SkyEphemeris.sample(profile.withSunSizeMultiplier(8).withLightPollution(1), 6000, 0));
    }

    @Test
    void malformedProfilesAndTimeInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withYearDays(3));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withYearDays(1_000_001));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withLatitudeDegrees(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withLatitudeDegrees(90.01));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withAxialTiltDegrees(91));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withSeasonOffsetDays(365));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withSeasonOffsetDays(-1));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withLightPollution(1.1));
        assertThrows(IllegalArgumentException.class, () -> PlanetarySkyProfile.DEFAULT.withSunSizeMultiplier(0.5));
        assertThrows(IllegalArgumentException.class, () -> SkyEphemeris.sample(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SkyEphemeris.sample(PlanetarySkyProfile.DEFAULT, 0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> SkyEphemeris.sample(PlanetarySkyProfile.DEFAULT, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SkyEphemeris.withSeasonAtTime(PlanetarySkyProfile.DEFAULT, 0, 1));
    }

    private static SkySample sampleSeason(PlanetarySkyProfile profile, long dayTime, double phase) {
        return SkyEphemeris.sample(SkyEphemeris.withSeasonAtTime(profile, dayTime, phase), dayTime, 0);
    }
}
