package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.PulsarGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.CosmosDescriptorCodec;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated-host persistence and wire checks for additive pulsars; native fixtures cover real manual entry. */
@PrefixGameTestTemplate(false)
public final class PulsarGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void versionSixMigrationPreservesExactPriorContent(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = detachedEmptyCatalog(helper);
        UUID id = UUID.randomUUID();
        catalog.player(id);
        CosmosSystem custom = CelestialApiFixtures.simple("p_mod:eden", "Prior private Eden");
        helper.assertTrue(catalog.createSystem(custom) == AstraCosmos.CreateResult.CREATED
                        && catalog.discover(id, custom.id()) == AstraCosmos.DiscoverResult.DISCOVERED,
                "Could not establish private content in the isolated migration fixture");
        catalog.tick(true);
        CompoundTag expected = catalog.save(new CompoundTag(), server.registryAccess());
        CompoundTag legacy = expected.copy();
        legacy.putInt("version", 6);
        legacy.remove("pulsar_version");
        CompoundTag original = legacy.copy();
        ExplorationCatalog migrated = ExplorationCatalog.decode(legacy);
        helper.assertTrue(migrated.isDirty() && migrated.save(new CompoundTag(), server.registryAccess()).equals(expected),
                "V6 migration changed prior descriptors, seed, clock, position, orientation, speed, chart or visits");
        helper.assertTrue(legacy.equals(original) && migrated.system(custom.id()).equals(custom),
                "Migration mutated input or confused a private p_ namespace with a pulsar ID");
        helper.assertTrue(expected.getInt("version") == 7 && expected.getInt("pulsar_version") == PulsarGenerator.VERSION,
                "Pulsar generation was not independently pinned");
        for (boolean missing : new boolean[]{true, false}) {
            CompoundTag invalid = expected.copy();
            if (missing) { invalid.remove("pulsar_version"); } else { invalid.putInt("pulsar_version", 999); }
            rejects(helper, () -> ExplorationCatalog.decode(invalid), "Missing or unknown pulsar version");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void pulsarDiscoveryAndSavedRoundtripNeverGrantVisits(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = detachedEmptyCatalog(helper);
        UUID id = UUID.randomUUID();
        var pilot = catalog.player(id);
        helper.assertTrue(!pilot.discoveredSystems().contains("p_0"), "Fresh pilots silently charted a pulsar");
        helper.assertTrue(catalog.discover(id, "p_0") == AstraCosmos.DiscoverResult.DISCOVERED
                        && pilot.visitedSystems().equals(List.of("sol")), "Charting a pulsar granted fast travel");
        CompoundTag saved = catalog.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(catalog.discover(id, "p_0") == AstraCosmos.DiscoverResult.ALREADY_KNOWN
                        && catalog.save(new CompoundTag(), server.registryAccess()).equals(saved),
                "Repeated pulsar discovery changed navigation");
        ExplorationCatalog restored = ExplorationCatalog.decode(saved);
        helper.assertTrue(restored.save(new CompoundTag(), server.registryAccess()).equals(saved)
                        && restored.system("p_0").equals(PulsarGenerator.landmark(catalog.galaxySeed(), 0)),
                "Pulsar discovery did not preserve its exact independent descriptor or exploration state");
        CompoundTag invalidLegacy = saved.copy();
        invalidLegacy.putInt("version", 6); invalidLegacy.remove("pulsar_version");
        rejects(helper, () -> ExplorationCatalog.decode(invalidLegacy), "Unversioned legacy pulsar identity");
        rejects(helper, () -> new ExplorationPayload(catalog.galaxySeed(), 0, "sol", SpaceVector.ZERO,
                SpaceVector.ZERO, true, 100, FlightOrientation.IDENTITY, 80, "p_0",
                List.of("sol", "p_0"), List.of("sol"), 1, 1), "Fast travel to unvisited pulsar");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void pulsarCustomDescriptorAndPublicRequestsRoundtripOnWire(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        CosmosSystem descriptor = CelestialSystems.builder("verification:beacon", "Beacon")
                .seed(47).kind(CosmosSystem.Kind.PULSAR)
                .body(CelestialBodies.pulsar("primary", "Neutron Star", 12_000).axialTiltRadians(0.7).build())
                .body(CelestialBodies.planet("rock", "Rock", CelestialBody.Kind.ROCKY, 800_000)
                        .parent("primary").orbit(1e10, 100_000).build()).build();
        helper.assertTrue(CosmosDescriptorCodec.decode(CosmosDescriptorCodec.encode(descriptor)).equals(descriptor),
                "Pulsar NBT changed physical radius, kinds, parent or orientation");
        var catalog = detachedEmptyCatalog(helper);
        helper.assertTrue(catalog.createSystem(descriptor) == AstraCosmos.CreateResult.CREATED
                        && ExplorationCatalog.decode(catalog.save(new CompoundTag(), server.registryAccess()))
                                .system(descriptor.id()).equals(descriptor), "Saved custom pulsar could not be restored");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            var payload = new CustomSystemsPayload(List.of(descriptor));
            CustomSystemsPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() == CosmosDescriptorCodec.snapshotBytes(payload.systems())
                            && CustomSystemsPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                    "Pulsar descriptor wire layout or byte accounting changed");
            for (int galaxy = 0; galaxy < 9; galaxy++) {
                buffer.clear();
                var request = new FlightActionPayload(FlightActionPayload.Action.CHART_ATLAS, "p_" + galaxy);
                FlightActionPayload.CODEC.encode(buffer, request);
                helper.assertTrue(FlightActionPayload.CODEC.decode(buffer).equals(request) && !buffer.isReadable(),
                        "Canonical pulsar atlas request changed on wire");
            }
            for (String invalid : List.of("p_9", "p_00", "p_mod:eden", "mod:p_1", "p_-1")) {
                buffer.clear(); buffer.writeEnum(FlightActionPayload.Action.CHART_ATLAS); buffer.writeUtf(invalid, 64);
                rejects(helper, () -> FlightActionPayload.CODEC.decode(buffer), "Nonpublic pulsar request " + invalid);
            }
            buffer.clear();
            var navigation = new ExplorationPayload(42, 123, "p_0", new SpaceVector(0, 0, -1_000_000),
                    SpaceVector.ZERO, true, 137, FlightOrientation.IDENTITY, 0, "", List.of("sol", "p_0"),
                    List.of("sol", "p_0"), 41, 2);
            ExplorationPayload.CODEC.encode(buffer, navigation);
            helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(navigation) && !buffer.isReadable(),
                    "Pulsar navigation snapshot lost identity, pose, epoch or visits");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static ExplorationCatalog detachedEmptyCatalog(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        // Other GameTests intentionally fill the live catalog to its capacity. Only clear this detached test copy.
        CompoundTag root = ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess());
        root.put("players", new ListTag());
        root.put("custom_systems", new ListTag());
        return ExplorationCatalog.decode(root);
    }

    private static void rejects(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
