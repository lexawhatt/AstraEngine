package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfacePatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceDebugCoordinatesTest {
    @Test
    void feetAltitudeUsesReferenceDatumAndPatchProjectionInBothWorlds() {
        for (String body : List.of("moon", "earth")) {
            SurfacePatch patch = SurfaceDefinition.byBody(body).patch();
            SpaceVector feet = new SpaceVector(731.5, 104.125, -413.5);
            SurfaceDebugCoordinates actual = SurfaceDebugCoordinates.fromFeet(patch, feet);
            GeographicPosition geographic = GeographicPosition.fromBody(patch.toBody(feet), patch.radiusMeters());
            assertEquals(Math.toDegrees(geographic.longitudeRadians()), actual.longitudeDegrees(), 1e-12);
            assertEquals(Math.toDegrees(geographic.latitudeRadians()), actual.latitudeDegrees(), 1e-12);
            assertEquals(40.125, actual.altitudeMeters(), 0);
            assertTrue(actual.longitudeDegrees() > 0 && actual.latitudeDegrees() > 0);
            assertEquals(-3.5, SurfaceDebugCoordinates.fromFeet(patch, new SpaceVector(0, 60.5, 0)).altitudeMeters());
            assertEquals(0, SurfaceDebugCoordinates.fromFeet(patch, new SpaceVector(0, 64, 0)).altitudeMeters());
        }
    }

    @Test
    void additionalPatchAnchorsAndReferenceHeightsDoNotAssumeVersionOneSeaLevel() {
        SurfacePatch patch = new SurfacePatch(6_371_000, Math.toRadians(37), Math.toRadians(-122), 4096, 320);
        SurfaceDebugCoordinates center = SurfaceDebugCoordinates.fromFeet(patch, new SpaceVector(0, 320, 0));
        assertEquals(-122, center.longitudeDegrees(), 1e-12);
        assertEquals(37, center.latitudeDegrees(), 1e-12);
        assertEquals(0, center.altitudeMeters());
        SurfaceDebugCoordinates southWest = SurfaceDebugCoordinates.fromFeet(patch, new SpaceVector(-100, 815, 100));
        assertTrue(southWest.longitudeDegrees() < center.longitudeDegrees());
        assertTrue(southWest.latitudeDegrees() < center.latitudeDegrees());
        assertEquals(495, southWest.altitudeMeters());
    }

    @Test
    void formattingIsStableAcrossLocalesAndUsesHemisphereSigns() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            SurfaceDebugCoordinates values = new SurfaceDebugCoordinates(-122.125, -37.25, -3.5);
            assertEquals("122.125000 deg W", values.longitudeText());
            assertEquals("37.250000 deg S", values.latitudeText());
            assertEquals("-3.50 m", values.altitudeText());
            SurfaceDebugCoordinates zero = new SurfaceDebugCoordinates(-0.0, -0.0, -0.0);
            assertEquals("0.000000 deg E", zero.longitudeText());
            assertEquals("0.000000 deg N", zero.latitudeText());
            assertEquals("0.00 m", zero.altitudeText());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void replacementPreservesUnrelatedDiagnosticsAndOnlyChangesPrimaryRows() {
        List<String> left = new ArrayList<>(List.of("Minecraft", "C: 12", "XYZ: 1 / 2 / 3", "Block: 1 2 3",
                "Other mod diagnostics", "Chunk: 0 0 0", "Facing: north", "Client Light: 15",
                "Block: unrelated later entry"));
        List<String> rows = List.of("Longitude: 0 deg E", "Latitude: 0 deg N", "Altitude: 2 m above reference");
        assertTrue(SurfaceDebugCoordinates.replacePrimaryRows(left, rows, false));
        assertEquals(List.of("Minecraft", "C: 12", rows.get(0), rows.get(1), "Other mod diagnostics", rows.get(2),
                "Facing: north", "Client Light: 15", "Block: unrelated later entry"), left);
        List<String> replaced = List.copyOf(left);
        assertFalse(SurfaceDebugCoordinates.replacePrimaryRows(left, rows, false));
        assertEquals(replaced, left);
    }

    @Test
    void reducedAndIncompleteDebugNeverAddCoordinates() {
        List<String> rows = List.of("longitude", "latitude", "altitude");
        for (List<String> source : List.of(List.of("Minecraft", "Chunk-relative: 1 2 3"),
                List.of("Minecraft", "XYZ: 1 2 3", "Block: 1 2 3"), List.<String>of())) {
            List<String> left = new ArrayList<>(source);
            assertFalse(SurfaceDebugCoordinates.replacePrimaryRows(left, rows, false));
            assertEquals(source, left);
        }
        List<String> full = List.of("XYZ: 1 2 3", "Block: 1 2 3", "Chunk: 0 0 0");
        assertFalse(SurfaceDebugCoordinates.replacePrimaryRows(full, rows, true));
        assertEquals(List.of("XYZ: 1 2 3", "Block: 1 2 3", "Chunk: 0 0 0"), full);
    }

    @Test
    void invalidCoordinatesAndReplacementContractsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDebugCoordinates(180, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDebugCoordinates(0, 91, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDebugCoordinates(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SurfaceDebugCoordinates.fromFeet(null, SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> SurfaceDebugCoordinates.replacePrimaryRows(List.of(), List.of("only one"), false));
    }
}
