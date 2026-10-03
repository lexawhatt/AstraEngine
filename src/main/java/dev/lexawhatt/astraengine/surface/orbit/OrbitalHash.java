package dev.lexawhatt.astraengine.surface.orbit;

/** Exact 32-bit sparse-atlas hash shared with orbital_edits.glsl; unsigned overflow is intentional. */
public final class OrbitalHash {
    public static final int SLOTS = 8192;
    public static final int MAX_PROBES = 128;
    private OrbitalHash() { }
    /** Marker combines render-body slot, immutable cube-face ordinal and level 0/4; zero is reserved for absence. */
    public static int marker(int bodyIndex, int face, int level) {
        if (bodyIndex < 0 || bodyIndex >= 12 || face < 0 || face >= 6 || level != 0 && level != 4) {
            throw new IllegalArgumentException("Invalid orbital atlas key");
        }
        return 1 + bodyIndex * 12 + face * 2 + (level == 4 ? 1 : 0);
    }
    /** Hash slot for signed chunk/page coordinates. */
    public static int slot(int x, int z, int marker) {
        int value = x * 0x8da6b343 ^ z * 0xd8163841 ^ marker * 0xcb1ab31f;
        value ^= value >>> 16; value *= 0x7feb352d; value ^= value >>> 15;
        return value & (SLOTS - 1);
    }
}
