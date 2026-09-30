package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
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
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Strict atlas host persistence and request boundaries; native fixtures establish real first-visit authority. */
@PrefixGameTestTemplate(false)
public final class AtlasGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void atlasVersionMigrationAndStrictReferences(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.decode(ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess()));
        UUID id = UUID.randomUUID(); catalog.player(id);
        CompoundTag current = catalog.save(new CompoundTag(), server.registryAccess());
        CompoundTag pilot = current.getList("players", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                .filter(value -> value.getUUID("uuid").equals(id)).findFirst().orElseThrow().copy();
        ListTag onlyPilot = new ListTag(); onlyPilot.add(pilot); current.put("players", onlyPilot);
        helper.assertTrue(current.getInt("version") == 6 && current.getInt("universe_version") == UniverseGenerator.VERSION,
                "Atlas generation ownership is missing from current NBT");
        CompoundTag legacy = current.copy(); legacy.putInt("version", 4); legacy.remove("universe_version");
        ExplorationCatalog migrated = ExplorationCatalog.decode(legacy);
        helper.assertTrue(migrated.isDirty() && migrated.save(new CompoundTag(), server.registryAccess()).equals(current),
                "V4 migration changed existing exact chart, visits, descriptors or navigation");
        var customU = CelestialApiFixtures.simple("u_mod:eden", "Legacy private Eden");
        var customV = CelestialApiFixtures.simple("v_pack:world", "Legacy private World");
        CompoundTag customLegacy = legacy.copy();
        ListTag customDefinitions = new ListTag();
        customDefinitions.add(CosmosDescriptorCodec.encode(customU));
        customDefinitions.add(CosmosDescriptorCodec.encode(customV));
        customLegacy.put("custom_systems", customDefinitions);
        var customPilot = customLegacy.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        customPilot.putString("system", customU.id());
        customPilot.put("discovered", strings(List.of("sol", customU.id(), customV.id())));
        customPilot.put("visited", strings(List.of("sol", customU.id(), customV.id())));
        CompoundTag expectedCustom = customLegacy.copy(); expectedCustom.putInt("version", 6);
        expectedCustom.putInt("universe_version", UniverseGenerator.VERSION);
        ExplorationCatalog customMigrated = ExplorationCatalog.decode(customLegacy);
        helper.assertTrue(customMigrated.isDirty()
                        && customMigrated.save(new CompoundTag(), server.registryAccess()).equals(expectedCustom)
                        && customMigrated.system(customU.id()).equals(customU)
                        && customMigrated.system(customV.id()).equals(customV),
                "V4 migration confused private u_/v_ namespaces with universe IDs or changed their definitions/visits");
        for (boolean missing : new boolean[]{false, true}) {
            CompoundTag malformed = current.copy();
            if (missing) { malformed.remove("universe_version"); } else { malformed.putInt("universe_version", 999); }
            rejects(helper, () -> ExplorationCatalog.decode(malformed), "Missing or unknown universe format");
        }
        var detached = ExplorationCatalog.decode(current);
        helper.assertTrue(detached.discover(id, "u_5_0") == AstraCosmos.DiscoverResult.DISCOVERED
                        && detached.player(id).visitedSystems().equals(List.of("sol")),
                "Atlas discovery granted a visit before physical entry");
        String empty = "v_1_100000_0_0";
        helper.assertTrue(CosmosIds.isKnownId(empty) && UniverseGenerator.find(detached.galaxySeed(), empty).isEmpty(),
                "Empty sector fixture is not a supported empty universe address");
        CompoundTag before = detached.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(detached.discover(id, empty) == AstraCosmos.DiscoverResult.UNKNOWN_SYSTEM
                        && before.equals(detached.save(new CompoundTag(), server.registryAccess())),
                "Charting empty space mutated private exploration");
        CompoundTag entered = before.copy(); var entry = entered.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        entry.putString("system", "u_5_0"); entry.put("visited", strings(List.of("sol", "u_5_0")));
        helper.assertTrue(ExplorationCatalog.decode(entered).save(new CompoundTag(), server.registryAccess()).equals(entered),
                "Atlas current-system identity or visits changed during exact NBT roundtrip");
        CompoundTag legacyAtlas = entered.copy(); legacyAtlas.putInt("version", 4); legacyAtlas.remove("universe_version");
        rejects(helper, () -> ExplorationCatalog.decode(legacyAtlas), "Legacy save containing unversioned atlas identities");
        CompoundTag unresolved = entered.copy(); var broken = unresolved.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        broken.putString("system", empty); broken.put("discovered", strings(List.of("sol", empty))); broken.put("visited", strings(List.of("sol", empty)));
        rejects(helper, () -> ExplorationCatalog.decode(unresolved), "Persisted current system resolving to empty space");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void atlasActionWireAndVisitAuthority(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            for (String id : List.of("u_0_0", "u_5_0", "u_8_6")) {
                var request = new FlightActionPayload(FlightActionPayload.Action.CHART_ATLAS, id);
                FlightActionPayload.CODEC.encode(buffer, request);
                helper.assertTrue(FlightActionPayload.CODEC.decode(buffer).equals(request) && !buffer.isReadable(),
                        "Public atlas request changed on wire"); buffer.clear();
            }
            for (String invalid : List.of("sol", "s_1_0_0", "verification:hidden", "u_9_0", "u_0_7", "u_00_0", "v_1_0_0_0")) {
                buffer.writeEnum(FlightActionPayload.Action.CHART_ATLAS); buffer.writeUtf(invalid, 64);
                rejects(helper, () -> FlightActionPayload.CODEC.decode(buffer), "Atlas request for non-public landmark " + invalid);
                buffer.clear();
            }
            for (String id : List.of("u_5_0", "v_1_0_0_0")) {
                helper.assertTrue(CosmosIds.isKnownId(id), "Canonical universe address was rejected");
                var payload = new ExplorationPayload(7, 20, id, SpaceVector.ZERO, SpaceVector.ZERO, true, 100,
                        FlightOrientation.IDENTITY, 0, "", List.of("sol", id), List.of("sol", id), 15, 2);
                ExplorationPayload.CODEC.encode(buffer, payload);
                helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                        "Universe navigation snapshot changed identity or private visits"); buffer.clear();
            }
            rejects(helper, () -> new ExplorationPayload(7, 20, "sol", SpaceVector.ZERO, SpaceVector.ZERO, true, 100,
                    FlightOrientation.IDENTITY, 80, "u_5_0", List.of("sol", "u_5_0"), List.of("sol"), 15, 2),
                    "Unvisited atlas destination on fast-travel wire");
            for (String invalid : List.of("u_9_0", "u_0_7", "u_00_0", "v_1_01_0_0", "v_9_0_0_0")) {
                helper.assertTrue(!CosmosIds.isKnownId(invalid), "Noncanonical universe identity was accepted: " + invalid);
            }
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static ListTag strings(List<String> values) {
        ListTag result = new ListTag(); values.forEach(value -> result.add(StringTag.valueOf(value))); return result;
    }
    private static void rejects(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
