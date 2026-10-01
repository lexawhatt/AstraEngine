package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.network.EarthBoundaryPayload;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthBoundaryPlan;
import dev.lexawhatt.astraengine.surface.EarthChartRebase;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import dev.lexawhatt.astraengine.surface.EarthChart;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-registry observation wire checks, malformed bounds and defensive numeric ownership. */
@PrefixGameTestTemplate(false)
public final class EarthBoundaryGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void boundaryPlansCoverCanonicalNeighborsWithinTheirSectionBudget(GameTestHelper helper) {
        double radius = EarthChart.RADIUS_METERS;
        int checked = 0;
        for (EarthChart source : EarthChart.all(2)) {
            for (double x : new double[]{-radius + .125, 0, radius - .125}) {
                for (double z : new double[]{-radius + .125, 0, radius - .125}) {
                    for (double y : new double[]{-2031.875, -2000.5, 0, 2000.5, 2031.875}) {
                        var anchor = new SpaceVector(x, y, z);
                        var result = EarthBoundaryPlan.around(source, anchor);
                        if (result.isEmpty()) { continue; }
                        var plan = result.orElseThrow();
                        helper.assertTrue(plan.sections().size() <= EarthBoundarySnapshot.MAX_SECTIONS
                                && plan.sections().stream().distinct().count() == plan.sections().size(), "Seam request exceeds its unique-section budget");
                        for (var address : plan.sections()) {
                            var center = address.section().center();
                            var represented = new EarthChartTransform(address.chart(), source)
                                    .position(new SpaceVector(center.getX(), center.getY(), center.getZ()));
                            helper.assertTrue(represented.distance(anchor) <= EarthChartRebase.MAX_EXTENSION_METERS,
                                    "Planned section exceeds its observation neighborhood");
                        }
                        double extent = plan.radiusMeters() * .73;
                        for (double dx : new double[]{-extent, 0, extent}) {
                            for (double dy : new double[]{-extent, 0, extent}) {
                                for (double dz : new double[]{-extent, 0, extent}) {
                                    var sample = EarthChartRebase.resolve(source, plan.focusFeet().add(new SpaceVector(dx, dy, dz)),
                                            SpaceVector.ZERO, FlightOrientation.IDENTITY);
                                    if (sample.isEmpty()) { continue; }
                                    var value = sample.orElseThrow();
                                    int bx = (int) Math.floor(value.feet().x()), by = (int) Math.floor(value.feet().y()), bz = (int) Math.floor(value.feet().z());
                                    // An exact face tie can belong to a different voxel than its zero-area feet address.
                                    if (!value.chart().contains(new SpaceVector(bx + .5, by + .5, bz + .5))) { continue; }
                                    var address = new EarthBoundaryPlan.Address(value.chart(), SectionPos.of(bx >> 4, by >> 4, bz >> 4));
                                    helper.assertTrue(plan.sections().contains(address), "Seam plan missed a canonical neighboring section");
                                }
                            }
                        }
                        checked++;
                    }
                }
            }
        }
        helper.assertTrue(checked > 1000, "Boundary coverage fixture skipped its edge cases");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void boundaryObservationsRetainEditsAndRejectMalformedPackets(GameTestHelper helper) {
        var source = new EarthChart(CubeFace.POSITIVE_X, 0, 2);
        var target = new EarthChart(CubeFace.POSITIVE_X, 1, 2);
        int[] states = new int[4096]; byte[] light = new byte[4096]; int[] biomes = new int[64];
        Arrays.fill(states, Block.getId(Blocks.STONE.defaultBlockState()));
        states[123] = Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()); light[123] = (byte) 0xCE;
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        Arrays.fill(biomes, registry.getId(registry.getOrThrow(Biomes.PLAINS)));
        var section = new EarthBoundarySection(target, SectionPos.of(0, -127, 0), states, light, biomes);
        int savedBiome = biomes[0]; states[123] = 0; light[123] = 0; biomes[0] = 0;
        helper.assertTrue(section.state(123) == Block.getId(Blocks.GOLD_BLOCK.defaultBlockState())
                && section.light(123) == 0xCE && section.biome(0) == savedBiome, "Observation retained mutable caller arrays");
        var mutableSections = new ArrayList<>(List.of(section));
        var snapshot = new EarthBoundarySnapshot(7, source, new SpaceVector(0, 2031, 0), true, mutableSections);
        mutableSections.clear(); helper.assertTrue(snapshot.sections().size() == 1, "Observation retained mutable section list");
        helper.assertTrue(snapshot.visibleFrom(source, snapshot.anchorFeet())
                && snapshot.visibleFrom(target, new SpaceVector(0, -2031, 0))
                && !snapshot.visibleFrom(target, new SpaceVector(0, 0, 0))
                && !snapshot.visibleFrom(target, new SpaceVector(0, -20_000_000, 0)), "Observation proximity or adjacent-band ownership changed");
        var adjacent = new EarthChart(CubeFace.NEGATIVE_Z, 0, 2);
        var faceSection = new EarthBoundarySection(adjacent, SectionPos.of((int) Math.floor(-EarthChart.RADIUS_METERS / 16), 0, 0),
                states, light, biomes);
        var faceView = new EarthBoundarySnapshot(8, source, new SpaceVector(EarthChart.RADIUS_METERS - 1, 8, 8), true, List.of(faceSection));
        helper.assertTrue(!faceView.visibleFrom(adjacent, new SpaceVector(EarthChart.RADIUS_METERS - 1, 8, 8)),
                "Distant teleport into an old neighbor's opposite hemisphere retained a singular observation");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            EarthBoundaryPayload.CODEC.encode(buffer, new EarthBoundaryPayload(snapshot));
            var restored = EarthBoundaryPayload.CODEC.decode(buffer).snapshot();
            helper.assertTrue(buffer.readableBytes() == 0 && restored.revision() == 7 && restored.complete()
                    && restored.source().equals(source) && restored.anchorFeet().equals(snapshot.anchorFeet())
                    && section.sameContents(restored.sections().getFirst()), "Boundary wire lost actual edit, light or identity");
            buffer.clear(); buffer.writeLong(-1); buffer.writeUtf("px"); buffer.writeByte(0); buffer.writeVarInt(2);
            buffer.writeDouble(0); buffer.writeDouble(2031); buffer.writeDouble(0); buffer.writeBoolean(true); buffer.writeVarInt(33);
            rejected(helper, () -> EarthBoundaryPayload.CODEC.decode(buffer), "Invalid header/count");
            buffer.clear(); buffer.writeZero(EarthBoundaryPayload.MAX_BYTES + 1);
            rejected(helper, () -> EarthBoundaryPayload.CODEC.decode(buffer), "Oversized packet");
            buffer.clear();
            Arrays.fill(states, EarthBoundarySection.MAX_REGISTRY_ID);
            var unknown = new EarthBoundarySection(target, SectionPos.of(0, -127, 0), states, light, biomes);
            EarthBoundaryPayload.CODEC.encode(buffer, new EarthBoundaryPayload(new EarthBoundarySnapshot(8, source,
                    snapshot.anchorFeet(), true, List.of(unknown))));
            rejected(helper, () -> EarthBoundaryPayload.CODEC.decode(buffer), "Unknown block state");
        } finally { buffer.release(); }
        rejected(helper, () -> new EarthBoundarySnapshot(8, source, snapshot.anchorFeet(), true, List.of(section, section)),
                "Duplicate canonical section");
        rejected(helper, () -> new EarthBoundarySection(target, SectionPos.of(Integer.MAX_VALUE, -127, 0), states, light, biomes),
                "Overflowing section coordinate");
        rejected(helper, () -> new EarthBoundarySnapshot(9, source, new SpaceVector(0, 0, 0), true, List.of(section)),
                "Section outside anchor neighborhood");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void largestBoundaryObservationHasABoundedWireAllocation(GameTestHelper helper) {
        var source = new EarthChart(CubeFace.POSITIVE_X, 0, 2);
        var target = new EarthChart(CubeFace.POSITIVE_X, 1, 2);
        int[] states = new int[4096]; byte[] light = new byte[4096]; int[] biomes = new int[64];
        // Numeric maximum intentionally need not exist in this registry: measure the format's true worst case.
        Arrays.fill(states, EarthBoundarySection.MAX_REGISTRY_ID); Arrays.fill(biomes, EarthBoundarySection.MAX_REGISTRY_ID);
        var sections = new ArrayList<EarthBoundarySection>();
        for (int y = -127; y <= -126; y++) {
            for (int x = -2; x < 2; x++) {
                for (int z = -2; z < 2; z++) {
                    sections.add(new EarthBoundarySection(target, SectionPos.of(x, y, z), states, light, biomes));
                }
            }
        }
        var snapshot = new EarthBoundarySnapshot(1, source, new SpaceVector(0, 2016, 0), true, sections);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            EarthBoundaryPayload.CODEC.encode(buffer, new EarthBoundaryPayload(snapshot));
            helper.assertTrue(buffer.readableBytes() <= EarthBoundaryPayload.MAX_BYTES
                    && sections.size() == EarthBoundarySnapshot.MAX_SECTIONS, "Maximum boundary packet exceeded its budget");
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static void rejected(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
