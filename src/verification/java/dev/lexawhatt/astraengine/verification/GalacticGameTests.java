package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
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

/** Dedicated host codecs and conservative visit migration; native scenarios exercise actual travel authorization. */
@PrefixGameTestTemplate(false)
public final class GalacticGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void visibleNeighborhoodAndConservativeMigration(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ExplorationCatalog catalog = ExplorationCatalog.decode(ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess()));
        UUID id = UUID.randomUUID();
        var pilot = catalog.player(id);
        helper.assertTrue(pilot.discoveredSystems().size() == 27 && pilot.visitedSystems().equals(List.of("sol")),
                "New pilots must see 27 charted systems but unlock only Sol");
        String distant = "s_9_8_7";
        helper.assertTrue(catalog.discover(id, distant) == AstraCosmos.DiscoverResult.DISCOVERED
                        && pilot.visitedSystems().equals(List.of("sol")), "Chart discovery fabricated a physical visit");
        CompoundTag saved = catalog.save(new CompoundTag(), server.registryAccess());
        CompoundTag entry = saved.getList("players", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                .filter(value -> value.getUUID("uuid").equals(id)).findFirst().orElseThrow().copy();
        entry.putString("system", "s_1_0_0"); entry.remove("visited");
        entry.putDouble("x", 123_456.125); entry.putLong("revision", 876);
        ListTag onlyPilot = new ListTag(); onlyPilot.add(entry);
        CompoundTag legacy = saved.copy(); legacy.putInt("version", 3); legacy.put("players", onlyPilot);
        ExplorationCatalog migrated = ExplorationCatalog.decode(legacy);
        var restored = migrated.player(id);
        helper.assertTrue(migrated.isDirty() && restored.visitedSystems().equals(List.of("sol", "s_1_0_0")),
                "V3 migration granted visits to old chart-only destinations");
        helper.assertTrue(restored.discoveredSystems().equals(pilot.discoveredSystems())
                        && restored.position().x() == 123_456.125 && restored.revision() == 876,
                "V3 migration changed exact chart, pose or revision");
        CompoundTag current = migrated.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(current.getInt("version") == 6
                        && ExplorationCatalog.decode(current).save(new CompoundTag(), server.registryAccess()).equals(current),
                "Current exploration NBT failed exact roundtrip");
        for (List<String> invalid : List.of(List.<String>of(), List.of("sol", "sol"), List.of("sol"),
                List.of("sol", "s_1_0_0", "s_999_999_999"))) {
            CompoundTag changed = current.copy(); changed.getList("players", Tag.TAG_COMPOUND).getCompound(0).put("visited", strings(invalid));
            rejects(helper, () -> ExplorationCatalog.decode(changed), "Malformed v4 visited list " + invalid);
        }
        CompoundTag missing = current.copy(); missing.getList("players", Tag.TAG_COMPOUND).getCompound(0).remove("visited");
        rejects(helper, () -> ExplorationCatalog.decode(missing), "Missing v4 visited list");
        CompoundTag far = current.copy();
        far.getList("players", Tag.TAG_COMPOUND).getCompound(0).putDouble("x", 100_000 * CosmosGenerator.LIGHT_YEAR);
        far.getList("players", Tag.TAG_COMPOUND).getCompound(0).putDouble("speed_mps", FlightDynamics.MAX_SPEED);
        helper.assertTrue(ExplorationCatalog.decode(far).save(new CompoundTag(), server.registryAccess()).equals(far),
                "V4 did not preserve far-galaxy coordinates and continuous speed");
        far.putInt("version", 3);
        rejects(helper, () -> ExplorationCatalog.decode(far), "Legacy v3 accepting expanded v4 position/speed bounds");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void visitedWireAuthorizationAndSpeedBounds(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var known = List.of("sol", "s_1_0_0");
            for (String destination : known) {
                var payload = new ExplorationPayload(1, 20, "sol", new SpaceVector(70_000 * CosmosGenerator.LIGHT_YEAR, 0, 0),
                        SpaceVector.ZERO, true, FlightDynamics.MAX_SPEED, FlightOrientation.IDENTITY, 80, destination,
                        known, known, 9, 2);
                ExplorationPayload.CODEC.encode(buffer, payload);
                helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                        "Visited fast travel or distant same-origin return changed on wire"); buffer.clear();
            }
            rejects(helper, () -> new ExplorationPayload(1, 20, "sol", SpaceVector.ZERO, SpaceVector.ZERO, true,
                    100, FlightOrientation.IDENTITY, 80, "s_1_0_0", known, List.of("sol"), 9, 2),
                    "Unvisited fast-travel destination");
            for (int count : new int[]{0, 257}) {
                writeNavigationPrefix(buffer); buffer.writeVarInt(count);
                rejects(helper, () -> ExplorationPayload.CODEC.decode(buffer), "Out-of-bounds visited wire count " + count);
                buffer.clear();
            }
            writeNavigationPrefix(buffer); buffer.writeVarInt(2); buffer.writeUtf("sol", 64); buffer.writeUtf("s_9_9_9", 64);
            buffer.writeLong(9); buffer.writeLong(2);
            rejects(helper, () -> ExplorationPayload.CODEC.decode(buffer), "Visited wire ID absent from the chart"); buffer.clear();
            for (double speed : new double[]{FlightDynamics.MIN_SPEED, FlightDynamics.LOCAL_MAX_SPEED, FlightDynamics.MAX_SPEED}) {
                FlightSpeedPayload value = new FlightSpeedPayload(speed); FlightSpeedPayload.CODEC.encode(buffer, value);
                helper.assertTrue(FlightSpeedPayload.CODEC.decode(buffer).equals(value) && !buffer.isReadable(),
                        "Continuous speed request changed on wire"); buffer.clear();
            }
            for (double speed : new double[]{0, Double.NaN, Double.POSITIVE_INFINITY, FlightDynamics.MAX_SPEED * 1.01}) {
                buffer.writeDouble(speed);
                rejects(helper, () -> FlightSpeedPayload.CODEC.decode(buffer), "Out-of-bounds speed request " + speed); buffer.clear();
            }
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static void writeNavigationPrefix(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(1); buffer.writeLong(20); buffer.writeUtf("sol", 64);
        for (int index = 0; index < 6; index++) { buffer.writeDouble(0); }
        buffer.writeBoolean(true); buffer.writeDouble(100);
        buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(1);
        buffer.writeVarInt(0); buffer.writeUtf("", 129);
        buffer.writeVarInt(2); buffer.writeUtf("sol", 64); buffer.writeUtf("s_1_0_0", 64);
    }

    private static ListTag strings(List<String> values) {
        ListTag tags = new ListTag(); values.forEach(value -> tags.add(StringTag.valueOf(value))); return tags;
    }

    private static void rejects(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
