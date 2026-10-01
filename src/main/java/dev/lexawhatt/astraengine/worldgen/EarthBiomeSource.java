package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Immutable, worker-safe biome palette driven by the same spherical field as Earth terrain and orbital detail.
 * Registry-owned biome holders retain host/consumer placed features. Surface climate is independent of the
 * storage band; this source does not add subterranean biomes, own chunks or mutate biome definitions.
 */
public final class EarthBiomeSource extends BiomeSource {
    private static final MapCodec<Definition> DEFINITION_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("face").forGetter(Definition::face),
            TerrainCodecs.EXACT_INT.optionalFieldOf("terrain_version", 1).forGetter(Definition::terrainVersion),
            Codec.unboundedMap(Codec.STRING, Biome.CODEC).fieldOf("palette").forGetter(Definition::palette)
    ).apply(instance, Definition::new));

    /** Names, rather than enum ordinals, persist the complete climate palette; malformed faces/palettes fail. */
    public static final MapCodec<EarthBiomeSource> CODEC = DEFINITION_CODEC.flatXmap(definition -> {
        try {
            return DataResult.success(new EarthBiomeSource(CubeFace.fromId(definition.face()), definition.terrainVersion(), definition.palette()));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(exception::getMessage);
        }
    }, source -> DataResult.success(new Definition(source.chart.face().id(), source.terrain.version(), source.palette)));

    private final EarthChart chart;
    private final ContinentalTerrain terrain;
    private final Map<String, Holder<Biome>> palette;

    /** Copies the legacy palette with exactly the lowercase EarthClimate names; nulls and missing keys fail. */
    public EarthBiomeSource(CubeFace face, Map<String, Holder<Biome>> palette) { this(face, 1, palette); }

    /** Versioned biome sampling; v3 additionally requires a river holder. Missing saved versions retain v1. */
    public EarthBiomeSource(CubeFace face, int terrainVersion, Map<String, Holder<Biome>> palette) {
        this.chart = new EarthChart(face, 0, terrainVersion);
        this.terrain = new ContinentalTerrain(terrainVersion, ContinentalTerrain.SEED);
        if (palette == null || palette.size() != EarthClimate.values().length + (terrainVersion >= 3 ? 1 : 0)) {
            throw new IllegalArgumentException("Earth biome palette must contain every named climate class exactly once");
        }
        Map<String, Holder<Biome>> copy = new LinkedHashMap<>();
        for (var climate : EarthClimate.values()) {
            String key = climate.name().toLowerCase(Locale.ROOT);
            Holder<Biome> biome = palette.get(key);
            if (biome == null) { throw new IllegalArgumentException("Missing Earth biome palette entry: " + key); }
            copy.put(key, biome);
        }
        if (terrainVersion >= 3) {
            var river = palette.get("river");
            if (river == null) { throw new IllegalArgumentException("Missing Earth biome palette entry: river"); }
            copy.put("river", river);
        }
        this.palette = Map.copyOf(copy);
    }

    /** The pinned horizontal projection, independent of vertical storage. */
    public CubeFace face() { return chart.face(); }

    /** Exact saved terrain algorithm driving climate; it must match the chunk generator. */
    public int terrainVersion() { return terrain.version(); }

    /** Immutable registry holder from this saved source's palette; supports consumer-defined biome palettes. */
    public Holder<Biome> biome(EarthClimate climate) {
        if (climate == null) { throw new IllegalArgumentException("A climate class is required"); }
        return palette.get(climate.name().toLowerCase(Locale.ROOT));
    }

    /** Registry holder for the exact sampled column; v3 includes a separate river biome with host features. */
    public Holder<Biome> biome(ContinentalTerrain.Sample sample) {
        if (sample == null) { throw new IllegalArgumentException("A terrain observation is required"); }
        return terrain.version() >= 3 && sample.river() ? palette.get("river") : biome(EarthClimate.at(sample));
    }

    /** River holder for v3, or the legacy ocean holder where no inland water exists. */
    public Holder<Biome> riverBiome() { return palette.getOrDefault("river", biome(EarthClimate.OCEAN)); }

    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return palette.values().stream(); }

    /** Host inputs are quart coordinates. Climate sampling uses the corresponding four-block cell center. */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        var sample = terrain.sample(chart.normal(x * 4.0 + 2, z * 4.0 + 2));
        return biome(sample);
    }

    private record Definition(String face, int terrainVersion, Map<String, Holder<Biome>> palette) {}
}
