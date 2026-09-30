package dev.lexawhatt.astraengine.client.sky;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkyIlluminationTest {
    @Test
    void sunlightFollowsAltitudeInsteadOfTheVanillaClock() {
        var day = SkyIllumination.skyLight(0.6, 0, 0, 1);
        var night = SkyIllumination.skyLight(-0.4, 0, 0, 1);
        var dusk = SkyIllumination.skyLight(0.03, 0, 0, 1);
        assertTrue(day.x() > night.x() * 20);
        assertTrue(day.z() > night.z() * 10);
        assertTrue(dusk.x() > dusk.z());
        assertTrue(night.z() > night.x());
    }

    @Test
    void extinctionAndStormCannotMakeSkylightBrighter() {
        var healthy = SkyIllumination.skyLight(0.6, 0, 0, 1);
        var dark = SkyIllumination.skyLight(0.6, 0, 0, 0);
        var storm = SkyIllumination.skyLight(0.6, 1, 1, 1);
        assertTrue(dark.length() == 0);
        assertTrue(storm.length() < healthy.length() * 0.3);
    }

    @Test
    void pollutionOnlyAddsNightHazeAndTwilightIsDirectional() {
        var clean = SkyIllumination.fog(-0.4, 0, 0, 0, 1, 0, 0);
        var polluted = SkyIllumination.fog(-0.4, 0, 0, 0, 1, 0, 1);
        assertTrue(polluted.x() > clean.x() * 5);
        var sunset = SkyIllumination.fog(0, 1, 0, 0, 1, 0, 0);
        var away = SkyIllumination.fog(0, 0, 0, 0, 1, 0, 0);
        assertTrue(sunset.x() > away.x());
        assertTrue(sunset.z() < away.z());
    }
}
