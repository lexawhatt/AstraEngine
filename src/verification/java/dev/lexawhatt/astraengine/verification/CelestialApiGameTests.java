package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SatelliteGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.CosmosDescriptorCodec;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Public consumer creation and strict host persistence/wire contracts; excluded from the shipped mod. */
@PrefixGameTestTemplate(false)
public final class CelestialApiGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void parentedDescriptorsAndAdditiveSatelliteMigration(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        CosmosSystem moons = new CosmosSystem("verification:solar_moons", "Solar moons", 7,
                CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, CosmosGenerator.sol().bodies());
        CompoundTag encoded = CosmosDescriptorCodec.encode(moons);
        helper.assertTrue(encoded.getInt("version") == 2 && CosmosDescriptorCodec.decode(encoded).equals(moons),
                "Parent-relative v2 NBT changed a descriptor or moon identity");
        for (String parent : new String[]{"absent", "moon"}) {
            CompoundTag malformed = encoded.copy();
            malformed.getList("bodies", Tag.TAG_COMPOUND).getCompound(9).putString("parent_id", parent);
            rejects(helper, () -> CosmosDescriptorCodec.decode(malformed), "Invalid saved lunar parent " + parent);
        }
        CompoundTag cycle = encoded.copy();
        cycle.getList("bodies", Tag.TAG_COMPOUND).getCompound(3).putString("parent_id", "moon");
        rejects(helper, () -> CosmosDescriptorCodec.decode(cycle), "Cyclic saved lunar parents");
        CompoundTag missing = encoded.copy(); missing.getList("bodies", Tag.TAG_COMPOUND).getCompound(9).remove("parent_id");
        rejects(helper, () -> CosmosDescriptorCodec.decode(missing), "Missing required v2 parent field");
        CosmosSystem original = CelestialApiFixtures.ringSystem();
        CompoundTag legacy = CosmosDescriptorCodec.encode(original); legacy.putInt("version", 1);
        legacy.getList("bodies", Tag.TAG_COMPOUND).forEach(tag -> ((CompoundTag) tag).remove("parent_id"));
        helper.assertTrue(CosmosDescriptorCodec.decode(legacy).equals(original), "Legacy custom descriptor changed during parent migration");

        // This synthetic legacy catalog replaces its definitions below; unrelated test players may legitimately
        // reference other custom systems in the shared server catalog, so neither belongs to this fixture.
        CompoundTag cleanCatalog = ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess());
        cleanCatalog.put("custom_systems", new ListTag()); cleanCatalog.put("players", new ListTag());
        var detached = ExplorationCatalog.decode(cleanCatalog);
        UUID id = UUID.randomUUID(); detached.player(id);
        CompoundTag oldCatalog = detached.save(new CompoundTag(), server.registryAccess());
        oldCatalog.putInt("version", 5); oldCatalog.remove("satellite_version");
        ListTag definitions = new ListTag(); definitions.add(legacy); oldCatalog.put("custom_systems", definitions);
        ExplorationCatalog migrated = ExplorationCatalog.decode(oldCatalog);
        CompoundTag current = migrated.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(migrated.isDirty() && current.getInt("version") == 7
                        && current.getInt("satellite_version") == SatelliteGenerator.VERSION,
                "Satellite generation was not pinned independently by v6 migration");
        helper.assertTrue(current.get("players").equals(oldCatalog.get("players"))
                        && current.get("seed").equals(oldCatalog.get("seed"))
                        && current.get("clock_ticks").equals(oldCatalog.get("clock_ticks"))
                        && migrated.system(original.id()).equals(original),
                "Satellite migration changed saved navigation, time, private visits or authored content");
        helper.assertTrue(migrated.system("sol").bodies().size() == 30,
                "A migrated Sol catalog did not receive the additive major moons");
        for (boolean unknown : new boolean[]{false, true}) {
            CompoundTag broken = current.copy();
            if (unknown) { broken.putInt("satellite_version", 999); } else { broken.remove("satellite_version"); }
            rejects(helper, () -> ExplorationCatalog.decode(broken), "Missing or unsupported satellite version");
        }
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            CustomSystemsPayload payload = new CustomSystemsPayload(List.of(moons));
            CustomSystemsPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() == CosmosDescriptorCodec.snapshotBytes(payload.systems()),
                    "Descriptor byte accounting disagrees with the wire encoder");
            helper.assertTrue(CustomSystemsPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                    "Moon parents did not survive wire synchronization");
            buffer.clear(); buffer.writeVarInt(1);
            rejects(helper, () -> CosmosDescriptorCodec.read(buffer), "Old descriptor wire version");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void descriptorByteBudgetPreventsOversizeCustomSynchronization(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.decode(ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess()));
        CompoundTag clean = catalog.save(new CompoundTag(), server.registryAccess());
        clean.put("custom_systems", new ListTag()); clean.put("players", new ListTag());
        catalog = ExplorationCatalog.decode(clean);
        ArrayList<CosmosSystem> accepted = new ArrayList<>();
        ArrayList<CelestialBody> bodies = new ArrayList<>();
        String name = "\u65e5".repeat(96);
        String primary = "a".repeat(64);
        bodies.add(CelestialBodies.star(primary, name, 1000).build());
        for (int index = 1; index < CelestialSystems.MAX_BODIES; index++) {
            bodies.add(CelestialBodies.planet(String.format(java.util.Locale.ROOT, "b%063d", index), name,
                    CelestialBody.Kind.ROCKY, 1000).parent(primary).orbit(1e7 + index * 1e6, 1e7).build());
        }
        for (int index = 0; index < 64; index++) {
            CosmosSystem system = new CosmosSystem("verification:bytes_" + index, name, index,
                    CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies);
            ArrayList<CosmosSystem> candidate = new ArrayList<>(accepted); candidate.add(system);
            boolean fits = CosmosDescriptorCodec.snapshotBytes(candidate) <= CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES;
            AstraCosmos.CreateResult result = catalog.createSystem(system);
            helper.assertTrue(result == (fits ? AstraCosmos.CreateResult.CREATED : AstraCosmos.CreateResult.LIMIT_REACHED),
                    "Catalog byte budget disagrees with creation capacity");
            if (!fits) {
                rejects(helper, () -> new CustomSystemsPayload(candidate), "Oversize descriptor snapshot");
                break;
            }
            accepted.add(system);
        }
        helper.assertTrue(!accepted.isEmpty() && accepted.size() < 64, "Maximum UTF-8 fixture did not exercise byte capacity");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            CustomSystemsPayload payload = new CustomSystemsPayload(accepted);
            CustomSystemsPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() == CosmosDescriptorCodec.snapshotBytes(accepted)
                            && buffer.readableBytes() <= CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES,
                    "Maximum-length UTF-8 descriptors exceeded their exact byte budget");
            helper.assertTrue(CustomSystemsPayload.CODEC.decode(buffer).equals(payload), "Near-capacity descriptor payload changed fields");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void consumerCreationCapacityAndPrivateDiscovery(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.get(server);
        CosmosSystem ring = CelestialApiFixtures.ringSystem();
        CosmosSystem hole = CelestialApiFixtures.holeSystem();
        helper.assertTrue(AstraCosmos.create(server, ring) == AstraCosmos.CreateResult.CREATED, "Consumer ring system was not created");
        helper.assertTrue(AstraCosmos.create(server, hole) == AstraCosmos.CreateResult.CREATED, "Consumer black-hole system was not created");
        helper.assertTrue(AstraCosmos.create(server, ring) == AstraCosmos.CreateResult.ALREADY_EXISTS, "Same descriptor did not replay");
        helper.assertTrue(AstraCosmos.create(server, CelestialApiFixtures.simple(ring.id(), "Conflict")) == AstraCosmos.CreateResult.CONFLICT,
                "Same identity overwrote an existing descriptor");
        helper.assertTrue(AstraCosmos.find(server, ring.id()).orElseThrow().equals(ring), "Conflict mutated saved descriptor");
        helper.assertTrue(AstraCosmos.find(server, "verification:absent").isEmpty(), "Missing custom system used procedural fallback");
        helper.assertTrue(AstraCosmos.find(server, "sol").orElseThrow().equals(CosmosGenerator.sol()), "Custom API changed canonical Sol");
        rejects(helper, () -> AstraCosmos.create(server, CosmosGenerator.sol()), "Reserved Sol creation");
        rejects(helper, () -> AstraCosmos.create(server, null), "Null descriptor creation");
        rejects(helper, () -> AstraCosmos.find(server, "invalid namespace:body"), "Malformed lookup identity");
        for (int index = catalog.customSystems().size(); index < ExplorationCatalog.MAX_CUSTOM_SYSTEMS; index++) {
            helper.assertTrue(AstraCosmos.create(server, CelestialApiFixtures.simple("verification:capacity_" + index, "Capacity " + index))
                    == AstraCosmos.CreateResult.CREATED, "Custom capacity stopped below its documented bound");
        }
        helper.assertTrue(catalog.customSystems().size() == 64, "Custom catalog exceeds or misses its bound");
        helper.assertTrue(AstraCosmos.create(server, CelestialApiFixtures.simple("verification:overflow", "Overflow"))
                == AstraCosmos.CreateResult.LIMIT_REACHED, "Custom capacity accepted an extra system");
        helper.assertTrue(AstraCosmos.create(server, ring) == AstraCosmos.CreateResult.ALREADY_EXISTS, "Full catalog rejected an exact replay");
        helper.assertTrue(AstraCosmos.find(server, "verification:overflow").isEmpty(), "Capacity refusal partially installed a descriptor");

        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        helper.assertTrue(catalog.discover(owner, ring.id()) == AstraCosmos.DiscoverResult.DISCOVERED, "Private discovery did not grant custom content");
        helper.assertTrue(catalog.discover(owner, ring.id()) == AstraCosmos.DiscoverResult.ALREADY_KNOWN, "Discovery replay consumed capacity");
        helper.assertTrue(catalog.discover(owner, "verification:absent") == AstraCosmos.DiscoverResult.UNKNOWN_SYSTEM, "Missing system was discovered");
        helper.assertTrue(catalog.player(other).discoveredSystems().size() == 27
                        && !catalog.player(other).discoveredSystems().contains(ring.id()), "Private discovery leaked to another pilot");
        helper.assertTrue(catalog.player(owner).visitedSystems().equals(List.of("sol")),
                "Public discovery granted an unearned fast-travel visit");
        for (int index = 1000; catalog.player(owner).discoveredSystems().size() < 256; index++) {
            helper.assertTrue(catalog.discover(owner, "s_" + index + "_0_0") == AstraCosmos.DiscoverResult.DISCOVERED,
                    "Private discovery stopped below its documented capacity");
        }
        helper.assertTrue(catalog.player(owner).discoveredSystems().size() == 256, "Private discovery count changed");
        helper.assertTrue(catalog.discover(owner, hole.id()) == AstraCosmos.DiscoverResult.LIMIT_REACHED, "Private discovery exceeded 256");
        helper.assertTrue(catalog.discover(owner, ring.id()) == AstraCosmos.DiscoverResult.ALREADY_KNOWN, "Full private list rejected a replay");
        CompoundTag saved = catalog.save(new CompoundTag(), server.registryAccess());
        ExplorationCatalog restored = ExplorationCatalog.decode(saved);
        helper.assertTrue(restored.customSystems().equals(catalog.customSystems())
                        && restored.player(owner).discoveredSystems().equals(catalog.player(owner).discoveredSystems()),
                "Full catalog/discovery NBT roundtrip changed exact values");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            CustomSystemsPayload payload = new CustomSystemsPayload(catalog.customSystems());
            CustomSystemsPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(CustomSystemsPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                    "Maximum system-count wire snapshot changed descriptors or framing");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void descriptorAndCustomPayloadValidation(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        CosmosSystem ring = CelestialApiFixtures.ringSystem(), hole = CelestialApiFixtures.holeSystem();
        CompoundTag encoded = CosmosDescriptorCodec.encode(ring);
        helper.assertTrue(CosmosDescriptorCodec.decode(encoded).equals(ring), "Descriptor NBT changed double, float or orbit fields");
        helper.assertTrue(CosmosDescriptorCodec.decode(CosmosDescriptorCodec.encode(hole)).equals(hole), "Black-hole NBT roundtrip changed descriptor");
        for (String required : new String[]{"version", "id", "name", "kind", "seed", "galaxy_x", "bodies"}) {
            CompoundTag missing = encoded.copy(); missing.remove(required);
            rejects(helper, () -> CosmosDescriptorCodec.decode(missing), "Missing descriptor field " + required);
        }
        CompoundTag invalidVersion = encoded.copy(); invalidVersion.putInt("version", 999);
        rejects(helper, () -> CosmosDescriptorCodec.decode(invalidVersion), "Unknown descriptor format");
        CompoundTag nan = encoded.copy(); nan.getList("bodies", Tag.TAG_COMPOUND).getCompound(1).putDouble("radius_meters", Double.NaN);
        rejects(helper, () -> CosmosDescriptorCodec.decode(nan), "Non-finite body radius");
        CompoundTag wrongType = encoded.copy(); wrongType.getList("bodies", Tag.TAG_COMPOUND).getCompound(1).putDouble("atmosphere", 0.35);
        rejects(helper, () -> CosmosDescriptorCodec.decode(wrongType), "Wrong numeric NBT tag type");
        CompoundTag duplicate = encoded.copy(); duplicate.getList("bodies", Tag.TAG_COMPOUND).add(encoded.getList("bodies", Tag.TAG_COMPOUND).getCompound(0).copy());
        rejects(helper, () -> CosmosDescriptorCodec.decode(duplicate), "Duplicate body identity");
        CompoundTag excess = encoded.copy(); ListTag excessBodies = new ListTag();
        for (int index = 0; index < 65; index++) {
            CompoundTag body = encoded.getList("bodies", Tag.TAG_COMPOUND).getCompound(0).copy(); body.putString("id", "body_" + index); excessBodies.add(body);
        }
        excess.put("bodies", excessBodies);
        rejects(helper, () -> CosmosDescriptorCodec.decode(excess), "Sixty-five-body descriptor");
        CompoundTag reserved = encoded.copy(); reserved.putString("id", "sol");
        rejects(helper, () -> CosmosDescriptorCodec.decode(reserved), "Reserved built-in descriptor identity");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            var payload = new CustomSystemsPayload(List.of(ring, hole));
            CustomSystemsPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 16_384, "Small consumer payload exceeded its expected bounded budget");
            helper.assertTrue(CustomSystemsPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(), "Custom payload changed values or framing");
            buffer.clear(); CustomSystemsPayload.CODEC.encode(buffer, new CustomSystemsPayload(List.of()));
            helper.assertTrue(CustomSystemsPayload.CODEC.decode(buffer).systems().isEmpty(), "Empty connection-cache snapshot was rejected");
            for (int count : new int[]{-1, 65}) {
                buffer.clear(); buffer.writeVarInt(count);
                rejects(helper, () -> CustomSystemsPayload.CODEC.decode(buffer), "Out-of-bounds custom packet count " + count);
            }
            buffer.clear(); buffer.writeVarInt(2); CosmosDescriptorCodec.write(buffer, ring); CosmosDescriptorCodec.write(buffer, ring);
            rejects(helper, () -> CustomSystemsPayload.CODEC.decode(buffer), "Duplicate wire descriptor identity");
            for (int count : new int[]{0, 65}) {
                buffer.clear(); writeDescriptorHeader(buffer, count);
                rejects(helper, () -> CosmosDescriptorCodec.read(buffer), "Out-of-bounds wire body count " + count);
            }
            buffer.clear(); writeDescriptorHeader(buffer, 1);
            buffer.writeUtf("primary", 64); buffer.writeUtf("Malformed primary", 96); buffer.writeVarInt(0);
            buffer.writeDouble(Double.NaN);
            for (int index = 0; index < 5; index++) { buffer.writeDouble(0); }
            for (int index = 0; index < 3; index++) { buffer.writeDouble(1); }
            for (int index = 0; index < 3; index++) { buffer.writeFloat(0); }
            buffer.writeDouble(0);
            buffer.writeUtf("", 64);
            rejects(helper, () -> CosmosDescriptorCodec.read(buffer), "Non-finite wire body radius");
            buffer.clear();
            var action = new FlightActionPayload(FlightActionPayload.Action.JUMP_SYSTEM, ring.id());
            FlightActionPayload.CODEC.encode(buffer, action);
            helper.assertTrue(FlightActionPayload.CODEC.decode(buffer).equals(action), "Custom navigation action lost namespaced ID");
            buffer.clear();
            var pilot = ExplorationCatalog.get(server).player(UUID.randomUUID());
            var navigation = new ExplorationPayload(1, 100, ring.id(), pilot.position(), pilot.velocity(), true, 100,
                    pilot.orientation(), 240, ring.id() + "/ringworld", List.of("sol", ring.id()), 12, 9);
            ExplorationPayload.CODEC.encode(buffer, navigation);
            helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(navigation), "Namespaced local approach changed on wire");
            String longestSystem = "verification:" + "a".repeat(51);
            String longestBody = "b".repeat(64);
            helper.assertTrue(longestSystem.length() == 64 && (longestSystem + "/" + longestBody).length() == 129,
                    "Maximum namespaced approach fixture has incorrect lengths");
            for (FlightActionPayload maximum : List.of(
                    new FlightActionPayload(FlightActionPayload.Action.JUMP_SYSTEM, longestSystem),
                    new FlightActionPayload(FlightActionPayload.Action.APPROACH_BODY, longestBody))) {
                buffer.clear(); FlightActionPayload.CODEC.encode(buffer, maximum);
                helper.assertTrue(FlightActionPayload.CODEC.decode(buffer).equals(maximum) && !buffer.isReadable(),
                        "Maximum-length navigation action changed on wire");
            }
            buffer.clear();
            var longestApproach = new ExplorationPayload(1, 100, longestSystem, pilot.position(), pilot.velocity(), true, 100,
                    pilot.orientation(), 240, longestSystem + "/" + longestBody, List.of("sol", longestSystem), 12, 9);
            ExplorationPayload.CODEC.encode(buffer, longestApproach);
            helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(longestApproach) && !buffer.isReadable(),
                    "129-character local approach target was truncated or changed on wire");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void customCatalogStrictnessAndVersionTwoMigration(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.get(server);
        UUID pilotId = UUID.randomUUID(); catalog.player(pilotId);
        CompoundTag saved = catalog.save(new CompoundTag(), server.registryAccess());
        CompoundTag pilot = saved.getList("players", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                .filter(value -> value.getUUID("uuid").equals(pilotId)).findFirst().orElseThrow().copy();
        FlightOrientation orientation = FlightOrientation.fromAngles(47, 126, -33);
        pilot.putDouble("qx", orientation.x()); pilot.putDouble("qy", orientation.y());
        pilot.putDouble("qz", orientation.z()); pilot.putDouble("qw", orientation.w());
        pilot.putDouble("speed_mps", 137.25); pilot.putLong("revision", 712);
        pilot.putString("system", "s_-1_2_0");
        ListTag discoveries = new ListTag(); discoveries.add(StringTag.valueOf("sol")); discoveries.add(StringTag.valueOf("s_-1_2_0"));
        pilot.put("discovered", discoveries); pilot.remove("visited");
        ListTag players = new ListTag(); players.add(pilot);
        CompoundTag legacy = saved.copy(); legacy.putInt("version", 2); legacy.remove("custom_systems");
        legacy.put("players", players); legacy.putLong("clock_ticks", 81234); legacy.putBoolean("landing", true);
        ExplorationCatalog migrated = ExplorationCatalog.decode(legacy);
        CompoundTag current = migrated.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(migrated.isDirty() && current.getInt("version") == 7 && current.getList("custom_systems", Tag.TAG_COMPOUND).isEmpty(),
                "Legacy v2 did not migrate to v7 with an empty custom catalog");
        CompoundTag expectedPilot = pilot.copy(); expectedPilot.put("visited", discoveries.copy());
        ListTag expectedPlayers = new ListTag(); expectedPlayers.add(expectedPilot);
        helper.assertTrue(current.getList("players", Tag.TAG_COMPOUND).equals(expectedPlayers)
                        && current.getLong("seed") == legacy.getLong("seed") && current.getLong("clock_ticks") == 81234 && current.getBoolean("landing"),
                "V2 migration changed exact pilot pose, identity, discovery, time or landing ownership");

        CosmosSystem ring = CelestialApiFixtures.ringSystem();
        ListTag custom = new ListTag(); custom.add(CosmosDescriptorCodec.encode(ring));
        CompoundTag populated = current.copy(); populated.put("custom_systems", custom);
        helper.assertTrue(ExplorationCatalog.decode(populated).system(ring.id()).equals(ring), "V5 did not restore the exact custom descriptor");
        CompoundTag missing = populated.copy(); missing.remove("custom_systems");
        rejects(helper, () -> ExplorationCatalog.decode(missing), "Missing mandatory v5 custom catalog");
        CompoundTag duplicates = populated.copy(); duplicates.getList("custom_systems", Tag.TAG_COMPOUND).add(CosmosDescriptorCodec.encode(ring));
        rejects(helper, () -> ExplorationCatalog.decode(duplicates), "Duplicate custom catalog identity");
        CompoundTag malformedList = populated.copy(); ListTag strings = new ListTag(); strings.add(StringTag.valueOf("invalid"));
        malformedList.put("custom_systems", strings);
        rejects(helper, () -> ExplorationCatalog.decode(malformedList), "Custom catalog list with wrong element type");
        CompoundTag missingReference = populated.copy(); CompoundTag changedPilot = missingReference.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        changedPilot.putString("system", "verification:missing");
        ListTag unknown = new ListTag(); unknown.add(StringTag.valueOf("sol")); unknown.add(StringTag.valueOf("verification:missing"));
        changedPilot.put("discovered", unknown);
        rejects(helper, () -> ExplorationCatalog.decode(missingReference), "Unresolved persisted custom discovery/current-system reference");
        CompoundTag legacyWithCustom = populated.copy(); legacyWithCustom.putInt("version", 2);
        rejects(helper, () -> ExplorationCatalog.decode(legacyWithCustom), "Legacy version silently dropping custom descriptors");
        helper.succeed();
    }

    private static void writeDescriptorHeader(RegistryFriendlyByteBuf buffer, int bodies) {
        buffer.writeVarInt(CosmosDescriptorCodec.VERSION); buffer.writeUtf("verification:wire", 64); buffer.writeUtf("Wire descriptor", 96);
        buffer.writeLong(1); buffer.writeVarInt(0);
        buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeVarInt(bodies);
    }

    private static void rejects(GameTestHelper helper, Runnable decode, String label) {
        boolean rejected = false;
        try { decode.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
