package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.network.OrbitalSummaryPayload;
import dev.lexawhatt.astraengine.server.orbit.OrbitalCapture;
import dev.lexawhatt.astraengine.server.orbit.OrbitalPageStore;
import dev.lexawhatt.astraengine.server.orbit.OrbitalSummaryCodec;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPage;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalSurface;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual host block capture, wire validation and asynchronous page persistence; isolated from player worlds. */
@PrefixGameTestTemplate(false)
public final class OrbitalSummaryGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 200)
    public static void actualBlocksAndEmissionFollowBuildToggleAndRemoval(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = helper.absolutePos(BlockPos.ZERO);
        var chunk = level.getChunkAt(origin); int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        int y = 100;
        var surface = new OrbitalSurface("sol", "earth", level.dimension().location().toString(), CubeFace.POSITIVE_X,
                6_371_000, 4064);
        // The shared GameTest world has its own terrain and status lamps. Restore a controlled opaque
        // foundation, rather than assuming that everything revealed below a removed roof is nonemissive.
        for (int dz = 0; dz < 16; dz++) { for (int dx = 0; dx < 16; dx++) {
            level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.defaultBlockState(), 2);
        } }
        var baseline = OrbitalCapture.capture(level, surface, chunk, 1);
        helper.assertTrue(baseline.cells().stream().allMatch(cell -> cell.emission() == 0
                && cell.altitudeMeters() == y + 4064), "Controlled foundation was not the exposed surface");
        for (int dz = 0; dz < 16; dz++) { for (int dx = 0; dx < 16; dx++) {
            level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.GLOWSTONE.defaultBlockState(), 2);
        } }
        var lit = OrbitalCapture.capture(level, surface, chunk, 1);
        helper.assertTrue(lit.cells().stream().allMatch(cell -> cell.emission() == 1
                && cell.altitudeMeters() == y + 1 + 4064), "Real exposed lights or physical height were lost");
        for (int dz = 0; dz < 16; dz++) { for (int dx = 0; dx < 16; dx++) {
            level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.RED_CONCRETE.defaultBlockState(), 2);
        } }
        var dark = OrbitalCapture.capture(level, surface, chunk, 2);
        helper.assertTrue(dark.cells().stream().allMatch(cell -> cell.emission() == 0), "Removed block lights remained emissive");
        helper.assertTrue(!dark.sameContent(lit), "Material edit did not replace prior appearance");
        for (int dz = 0; dz < 16; dz++) { for (int dx = 0; dx < 16; dx++) {
            level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.AIR.defaultBlockState(), 2);
        } }
        var removed = OrbitalCapture.capture(level, surface, chunk, 3);
        helper.assertTrue(removed.sameContent(baseline),
                "Removed landmark did not restore the actual foundation: " + removed.cells());
        int ceiling = level.getMaxBuildHeight() - 1;
        for (int dz = 0; dz < 16; dz++) { for (int dx = 0; dx < 16; dx++) {
            level.setBlock(new BlockPos(x + dx, ceiling, z + dz), Blocks.STONE.defaultBlockState(), 2);
        } }
        var clipped = OrbitalCapture.capture(level, surface, chunk, 4);
        helper.assertTrue(!clipped.visible(), "Storage-band ceiling was mistaken for an exposed orbital roof");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void strictPageAndWireRoundTripsPreserveSparseEmission(GameTestHelper helper) {
        var patch = sample(); var key = OrbitalPage.Key.of(patch);
        var page = new OrbitalPage(key, Map.of()).with(patch);
        var encoded = OrbitalSummaryCodec.page(page);
        helper.assertTrue(OrbitalSummaryCodec.page(encoded, key).equals(page), "Page round trip changed observations");
        encoded.putInt("version", 17);
        try { OrbitalSummaryCodec.page(encoded, key); helper.assertTrue(false, "Future cache format was silently accepted"); }
        catch (IllegalArgumentException expected) { }
        var payload = new OrbitalSummaryPayload(5, true, true, List.of(patch, page.coarse(4)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            OrbitalSummaryPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(OrbitalSummaryPayload.CODEC.decode(buffer).equals(payload), "Wire lost cells or identity");
            buffer.clear();
            var largestIdentity = new OrbitalSurface("s".repeat(128), "b".repeat(128),
                    "astraengine:" + "d".repeat(244), CubeFace.POSITIVE_X, 6_371_000, 0);
            var maximumPatch = new OrbitalPatch(largestIdentity, -2_000_000, 2_000_000, 0, Long.MAX_VALUE, patch.cells());
            var maximum = new OrbitalSummaryPayload(Long.MAX_VALUE, true, true,
                    Collections.nCopies(OrbitalSummaryPayload.MAX_PATCHES, maximumPatch));
            OrbitalSummaryPayload.CODEC.encode(buffer, maximum);
            helper.assertTrue(buffer.readableBytes() <= OrbitalSummaryPayload.MAX_BYTES,
                    "Maximum valid identifiers and cells exceeded the advertised wire bound");
            helper.assertTrue(OrbitalSummaryPayload.CODEC.decode(buffer).equals(maximum),
                    "Maximum legal transaction failed its bounded round trip");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void asynchronousPagesSurviveOwnerRestartAndReplaceRemovedLights(GameTestHelper helper) {
        var patch = sample(); var key = OrbitalPage.Key.of(patch);
        var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("orbital-test-" + java.util.UUID.randomUUID());
        var store = new OrbitalPageStore(path);
        helper.startSequence().thenWaitUntil(() -> {
            store.tick(); helper.assertTrue(store.get(key).isPresent(), "Waiting for bounded page read");
        }).thenExecute(() -> {
            store.put(store.get(key).orElseThrow().with(patch));
        }).thenWaitUntil(() -> { store.tick(); helper.assertTrue(store.settled(), "Waiting for asynchronous page flush"); })
                .thenExecute(store::close).thenExecute(() -> {
                    var restored = new OrbitalPageStore(path);
                    helper.startSequence().thenWaitUntil(() -> {
                        restored.tick(); helper.assertTrue(restored.get(key).isPresent(), "Waiting for restart read");
                    }).thenExecute(() -> {
                        helper.assertTrue(restored.get(key).orElseThrow().chunks().values().contains(patch),
                                "Cache owner restart lost persisted surface observations");
                        var dark = new OrbitalPatch(patch.surface(), patch.x(), patch.z(), 0, 9,
                                Collections.nCopies(16, new OrbitalPatch.Cell(14, 0x222222, 0, 1)));
                        restored.put(restored.get(key).orElseThrow().with(dark));
                    }).thenWaitUntil(() -> { restored.tick(); helper.assertTrue(restored.settled(), "Waiting for dark update flush"); })
                            .thenExecute(restored::close).thenExecute(() -> {
                                var darkRestart = new OrbitalPageStore(path);
                                helper.startSequence().thenWaitUntil(() -> {
                                    darkRestart.tick(); helper.assertTrue(darkRestart.get(key).isPresent(), "Waiting for removal restart read");
                                }).thenExecute(() -> {
                                    helper.assertTrue(darkRestart.get(key).orElseThrow().chunks().get(OrbitalPage.slot(patch.x(), patch.z()))
                                            .cells().stream().allMatch(cell -> cell.emission() == 0 && cell.altitudeMeters() == 14),
                                            "Removed lights or height reappeared after a second cache owner restart");
                                    darkRestart.close();
                                }).thenSucceed();
                            });
                });
    }

    private static OrbitalPatch sample() {
        return new OrbitalPatch(new OrbitalSurface("sol", "earth", "minecraft:overworld", CubeFace.POSITIVE_X,
                6_371_000, 0), -17, 18, 0, 4, Collections.nCopies(16, new OrbitalPatch.Cell(112, 0xffb060, .5f, 1)));
    }
}
