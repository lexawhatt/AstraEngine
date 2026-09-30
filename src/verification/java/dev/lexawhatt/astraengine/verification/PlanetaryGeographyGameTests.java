package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.PlanetaryGeographyState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Strict real-host metadata persistence checks, separate from pure topology and native world restart. */
@PrefixGameTestTemplate(false)
public final class PlanetaryGeographyGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void geographicOwnershipRoundtripsWithoutAllocatingWorlds(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var state = PlanetaryGeographyState.get(server);
        var levelsBefore = new ArrayList<>(server.levelKeys());
        CompoundTag encoded = state.save(new CompoundTag(), server.registryAccess());
        var decoded = PlanetaryGeographyState.decode(encoded);
        helper.assertTrue(decoded.topology().equals(state.topology()), "Saved topology changed on decode");
        for (SpaceVector direction : new SpaceVector[]{new SpaceVector(1, 0, 0), new SpaceVector(-1, 0, 0),
                new SpaceVector(0, 1, 0), new SpaceVector(0, -1, 0), new SpaceVector(1, 1, 1)}) {
            var tile = state.topology().locate(direction);
            helper.assertTrue(decoded.tileKey(tile).equals(state.tileKey(tile)), "Tile ownership changed on reload");
        }
        helper.assertTrue(encoded.equals(decoded.save(new CompoundTag(), server.registryAccess())),
                "Metadata roundtrip changed saved fields");
        helper.assertTrue(server.levelKeys().equals(new HashSet<>(levelsBefore)),
                "Geography diagnostics allocated runtime worlds");
        helper.assertTrue(PlanetaryGeographyState.get(server) == state, "Manifest is not owned by host SavedData storage");
        helper.assertTrue(CompletableFuture.supplyAsync(() -> {
            try { PlanetaryGeographyState.get(server); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join(), "Worker thread accessed mutable host data storage");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void diagnosticsRequireOperatorAndDoNotChangeIdentity(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var command = dispatcher.getRoot().getChild("astra").getChild("terrain");
        helper.assertTrue(!command.canUse(server.createCommandSourceStack().withPermission(1)),
                "Geographic diagnostics accept a non-operator source");
        var operator = server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
        var state = PlanetaryGeographyState.get(server);
        CompoundTag before = state.save(new CompoundTag(), server.registryAccess());
        for (String coordinates : new String[]{"90 0", "90 180", "-90 0", "-90 180", "0 180", "0 -180", "45 0"}) {
            helper.assertTrue(dispatcher.execute("astra terrain locate " + coordinates, operator) == 1,
                    "Valid polar/seam geographic coordinate failed: " + coordinates);
        }
        helper.assertTrue(dispatcher.execute("astra terrain here", operator) == 0,
                "Ordinary Overworld was incorrectly interpreted as the highlands geography");
        helper.assertTrue(before.equals(state.save(new CompoundTag(), server.registryAccess())),
                "Read-only geographic diagnostics changed the manifest");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void incompatibleManifestRejectsWithoutMutatingValidState(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var state = PlanetaryGeographyState.get(server);
        CompoundTag original = state.save(new CompoundTag(), server.registryAccess());
        var malformed = new ArrayList<CompoundTag>();
        for (String key : original.getAllKeys()) {
            CompoundTag missing = original.copy(); missing.remove(key); malformed.add(missing);
            CompoundTag wrongType = original.copy(); wrongType.putBoolean(key, true); malformed.add(wrongType);
        }
        for (String key : new String[]{"version", "topology_version", "level", "terrain_version"}) {
            CompoundTag changed = original.copy(); changed.putInt(key, original.getInt(key) + 1); malformed.add(changed);
        }
        CompoundTag seed = original.copy(); seed.putLong("terrain_seed", original.getLong("terrain_seed") + 1); malformed.add(seed);
        for (double radius : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 6_371_001}) {
            CompoundTag changed = original.copy(); changed.putDouble("radius_meters", radius); malformed.add(changed);
        }
        for (String key : new String[]{"dimension", "geography"}) {
            CompoundTag changed = original.copy(); changed.putString(key, "example:foreign"); malformed.add(changed);
        }
        for (CompoundTag invalid : malformed) {
            boolean rejected = false;
            try { PlanetaryGeographyState.decode(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Incompatible geography metadata was accepted: " + invalid);
            helper.assertTrue(original.equals(state.save(new CompoundTag(), server.registryAccess())),
                    "Rejected metadata changed the previously valid identity");
        }
        helper.succeed();
    }
}
