package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthSurfacePalette;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Captures current host texture/biome colors on the render thread; snapshots outlive no resource references. */
final class EarthSurfaceMaterials {
    private EarthSurfaceMaterials() { }

    static EarthSurfacePalette capture() {
        RenderSystem.assertOnRenderThread();
        var game = Minecraft.getInstance();
        if (game.level == null) { throw new IllegalStateException("Earth materials require a connected biome registry"); }
        SpaceVector grass = atlasTexture("grass_block_top");
        SpaceVector oak = texture(Blocks.OAK_LEAVES);
        SpaceVector spruce = multiply(texture(Blocks.SPRUCE_LEAVES), rgb(0x619961));
        SpaceVector sand = texture(Blocks.SAND);
        SpaceVector snow = texture(Blocks.SNOW_BLOCK);
        SpaceVector rock = texture(Blocks.STONE);
        SpaceVector water = multiply(atlasTexture("water_still"), rgb(biome(Biomes.OCEAN).getWaterColor()));
        // Dense canopies dominate the visible land cover. The small ground fraction accounts for gaps;
        // host leaves already include texture color, biome tint and stable spruce tint where appropriate.
        return new EarthSurfacePalette(List.of(water.multiply(.62), water.multiply(.82), texture(Blocks.ICE), sand,
                snow, rock, sand, multiply(grass, rgb(biome(Biomes.SAVANNA).getGrassColor(0, 0))),
                canopy(texture(Blocks.JUNGLE_LEAVES), grass, Biomes.JUNGLE, .93),
                spruce.multiply(.92).add(multiply(grass, rgb(biome(Biomes.TAIGA).getGrassColor(0, 0))).multiply(.08)),
                canopy(oak, grass, Biomes.FOREST, .90),
                multiply(grass, rgb(biome(Biomes.PLAINS).getGrassColor(0, 0)))));
    }

    static void bind(ShaderInstance shader, EarthSurfacePalette palette) {
        for (int index = 0; index < palette.colors().size(); index++) {
            var color = palette.colors().get(index);
            // Host ShaderInstance treats count > 4 as a scalar array, not a vec4 array.
            shader.safeGetUniform("EarthSurfaceColors[" + index + "]").set(
                    (float) color.x(), (float) color.y(), (float) color.z(), index < 2 ? 1.0f : 0.0f);
        }
    }

    private static SpaceVector canopy(SpaceVector leaves, SpaceVector grass, ResourceKey<Biome> key, double cover) {
        var biome = biome(key);
        return multiply(leaves, rgb(biome.getFoliageColor())).multiply(cover)
                .add(multiply(grass, rgb(biome.getGrassColor(0, 0))).multiply(1 - cover));
    }

    private static Biome biome(ResourceKey<Biome> key) {
        return Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME).getOrThrow(key);
    }

    private static SpaceVector texture(Block block) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModel(block.defaultBlockState())
                .getParticleIcon();
        return average(sprite);
    }

    private static SpaceVector atlasTexture(String name) {
        return average(Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/" + name)));
    }

    private static SpaceVector average(TextureAtlasSprite sprite) {
        double red = 0, green = 0, blue = 0, weight = 0;
        // A bounded grid also handles large resource-pack textures without a render-thread full-image scan.
        int width = sprite.contents().width(), height = sprite.contents().height();
        int stepX = Math.max(1, Math.ceilDiv(width, 32)), stepY = Math.max(1, Math.ceilDiv(height, 32));
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                double alpha = (abgr >>> 24) / 255.0;
                red += (abgr & 255) / 255.0 * alpha;
                green += ((abgr >>> 8) & 255) / 255.0 * alpha;
                blue += ((abgr >>> 16) & 255) / 255.0 * alpha; weight += alpha;
            }
        }
        return weight == 0 ? new SpaceVector(.5, .5, .5) : new SpaceVector(red / weight, green / weight, blue / weight);
    }

    private static SpaceVector rgb(int rgb) {
        return new SpaceVector(((rgb >>> 16) & 255) / 255.0, ((rgb >>> 8) & 255) / 255.0, (rgb & 255) / 255.0);
    }

    private static SpaceVector multiply(SpaceVector a, SpaceVector b) {
        return new SpaceVector(a.x() * b.x(), a.y() * b.y(), a.z() * b.z());
    }
}
