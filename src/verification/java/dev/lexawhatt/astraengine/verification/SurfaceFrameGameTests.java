package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.server.PlanetaryGeographyState;
import dev.lexawhatt.astraengine.server.SurfaceFrameTracker;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Host-side access/lifetime guards; the native fixture supplies real highlands movement and two observers. */
@PrefixGameTestTemplate(false)
public final class SurfaceFrameGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void samplingIsReadOnlyAndRejectsWrongThread(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var player = new ServerPlayer(server, helper.getLevel(), new GameProfile(UUID.randomUUID(), "geography-probe"),
                ClientInformation.createDefault());
        player.setPos(10.5, 80, 12.5);
        player.setDeltaMovement(new Vec3(0.1, -0.08, 0.2));
        player.setYRot(17);
        player.setXRot(-23);
        var originalPosition = player.position();
        var originalVelocity = player.getDeltaMovement();
        var manifest = PlanetaryGeographyState.get(server);
        CompoundTag saved = manifest.save(new CompoundTag(), server.registryAccess());
        var levels = java.util.Set.copyOf(server.levelKeys());
        helper.assertTrue(AstraGeography.snapshot(player).isEmpty(), "Unsupported world acquired a geographic binding");
        helper.assertTrue(CompletableFuture.supplyAsync(() -> {
            try { AstraGeography.snapshot(player); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join(), "Wrong-thread sampling read mutable host state");
        helper.assertTrue(player.position().equals(originalPosition) && player.getDeltaMovement().equals(originalVelocity)
                        && player.getYRot() == 17 && player.getXRot() == -23,
                "Read-only observation changed host pose or stored velocity");
        helper.assertTrue(saved.equals(manifest.save(new CompoundTag(), server.registryAccess()))
                        && server.levelKeys().equals(levels), "Sampling changed identity or allocated a world");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void transientTrackerClosesAndHasNoPersistenceSideEffects(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var player = new ServerPlayer(server, helper.getLevel(), new GameProfile(UUID.randomUUID(), "frame-probe"),
                ClientInformation.createDefault());
        var tracker = new SurfaceFrameTracker(server);
        var state = PlanetaryGeographyState.get(server);
        CompoundTag saved = state.save(new CompoundTag(), server.registryAccess());
        tracker.observe(player);
        tracker.forget(player);
        helper.assertTrue(CompletableFuture.supplyAsync(() -> {
            try { tracker.observe(player); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join(), "Tracker accepted a worker-thread mutation");
        tracker.close();
        boolean rejected = false;
        try { tracker.observe(player); } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Closed server session accepted another observation");
        tracker.close();
        helper.assertTrue(saved.equals(state.save(new CompoundTag(), server.registryAccess())),
                "Transient lifecycle rewrote the saved geography");
        helper.succeed();
    }
}
