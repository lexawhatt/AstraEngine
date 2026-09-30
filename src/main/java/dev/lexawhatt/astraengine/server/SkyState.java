package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Overworld-save-owned visual sky settings. Access and mutation require the owning logical-server thread. */
public final class SkyState extends SavedData {
    private static final String FILE_NAME = "astraengine_sky";
    private PlanetarySkyProfile profile;
    private long revision;

    private SkyState(PlanetarySkyProfile profile, long revision) {
        if (profile == null || revision < 0) { throw new IllegalArgumentException("Invalid saved sky state"); }
        this.profile = profile;
        this.revision = revision;
    }

    /** Loads saved settings, rejecting unreadable existing data instead of replacing it with defaults. */
    public static SkyState get(MinecraftServer server) {
        if (server == null) { throw new IllegalArgumentException("A Minecraft server is required for sky state"); }
        if (!server.isSameThread()) { throw new IllegalStateException("Sky state requires the owning server thread"); }
        Factory<SkyState> factory = new Factory<>(() -> {
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME + ".dat"))) {
                throw new IllegalStateException("Existing sky state could not be read; preserve it and restore a backup");
            }
            SkyState state = new SkyState(PlanetarySkyProfile.DEFAULT, 0);
            state.setDirty();
            return state;
        }, (tag, registries) -> decode(tag), null);
        return server.overworld().getDataStorage().computeIfAbsent(factory, FILE_NAME);
    }

    /** Returns immutable settings. No independent simulation clock is stored here. */
    public PlanetarySkyProfile profile() { return profile; }

    /** Monotonic settings revision, advanced only by a changed profile. */
    public long revision() { return revision; }

    /**
     * Applies a validated immutable profile on the owning server thread; equal settings are a no-op.
     * Null or exhausted revision rejects before mutation. Use AstraSky.configure to notify connected clients.
     */
    public boolean configure(PlanetarySkyProfile replacement) {
        if (replacement == null) { throw new IllegalArgumentException("A planetary sky profile is required"); }
        if (profile.equals(replacement)) { return false; }
        if (revision == Long.MAX_VALUE) { throw new IllegalStateException("Sky settings revision exhausted"); }
        profile = replacement;
        revision++;
        setDirty();
        return true;
    }

    /** Strict format-one decoding. Missing fields, wrong NBT types and invalid numeric ranges are rejected. */
    public static SkyState decode(CompoundTag tag) {
        if (tag == null) { throw new IllegalArgumentException("Saved sky tag must not be null"); }
        require(tag, Tag.TAG_INT, "version", "year_days");
        require(tag, Tag.TAG_LONG, "revision");
        require(tag, Tag.TAG_DOUBLE, "latitude_degrees", "axial_tilt_degrees", "eccentricity",
                "season_offset_days", "light_pollution", "sun_size_multiplier");
        if (tag.getInt("version") != 1) { throw new IllegalArgumentException("Unsupported sky state format"); }
        return new SkyState(new PlanetarySkyProfile(tag.getInt("year_days"), tag.getDouble("latitude_degrees"),
                tag.getDouble("axial_tilt_degrees"), tag.getDouble("eccentricity"), tag.getDouble("season_offset_days"),
                tag.getDouble("light_pollution"), tag.getDouble("sun_size_multiplier")), tag.getLong("revision"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);
        tag.putLong("revision", revision);
        tag.putInt("year_days", profile.yearDays());
        tag.putDouble("latitude_degrees", profile.latitudeDegrees());
        tag.putDouble("axial_tilt_degrees", profile.axialTiltDegrees());
        tag.putDouble("eccentricity", profile.eccentricity());
        tag.putDouble("season_offset_days", profile.seasonOffsetDays());
        tag.putDouble("light_pollution", profile.lightPollution());
        tag.putDouble("sun_size_multiplier", profile.sunSizeMultiplier());
        return tag;
    }

    private static void require(CompoundTag tag, int type, String... keys) {
        for (String key : keys) {
            if (!tag.contains(key, type)) { throw new IllegalArgumentException("Missing or invalid saved sky field: " + key); }
        }
    }
}
