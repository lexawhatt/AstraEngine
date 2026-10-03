package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.AstraSystems;
import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.network.SystemPayload;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.SolarState;
import dev.lexawhatt.astraengine.server.SystemCatalog;
import dev.lexawhatt.astraengine.server.SystemWorlds;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises Minecraft NBT/network integration and the actual dedicated-server lifecycle. */
@PrefixGameTestTemplate(false)
public final class EngineGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void calendarPayloadRoundTrip(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var rotation = FlightOrientation.fromAngles(177, -134, 12);
        var catalog = ExplorationCatalog.get(server);
        var pilot = catalog.player(UUID.randomUUID());
        var navigation = new ExplorationPayload(catalog.galaxySeed(), 170, "sol", pilot.position(),
                pilot.velocity(), true, 137.25, pilot.orientation(), 0, "", pilot.discoveredSystems(),
                pilot.visitedSystems(), pilot.revision(), 1, -23000.75, rotation, 17);
        var surface = new dev.lexawhatt.astraengine.network.SurfacePayload("moon", 170,
                dev.lexawhatt.astraengine.network.SurfacePayload.Phase.SURFACE, 0, -23000.75, rotation, 17);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            ExplorationPayload.CODEC.encode(buffer, navigation);
            helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(navigation), "Calendar navigation changed on wire");
            helper.assertTrue(!buffer.isReadable(), "Calendar navigation left unread bytes");
            buffer.clear();
            dev.lexawhatt.astraengine.network.SurfacePayload.CODEC.encode(buffer, surface);
            helper.assertTrue(dev.lexawhatt.astraengine.network.SurfacePayload.CODEC.decode(buffer).equals(surface),
                    "Calendar ground context changed on wire");
            helper.assertTrue(!buffer.isReadable(), "Calendar ground context left unread bytes");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void catalogAndPayloadRoundTrip(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = SystemCatalog.get(server);
        var operation = UUID.randomUUID();
        AstraSystems.extract(server, "alpha", "primary", operation, 10);
        var snapshot = AstraSystems.snapshot(server, "alpha");
        CompoundTag encoded = catalog.save(new CompoundTag(), server.registryAccess());
        var restored = SystemCatalog.decode(encoded);
        helper.assertTrue(restored.system("alpha").snapshot().equals(snapshot), "NBT changed the snapshot");
        helper.assertTrue(restored.system("alpha").extract(operation, 10).status() == ExtractionResult.Status.REPLAY,
                "NBT lost replay protection");
        CompoundTag missingTime = encoded.copy();
        missingTime.getList("systems", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("active_ticks");
        boolean missingTimeRejected = false;
        try { SystemCatalog.decode(missingTime); } catch (IllegalArgumentException expected) { missingTimeRejected = true; }
        helper.assertTrue(missingTimeRejected, "Missing saved time was silently reset");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            var payload = new SystemPayload(snapshot, SystemWorlds.dimension("alpha").location(), 0);
            SystemPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 256, "Snapshot exceeded the intended network budget");
            helper.assertTrue(SystemPayload.CODEC.decode(buffer).equals(payload), "Wire codec changed the snapshot");
            helper.assertTrue(!buffer.isReadable(), "Wire codec left unread bytes");
        } finally {
            buffer.release();
        }
        encoded.putInt("version", 999);
        boolean rejected = false;
        try { SystemCatalog.decode(encoded); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unknown save format was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void explorationPersistenceAndNetwork(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.get(server);
        UUID pilotId = UUID.randomUUID();
        var pilot = catalog.player(pilotId);
        CompoundTag encoded = catalog.save(new CompoundTag(), server.registryAccess());
        var restored = ExplorationCatalog.decode(encoded);
        var loaded = restored.player(pilotId);
        helper.assertTrue(loaded.systemId().equals("sol") && loaded.discoveredSystems().equals(pilot.discoveredSystems()),
                "Exploration save changed private discoveries");
        helper.assertTrue(loaded.position().equals(pilot.position()) && loaded.velocity().equals(pilot.velocity())
                && loaded.orientation().equals(pilot.orientation())
                && loaded.speedMetersPerSecond() == pilot.speedMetersPerSecond()
                && restored.galaxySeed() == catalog.galaxySeed(), "Exploration save changed navigation or seed");
        long clock = restored.clockTicks();
        restored.tick(false);
        helper.assertTrue(restored.clockTicks() == clock, "Unoccupied exploration clock advanced");
        restored.tick(true);
        helper.assertTrue(restored.clockTicks() == clock + 1, "Occupied exploration clock did not advance");
        var payload = new ExplorationPayload(restored.galaxySeed(), restored.clockTicks(), loaded.systemId(),
                loaded.position(), loaded.velocity(), true, 137.25, FlightOrientation.fromAngles(90, -135, 37),
                80, "sol/earth", loaded.discoveredSystems(), loaded.revision(), 7);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            ExplorationPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 1024, "Initial exploration snapshot exceeded its bounded budget");
            helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(payload), "Exploration wire roundtrip changed state");
            helper.assertTrue(!buffer.isReadable(), "Exploration wire codec left unread bytes");
            buffer.clear();
            var controls = new FlightControlPayload(1, -1, 0.5f, FlightOrientation.fromAngles(90, -135, 37), false, 42, 7);
            FlightControlPayload.CODEC.encode(buffer, controls);
            helper.assertTrue(FlightControlPayload.CODEC.decode(buffer).equals(controls), "Flight controls changed on wire");
            buffer.clear();
            var localControls = new FlightControlPayload(1, 0, 0, FlightOrientation.fromAngles(35, -90, 37),
                    false, 43, 7, true);
            FlightControlPayload.CODEC.encode(buffer, localControls);
            helper.assertTrue(FlightControlPayload.CODEC.decode(buffer).equals(localControls) && !buffer.isReadable(),
                    "Body-fixed ground controls lost their explicit coordinate space");
            buffer.clear();
            var action = new FlightActionPayload(FlightActionPayload.Action.APPROACH_BODY, "earth");
            FlightActionPayload.CODEC.encode(buffer, action);
            helper.assertTrue(FlightActionPayload.CODEC.decode(buffer).equals(action), "Flight action changed on wire");
            buffer.clear();
            buffer.writeFloat(Float.NaN); buffer.writeFloat(0); buffer.writeFloat(0);
            buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(1);
            buffer.writeBoolean(false); buffer.writeLong(1); buffer.writeLong(7); buffer.writeBoolean(false);
            boolean rejectedInput = false;
            try { FlightControlPayload.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejectedInput = true; }
            helper.assertTrue(rejectedInput, "Non-finite network control was accepted");
            buffer.clear(); FlightControlPayload.CODEC.encode(buffer, controls);
            buffer.setDouble(12, 2);
            boolean rejectedQuaternion = false;
            try { FlightControlPayload.CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejectedQuaternion = true; }
            helper.assertTrue(rejectedQuaternion, "Malformed network orientation was normalized or accepted");
            boolean rejectedEpoch = false;
            try { new FlightControlPayload(0, 0, 0, 0, 0, false, 1, -1); }
            catch (IllegalArgumentException expected) { rejectedEpoch = true; }
            helper.assertTrue(rejectedEpoch, "Negative navigation epoch was accepted");
            buffer.clear();
            buffer.writeLong(restored.galaxySeed()); buffer.writeLong(0); buffer.writeUtf("sol", 64);
            for (int component = 0; component < 6; component++) { buffer.writeDouble(0); }
            buffer.writeBoolean(false); buffer.writeDouble(100);
            buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(1);
            buffer.writeVarInt(0); buffer.writeUtf("", 128); buffer.writeVarInt(257);
            boolean rejectedCount = false;
            try { ExplorationPayload.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejectedCount = true; }
            helper.assertTrue(rejectedCount, "Oversized discovery list was read past its count guard");
        } finally {
            buffer.release();
        }
        CompoundTag missingClock = encoded.copy();
        missingClock.remove("clock_ticks");
        boolean missingRejected = false;
        try { ExplorationCatalog.decode(missingClock); } catch (IllegalArgumentException expected) { missingRejected = true; }
        helper.assertTrue(missingRejected, "Missing exploration time was silently reset");
        CompoundTag unknownVersion = encoded.copy();
        unknownVersion.putInt("generator_version", 999);
        boolean versionRejected = false;
        try { ExplorationCatalog.decode(unknownVersion); } catch (IllegalArgumentException expected) { versionRejected = true; }
        helper.assertTrue(versionRejected, "Unknown generator version was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void approachTransitionNetworkBounds(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            for (int ticks : new int[]{81, ExplorationPayload.MAX_TRANSITION_TICKS}) {
                writeTransition(buffer, true, ticks, "sol/earth", 51);
                ExplorationPayload decoded = ExplorationPayload.CODEC.decode(buffer);
                helper.assertTrue(decoded.approaching() && !decoded.interstellarJump()
                                && decoded.approachBodyId().equals("earth") && decoded.jumpTicks() == ticks,
                        "Extended local approach lost its bounded transition semantics on decode");
                helper.assertTrue(decoded.navigationEpoch() == 51 && !buffer.isReadable(), "Approach epoch or codec framing changed");
                buffer.clear();
                ExplorationPayload.CODEC.encode(buffer, decoded);
                helper.assertTrue(ExplorationPayload.CODEC.decode(buffer).equals(decoded), "Approach wire roundtrip changed state");
                buffer.clear();
            }
            writeTransition(buffer, true, 80, "s_1_0_0", 51);
            ExplorationPayload interstellar = ExplorationPayload.CODEC.decode(buffer);
            helper.assertTrue(interstellar.interstellarJump() && !interstellar.approaching()
                            && interstellar.approachBodyId().isEmpty(), "Existing 80-tick system jump became a local approach");
            buffer.clear();
            writeTransition(buffer, true, 0, "", 52);
            ExplorationPayload canceled = ExplorationPayload.CODEC.decode(buffer);
            helper.assertTrue(!canceled.approaching() && !canceled.interstellarJump() && canceled.navigationEpoch() == 52,
                    "Canceled route retained transition ownership or lost its new epoch");
            buffer.clear();
            record InvalidTransition(boolean active, int ticks, String target) {}
            for (InvalidTransition invalid : new InvalidTransition[]{
                    new InvalidTransition(true, ExplorationPayload.MAX_TRANSITION_TICKS + 1, "sol/earth"),
                    new InvalidTransition(true, -1, "sol/earth"),
                    new InvalidTransition(false, 120, "sol/earth"),
                    new InvalidTransition(true, 0, "sol/earth"),
                    new InvalidTransition(true, 120, ""),
                    new InvalidTransition(true, 120, "s_1_0_0/earth"),
                    new InvalidTransition(true, 120, "sol/"),
                    new InvalidTransition(true, 120, "sol/earth/moon"),
                    new InvalidTransition(true, ExplorationPayload.MAX_TRANSITION_TICKS + 1, "s_1_0_0"),
                    new InvalidTransition(true, 80, "s_2_0_0")}) {
                writeTransition(buffer, invalid.active(), invalid.ticks(), invalid.target(), 51);
                boolean rejected = false;
                try { ExplorationPayload.CODEC.decode(buffer); }
                catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected, "Contradictory transition decoded successfully: " + invalid);
                buffer.clear();
            }
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static void writeTransition(RegistryFriendlyByteBuf buffer, boolean active, int ticks, String target, long epoch) {
        buffer.writeLong(20260928); buffer.writeLong(100); buffer.writeUtf("sol", 64);
        for (int component = 0; component < 6; component++) { buffer.writeDouble(0); }
        buffer.writeBoolean(active); buffer.writeDouble(100);
        buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(0); buffer.writeDouble(1);
        buffer.writeVarInt(ticks); buffer.writeUtf(target, 129);
        buffer.writeVarInt(2); buffer.writeUtf("sol", 64); buffer.writeUtf("s_1_0_0", 64);
        buffer.writeVarInt(2); buffer.writeUtf("sol", 64); buffer.writeUtf("s_1_0_0", 64);
        buffer.writeLong(99); buffer.writeLong(epoch);
        buffer.writeDouble(5); buffer.writeBoolean(false); buffer.writeLong(0);
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void explorationVersionMigrationAndStrictOrientation(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.get(server);
        UUID sourceId = UUID.randomUUID();
        catalog.player(sourceId);
        CompoundTag original = catalog.save(new CompoundTag(), server.registryAccess());
        CompoundTag prototype = original.getList("players", Tag.TAG_COMPOUND).getCompound(0).copy();
        CompoundTag legacy = original.copy();
        legacy.remove("custom_systems");
        legacy.putInt("version", 1); legacy.putLong("clock_ticks", 123456); legacy.putBoolean("landing", true);
        ListTag entries = new ListTag();
        for (int gear = 0; gear < FlightDynamics.speedCount(); gear++) {
            CompoundTag entry = prototype.copy();
            entry.putUUID("uuid", new UUID(170, gear));
            entry.putString("system", "s_1_0_0");
            entry.putDouble("x", 1_234_567_890.125 + gear); entry.putDouble("y", -7.25); entry.putDouble("z", 3.5);
            entry.putDouble("vx", 17.5); entry.putDouble("vy", -2.75); entry.putDouble("vz", 9.125);
            entry.putFloat("yaw", -177.125f + gear); entry.putFloat("pitch", 89.9f - gear);
            entry.putInt("speed", gear); entry.putLong("revision", 900 + gear);
            for (String key : new String[]{"qx", "qy", "qz", "qw", "speed_mps", "visited"}) { entry.remove(key); }
            ListTag discoveries = new ListTag();
            discoveries.add(StringTag.valueOf("sol")); discoveries.add(StringTag.valueOf("s_1_0_0"));
            entry.put("discovered", discoveries); entries.add(entry);
        }
        legacy.put("players", entries);
        ExplorationCatalog migrated = ExplorationCatalog.decode(legacy);
        CompoundTag current = migrated.save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(migrated.isDirty() && current.getInt("version") == 7, "Legacy save was not marked for v7 migration");
        helper.assertTrue(current.getLong("seed") == legacy.getLong("seed")
                && current.getLong("clock_ticks") == legacy.getLong("clock_ticks") && current.getBoolean("landing"),
                "Migration changed catalog identity, active time or landing ownership");
        for (int gear = 0; gear < FlightDynamics.speedCount(); gear++) {
            CompoundTag before = entries.getCompound(gear);
            CompoundTag after = current.getList("players", Tag.TAG_COMPOUND).getCompound(gear);
            for (String key : new String[]{"uuid", "system", "x", "y", "z", "vx", "vy", "vz", "revision", "discovered"}) {
                helper.assertTrue(before.get(key).equals(after.get(key)), "Migration changed preserved field " + key);
            }
            var pilot = migrated.player(new UUID(170, gear));
            helper.assertTrue(pilot.speedMetersPerSecond() == FlightDynamics.speed(gear), "Migration quantized an old gear");
            helper.assertTrue(pilot.orientation().equals(FlightDynamics.legacyOrientation(before.getFloat("yaw"),
                    before.getFloat("pitch"))), "Migration changed the old view direction");
        }
        CompoundTag changed = current.copy();
        CompoundTag navigation = changed.getList("players", Tag.TAG_COMPOUND).getCompound(0);
        FlightOrientation view = FlightOrientation.fromAngles(-52, 147, 63);
        navigation.putDouble("speed_mps", 1234.56789);
        navigation.putDouble("qx", view.x()); navigation.putDouble("qy", view.y());
        navigation.putDouble("qz", view.z()); navigation.putDouble("qw", view.w());
        CompoundTag roundTrip = ExplorationCatalog.decode(changed).save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(changed.equals(roundTrip), "Current save changed roll, continuous speed or encoded components");
        for (String key : new String[]{"speed_mps", "qx", "qy", "qz", "qw"}) {
            CompoundTag missing = changed.copy(); missing.getList("players", Tag.TAG_COMPOUND).getCompound(0).remove(key);
            helper.assertTrue(rejectsExploration(missing), "Missing version-two field was silently defaulted: " + key);
        }
        CompoundTag malformed = changed.copy();
        malformed.getList("players", Tag.TAG_COMPOUND).getCompound(0).putDouble("qw", 2);
        helper.assertTrue(rejectsExploration(malformed), "Non-unit saved orientation was silently normalized");
        CompoundTag badSpeed = changed.copy();
        badSpeed.getList("players", Tag.TAG_COMPOUND).getCompound(0).putDouble("speed_mps", Double.NaN);
        helper.assertTrue(rejectsExploration(badSpeed), "Non-finite saved continuous speed was accepted");
        CompoundTag badLegacy = legacy.copy();
        badLegacy.getList("players", Tag.TAG_COMPOUND).getCompound(0).putInt("speed", 999);
        helper.assertTrue(rejectsExploration(badLegacy), "Invalid legacy speed was silently repaired");
        helper.succeed();
    }

    private static boolean rejectsExploration(CompoundTag tag) {
        try { ExplorationCatalog.decode(tag); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void solarStatePersistenceAndOperatorScope(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        SolarState detached = SolarState.decode(SolarState.get(server).save(new CompoundTag(), server.registryAccess()));
        detached.startDemo(11);
        for (int tick = 0; tick < 270; tick++) { detached.tick(true); }
        detached.pause();
        var snapshot = detached.snapshot();
        CompoundTag encoded = detached.save(new CompoundTag(), server.registryAccess());
        SolarState restored = SolarState.decode(encoded);
        helper.assertTrue(restored.snapshot().equals(snapshot), "Solar save changed an interrupted collapse");
        helper.assertTrue(!restored.tick(true) && restored.snapshot().equals(snapshot), "Paused collapse advanced after load");
        restored.resume(); restored.tick(true);
        helper.assertTrue(restored.snapshot().phaseTicks() == snapshot.phaseTicks() + 1,
                "Resumed collapse lost its exact phase clock");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            SolarPayload payload = new SolarPayload(snapshot);
            SolarPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 128, "Solar payload exceeded the bounded snapshot budget");
            helper.assertTrue(SolarPayload.CODEC.decode(buffer).equals(payload), "Solar payload changed during roundtrip");
            helper.assertTrue(!buffer.isReadable(), "Solar payload left unread bytes");
            buffer.clear(); SolarPayload.CODEC.encode(buffer, payload);
            buffer.setLong(0, 2_000_000);
            boolean badEnergyRejected = false;
            try { SolarPayload.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { badEnergyRejected = true; }
            helper.assertTrue(badEnergyRejected, "Invalid solar network accounting was accepted");
        } finally {
            buffer.release();
        }
        CompoundTag missingTime = encoded.copy(); missingTime.remove("phase_ticks");
        boolean missingRejected = false;
        try { SolarState.decode(missingTime); } catch (IllegalArgumentException expected) { missingRejected = true; }
        helper.assertTrue(missingRejected, "Missing solar phase time was silently reset");
        CompoundTag wrongVersion = encoded.copy(); wrongVersion.putInt("version", 999);
        boolean versionRejected = false;
        try { SolarState.decode(wrongVersion); } catch (IllegalArgumentException expected) { versionRejected = true; }
        helper.assertTrue(versionRejected, "Unknown solar save version was accepted");
        CompoundTag wrongPhase = encoded.copy(); wrongPhase.putString("phase", "REMNANT");
        boolean phaseRejected = false;
        try { SolarState.decode(wrongPhase); } catch (IllegalArgumentException expected) { phaseRejected = true; }
        helper.assertTrue(phaseRejected, "Contradictory solar phase was accepted");
        var command = server.getCommands().getDispatcher().getRoot().getChild("astra").getChild("sun");
        helper.assertTrue(command != null, "Solar command subtree was not registered");
        helper.assertTrue(!command.canUse(server.createCommandSourceStack().withPermission(1)), "Non-operator may alter Sol");
        helper.assertTrue(command.canUse(server.createCommandSourceStack().withPermission(2)), "Operator cannot use solar diagnostics");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 80)
    public static void emptySystemsPause(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        // Vanilla GameTestServer bakes an empty LevelStem registry, omitting datapack dimensions.
        // The native client fixture verifies actual dimensions; this tests server-side absence policy.
        var before = AstraSystems.snapshot(server, "beta");
        SolarState solar = SolarState.get(server);
        solar.startDemo(10);
        var solarBefore = solar.snapshot();
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(AstraSystems.snapshot(server, "beta").equals(before), "Empty system evolved");
            helper.assertTrue(solar.snapshot().equals(solarBefore), "Unoccupied configured solar demo evolved");
            solar.reset();
            helper.succeed();
        });
    }
}
