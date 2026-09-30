package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfacePatch;
import java.util.List;
import java.util.Locale;

/** Pure geographic F3 values: east/north-positive degrees and player-feet altitude above the reference sphere. */
public record SurfaceDebugCoordinates(double longitudeDegrees, double latitudeDegrees, double altitudeMeters) {
    public SurfaceDebugCoordinates {
        if (!Double.isFinite(longitudeDegrees) || longitudeDegrees < -180 || longitudeDegrees >= 180
                || !Double.isFinite(latitudeDegrees) || Math.abs(latitudeDegrees) > 90
                || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Surface debug coordinates require finite canonical geographic values");
        }
    }

    /** Converts local player feet in meters; camera height, orbital position and terrain edits do not alter the datum. */
    public static SurfaceDebugCoordinates fromFeet(SurfacePatch patch, SpaceVector localFeetMeters) {
        if (patch == null || localFeetMeters == null) {
            throw new IllegalArgumentException("Surface debug coordinates require a patch and local feet position");
        }
        GeographicPosition direction = GeographicPosition.fromBody(
                patch.normal(localFeetMeters.x(), localFeetMeters.z()), 1);
        return new SurfaceDebugCoordinates(Math.toDegrees(direction.longitudeRadians()),
                Math.toDegrees(direction.latitudeRadians()), localFeetMeters.y() - patch.seaY());
    }

    /** Locale-independent longitude value, with an explicit east/west hemisphere. */
    public String longitudeText() {
        return String.format(Locale.ROOT, "%.6f deg %s", Math.abs(longitudeDegrees), longitudeDegrees < 0 ? "W" : "E");
    }

    /** Locale-independent latitude value, with an explicit north/south hemisphere. */
    public String latitudeText() {
        return String.format(Locale.ROOT, "%.6f deg %s", Math.abs(latitudeDegrees), latitudeDegrees < 0 ? "S" : "N");
    }

    /** Signed feet altitude, in meters above the patch's reference sphere, never inferred ground clearance. */
    public String altitudeText() {
        return String.format(Locale.ROOT, "%.2f m", altitudeMeters == 0 ? 0 : altitudeMeters);
    }

    /**
     * Replaces only the first complete vanilla XYZ/Block/Chunk row sequence in a mutable left debug list.
     * Other diagnostics retain their order and text. Reduced debug never inserts geographic coordinates;
     * absent/partial host rows are left untouched. The three replacement labels may be localized by the client.
     * Returns whether the host rows were replaced; null arguments or a different replacement count are invalid.
     */
    public static boolean replacePrimaryRows(List<String> left, List<String> geographicRows, boolean reducedDebug) {
        if (left == null || geographicRows == null || geographicRows.size() != 3
                || geographicRows.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Surface debug replacement requires a list and three geographic rows");
        }
        if (reducedDebug) { return false; }
        int xyz = findRow(left, "XYZ: ", 0);
        int block = xyz < 0 ? -1 : findRow(left, "Block: ", xyz + 1);
        int chunk = block < 0 ? -1 : findRow(left, "Chunk: ", block + 1);
        if (chunk < 0) { return false; }
        left.set(xyz, geographicRows.get(0));
        left.set(block, geographicRows.get(1));
        left.set(chunk, geographicRows.get(2));
        return true;
    }

    private static int findRow(List<String> lines, String prefix, int start) {
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i) != null && lines.get(i).startsWith(prefix)) { return i; }
        }
        return -1;
    }
}
