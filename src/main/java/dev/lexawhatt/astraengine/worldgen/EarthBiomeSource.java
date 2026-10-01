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

    /** Copies a complete palette with exactly the lowercase EarthClimate names; nulls and missing keys fail. */
    public EarthBiomeSource(CubeFace face, Map<String, Holder<Biome>> palette) { this(face, 1, palette); }

    /** Versioned biome sampling; version one is retained when old saved sources omit the version field. */
    public EarthBiomeSource(CubeFace face, int terrainVersion, Map<String, Holder<Biome>> palette) {
        this.chart = new EarthChart(face, 0, terrainVersion);
        this.terrain = new ContinentalTerrain(terrainVersion, ContinentalTerrain.SEED);
        if (palette == null || palette.size() != EarthClimate.values().length) {
            throw new IllegalArgumentException("Earth biome palette must contain every named climate class exactly once");
        }
        Map<String, Holder<Biome>> copy = new LinkedHashMap<>();
        for (var climate : EarthClimate.values()) {
            String key = climate.name().toLowerCase(Locale.ROOT);
            Holder<Biome> biome = palette.get(key);
            if (biome == null) { throw new IllegalArgumentException("Missing Earth biome palette entry: " + key); }
            copy.put(key, biome);
        }
        this.palette = Map.copyOf(copy);
    }

    /** The pinned horizontal projection, independent of vertical storage. */
    public CubeFace face() { return chart.face(); }

    /** Exact saved terrain algorithm driving climate; it must match the chunk generator. */
    public int terrainVersion() { return terrain.version(); }

    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return palette.values().stream(); }

    /** Host inputs are quart coordinates. Climate sampling uses the corresponding four-block cell center. */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        var sample = terrain.sample(chart.normal(x * 4.0 + 2, z * 4.0 + 2));
        return palette.get(EarthClimate.at(sample).name().toLowerCase(Locale.ROOT));
    }

    private record Definition(String face, int terrainVersion, Map<String, Holder<Biome>> palette) {}
}
