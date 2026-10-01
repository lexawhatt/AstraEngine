package dev.lexawhatt.astraengine.surface;

import java.util.Arrays;
import net.minecraft.core.SectionPos;

/**
 * Immutable read-only observation of one actual neighboring chunk section. Registry IDs are connection-local;
 * this is neither terrain generation nor a save format. State/light cells use (y * 16 + z) * 16 + x;
 * biome cells use (quartY * 4 + quartZ) * 4 + quartX. Light packs sky in the high nibble and block in the low.
 */
public final class EarthBoundarySection {
    public static final int CELL_COUNT = 4096;
    public static final int BIOME_COUNT = 64;
    public static final int MAX_REGISTRY_ID = 0x1FFFFF;
    private final EarthChart chart;
    private final SectionPos section;
    private final int[] states;
    private final byte[] light;
    private final int[] biomes;

    /** Copies caller data; rejects malformed sizes, registry-ID bounds and sections outside saved Earth storage. */
    public EarthBoundarySection(EarthChart chart, SectionPos section, int[] states, byte[] light, int[] biomes) {
        if (chart == null || section == null || states == null || light == null || biomes == null
                || states.length != CELL_COUNT || light.length != CELL_COUNT || biomes.length != BIOME_COUNT
                || section.y() < EarthChart.MIN_Y / 16 || section.y() >= (EarthChart.MIN_Y + EarthChart.HEIGHT) / 16
                || section.x() * 16.0 >= EarthChart.RADIUS_METERS || section.x() * 16.0 + 15 < -EarthChart.RADIUS_METERS
                || section.z() * 16.0 >= EarthChart.RADIUS_METERS || section.z() * 16.0 + 15 < -EarthChart.RADIUS_METERS) {
            throw new IllegalArgumentException("Invalid Earth boundary section extent or cell count");
        }
        requireIds(states); requireIds(biomes);
        this.chart = chart;
        this.section = SectionPos.of(section.x(), section.y(), section.z());
        this.states = states.clone(); this.light = light.clone(); this.biomes = biomes.clone();
    }

    /** Permanent chart identity whose actual blocks were observed. */
    public EarthChart chart() { return chart; }
    /** Value address; returns a defensive section position. */
    public SectionPos section() { return SectionPos.of(section.x(), section.y(), section.z()); }
    /** Block-state registry ID at index0..4095. Invalid indexes throw. */
    public int state(int index) { return states[index]; }
    /** Packed sky/block light at index0..4095, unsigned0..255. Invalid indexes throw. */
    public int light(int index) { return Byte.toUnsignedInt(light[index]); }
    /** Biome registry ID at quart-cell index0..63. Invalid indexes throw. */
    public int biome(int index) { return biomes[index]; }

    /** Exact numeric content comparison for suppressing unchanged observations; no world access. */
    public boolean sameContents(EarthBoundarySection other) {
        return other != null && chart.equals(other.chart) && section.equals(other.section)
                && Arrays.equals(states, other.states) && Arrays.equals(light, other.light) && Arrays.equals(biomes, other.biomes);
    }

    private static void requireIds(int[] values) {
        for (int value : values) {
            if (value < 0 || value > MAX_REGISTRY_ID) {
                throw new IllegalArgumentException("Earth boundary registry ID exceeds its three-byte wire budget");
            }
        }
    }
}
