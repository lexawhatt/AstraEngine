package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.api.rocket.AstraRocketEditor;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload;
import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload.Action;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketValue;
import dev.lexawhatt.astraengine.server.rocket.RocketAssemblyEntity;
import dev.lexawhatt.astraengine.server.rocket.RocketBlueprintCodec;
import dev.lexawhatt.astraengine.server.rocket.RocketEditorBlockEntity;
import dev.lexawhatt.astraengine.server.rocket.RocketEditorSessions;
import dev.lexawhatt.astraengine.server.rocket.RocketHitboxPart;
import dev.lexawhatt.astraengine.server.rocket.RocketWorkshop;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real dedicated host authority, persistent schema values and NeoForge multipart collision boundaries. */
@PrefixGameTestTemplate(false)
public final class RocketWorkshopGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void strictExternalSchemaStorageAndBoundedWire(GameTestHelper helper) {
        RocketBlueprint draft = editedDraft();
        CompoundTag saved = AstraRocketEditor.encodeBlueprint(draft);
        RocketBlueprint decoded = AstraRocketEditor.decodeBlueprint(saved);
        helper.assertTrue(decoded.equals(draft), "External typed values changed in public API storage");
        CompoundTag detached = AstraRocketEditor.encodeBlueprint(draft);
        RocketBlueprint detachedDecoded = AstraRocketEditor.decodeBlueprint(detached);
        detached.getList("parts", Tag.TAG_COMPOUND).getCompound(0).putDouble("position_x", 1);
        helper.assertTrue(AstraRocketEditor.decodeBlueprint(saved).equals(draft) && detachedDecoded.equals(draft),
                "Public encoding reused mutable storage or decoding retained its input tag");
        rejects(helper, () -> AstraRocketEditor.encodeBlueprint(null), "Null public blueprint encoding");
        rejects(helper, () -> AstraRocketEditor.decodeBlueprint(null), "Null public blueprint decoding");
        var part = saved.getList("parts", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag unknown = saved.copy(); unknown.getList("parts", Tag.TAG_COMPOUND).getCompound(0)
                .putString("definition", "verification:missing");
        rejects(helper, () -> AstraRocketEditor.decodeBlueprint(unknown), "Unknown definition");
        CompoundTag malformed = saved.copy(); malformed.getList("parts", Tag.TAG_COMPOUND).getCompound(0)
                .putFloat("position_x", 0);
        rejects(helper, () -> RocketBlueprintCodec.decode(malformed), "Wrong exact numeric tag type");
        for (String key : List.of(RocketVerificationParts.RANGE, RocketVerificationParts.STABILIZED)) {
            CompoundTag badValue = saved.copy();
            var fields = badValue.getList("parts", Tag.TAG_COMPOUND).getCompound(0)
                    .getList("modules", Tag.TAG_COMPOUND).getCompound(0).getList("values", Tag.TAG_COMPOUND);
            for (Tag fieldTag : fields) {
                CompoundTag field = (CompoundTag) fieldTag;
                if (field.getString("key").equals(key)) {
                    if (key.equals(RocketVerificationParts.RANGE)) { field.putDouble("number", Double.NaN); }
                    else { field.putByte("flag", (byte) 2); }
                }
            }
            rejects(helper, () -> RocketBlueprintCodec.decode(badValue), "Malformed external value " + key);
        }
        CompoundTag extra = saved.copy();
        var fields = extra.getList("parts", Tag.TAG_COMPOUND).getCompound(0)
                .getList("modules", Tag.TAG_COMPOUND).getCompound(0).getList("values", Tag.TAG_COMPOUND);
        CompoundTag added = fields.getCompound(0).copy(); added.putString("key", "unregistered"); fields.add(added);
        rejects(helper, () -> RocketBlueprintCodec.decode(extra), "Unregistered module field");
        CompoundTag duplicate = saved.copy(); duplicate.getList("parts", Tag.TAG_COMPOUND).add(part.copy());
        rejects(helper, () -> RocketBlueprintCodec.decode(duplicate), "Duplicate graph identity");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var request = new RocketEditorCommandPayload(UUID.randomUUID(), 7, Action.SAVE, draft);
            RocketEditorCommandPayload.CODEC.encode(buffer, request);
            helper.assertTrue(RocketEditorCommandPayload.CODEC.decode(buffer).equals(request) && !buffer.isReadable(),
                    "Typed external request changed on wire");
            buffer.clear(); RocketBlueprintCodec.write(buffer, draft);
            buffer.readVarInt(); buffer.readUtf(64); buffer.readVarInt(); buffer.readVarInt(); buffer.readVarInt(); buffer.readUtf(64);
            buffer.setByte(buffer.readerIndex(), buffer.getByte(buffer.readerIndex()) ^ 1); buffer.readerIndex(0);
            rejects(helper, () -> RocketBlueprintCodec.read(buffer), "Definition fingerprint mismatch");
            buffer.clear(); buffer.writeVarInt(1); buffer.writeUtf("Oversized", 64); buffer.writeVarInt(33);
            rejects(helper, () -> RocketBlueprintCodec.read(buffer), "Oversized part list before allocation");
            buffer.clear();
            List<RocketPart> maximum = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                maximum.add(new RocketPart(index, index == 0 ? -1 : 0, RocketVerificationParts.BOUNDARY_ID,
                        new SpaceVector(index % 4, index / 16, index / 4 % 4), new SpaceVector(0.25, 0.25, 0.25), 0,
                        AstraRocketEditor.catalog().defaultValues(RocketVerificationParts.BOUNDARY_ID)));
            }
            RocketBlueprint boundary = new RocketBlueprint("Maximum fields", maximum);
            var boundaryRequest = new RocketEditorCommandPayload(UUID.randomUUID(), Long.MAX_VALUE, Action.SAVE, boundary);
            RocketEditorCommandPayload.CODEC.encode(buffer, boundaryRequest);
            helper.assertTrue(buffer.readableBytes() < 16_384,
                    "Maximum schema draft approached the NeoForge 32 KiB C2S payload cap");
            helper.assertTrue(RocketEditorCommandPayload.CODEC.decode(buffer).equals(boundaryRequest) && !buffer.isReadable(),
                    "Maximum 32 x 32 numeric draft failed exact bounded roundtrip");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", batch = "rocket_workshops")
    public static void editorTokenPermissionRevisionAndPersistence(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
        level.setBlockAndUpdate(position, RocketWorkshop.EDITOR.get().defaultBlockState());
        RocketEditorBlockEntity host = (RocketEditorBlockEntity) level.getBlockEntity(position);
        ServerPlayer player = negotiatedPlayer(helper);
        player.setPos(Vec3.atCenterOf(position).add(-1.5, 0, 0));
        try {
            helper.assertTrue(AstraRocketEditor.open(player, position), "Authorized diagnostic host refused open");
            UUID token = token(player);
            RocketBlueprint draft = editedDraft();
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(UUID.randomUUID(), 0, Action.SAVE, draft));
            helper.assertTrue(host.revision() == 0 && token(player).equals(token), "Forged token mutated host or revoked owner session");
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(token, 0, Action.SAVE, draft));
            helper.assertTrue(host.revision() == 1 && host.blueprint().equals(draft), "Valid save was not atomic");
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(token, 0, Action.SAVE, RocketVerificationParts.blueprint()));
            helper.assertTrue(host.revision() == 1 && host.blueprint().equals(draft), "Stale revision replaced newer draft");
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(token, 1, Action.SAVE, RocketVerificationParts.blueprint()));
            helper.assertTrue(host.revision() == 1, "Burst request bypassed rate bound");
            UUID deployment = UUID.randomUUID(); host.setDeployedAssembly(Optional.of(deployment));
            CompoundTag persisted = host.saveCustomOnly(level.registryAccess());
            RocketEditorBlockEntity restored = new RocketEditorBlockEntity(position, host.getBlockState());
            restored.setLevel(level); restored.loadCustomOnly(persisted, level.registryAccess());
            helper.assertTrue(restored.blueprint().equals(draft) && restored.revision() == 1
                    && restored.deployedAssembly().equals(Optional.of(deployment)), "Host save lost draft/revision/deployment");
            CompoundTag invalid = persisted.copy(); invalid.putInt("revision", 2);
            rejects(helper, () -> restored.loadCustomOnly(invalid, level.registryAccess()), "Wrong revision tag type");
            helper.assertTrue(restored.saveCustomOnly(level.registryAccess()).equals(persisted), "Rejected load replaced prior valid host state");
            CompoundTag playerSave = player.saveWithoutId(new CompoundTag());
            helper.assertTrue(!playerSave.getCompound("neoforge:attachments").contains("astraengine:rocket_editor_session"),
                    "Transient editing attachment leaked into player persistence");
            helper.assertTrue(AstraRocketEditor.open(player, position) && !token(player).equals(token), "Reopen reused old session");
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(token, 1, Action.SAVE, RocketVerificationParts.blueprint()));
            helper.assertTrue(host.revision() == 1, "Replaced session retained save authority");
            UUID current = token(player);
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(current, 1, Action.CLOSE, null));
            helper.assertTrue(player.getData(RocketWorkshop.EDIT_SESSION).isEmpty(), "Close retained a session token");
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(current, 1, Action.SAVE, RocketVerificationParts.blueprint()));
            helper.assertTrue(host.revision() == 1, "Closed session replay mutated host");
            player.getAbilities().mayBuild = false;
            helper.assertTrue(!AstraRocketEditor.open(player, position), "Build restriction was ignored");
            player.getAbilities().mayBuild = true;
            helper.assertTrue(AstraRocketEditor.open(player, position), "Reauthorized host refused reopen");
            player.setPos(Vec3.atCenterOf(position).add(9, 0, 0)); RocketEditorSessions.tick(player);
            helper.assertTrue(player.getData(RocketWorkshop.EDIT_SESSION).isEmpty()
                    && !AstraRocketEditor.open(player, position), "Out-of-range host retained authorization");
            player.setPos(Vec3.atCenterOf(position).add(-1.5, 0, 0));
            helper.assertTrue(AstraRocketEditor.open(player, position), "Could not open for replacement check");
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(position, RocketWorkshop.EDITOR.get().defaultBlockState());
            RocketEditorSessions.tick(player);
            helper.assertTrue(player.getData(RocketWorkshop.EDIT_SESSION).isEmpty(), "Replacement block inherited old host token");
        } finally {
            removePlayer(player);
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", batch = "rocket_workshops", timeoutTicks = 80)
    public static void deploymentTerrainGuardIdentityAndMultipartPersistence(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
        level.setBlockAndUpdate(position, RocketWorkshop.EDITOR.get().defaultBlockState());
        RocketEditorBlockEntity host = (RocketEditorBlockEntity) level.getBlockEntity(position);
        host.setBlueprint(editedDraft());
        ServerPlayer player = negotiatedPlayer(helper);
        player.setPos(Vec3.atCenterOf(position).add(-1.5, 0, 0));
        helper.assertTrue(AstraRocketEditor.open(player, position), "Cannot open deployment fixture");
        UUID session = token(player);
        BlockPos obstruction = position.east(2);
        level.setBlockAndUpdate(obstruction, Blocks.DIAMOND_BLOCK.defaultBlockState());
        RocketEditorSessions.handle(player, new RocketEditorCommandPayload(session, 1, Action.DEPLOY, null));
        helper.assertTrue(host.deployedAssembly().isEmpty() && level.getBlockState(obstruction).is(Blocks.DIAMOND_BLOCK),
                "Deployment overwrote occupied terrain");
        level.setBlockAndUpdate(obstruction, Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(5, () -> {
            RocketEditorSessions.handle(player, new RocketEditorCommandPayload(session, 1, Action.DEPLOY, null));
            helper.assertTrue(host.deployedAssembly().isPresent(), "Clear loaded deployment was refused");
            RocketAssemblyEntity assembly = (RocketAssemblyEntity) level.getEntity(host.deployedAssembly().orElseThrow());
            helper.assertTrue(assembly != null && assembly.blueprint().equals(editedDraft()) && host.revision() == 1,
                    "Deployment lost snapshot or changed draft revision");
            UUID id = assembly.getUUID();
            RocketHitboxPart[] originalParts = assembly.getParts();
            helper.assertTrue(originalParts.length == 96 && assembly.activeHitboxCount() == 1 && !assembly.canBeCollidedWith(),
                    "Assembly uses an oversized solid parent or dynamic child array");
            helper.assertTrue(level.getEntityOrPart(originalParts[0].getId()) == originalParts[0],
                    "NeoForge did not register the active multipart child ID");
            Vec3 center = originalParts[0].getBoundingBox().getCenter();
            Vec3 start = center.add(-2, 0, 0), end = center.add(2, 0, 0);
            var hit = ProjectileUtil.getEntityHitResult(level, player, start, end, new AABB(start, end).inflate(0.1),
                    Entity::isPickable, 0);
            helper.assertTrue(hit != null && hit.getEntity() == originalParts[0], "Normal host ray missed the close child collider");
            helper.assertTrue(!level.getEntityCollisions(null, originalParts[0].getBoundingBox().deflate(0.1)).isEmpty(),
                    "Host movement collisions ignored the child");
            assembly.setYRot(90); assembly.setXRot(30);
            helper.assertTrue(assembly.getYRot() == 0 && assembly.getXRot() == 0, "Stationary geometry drifted from collider axes");
            CompoundTag stored = assembly.saveWithoutId(new CompoundTag());
            RocketAssemblyEntity restored = new RocketAssemblyEntity(RocketWorkshop.ASSEMBLY.get(), level);
            restored.load(stored);
            helper.assertTrue(restored.blueprint().equals(assembly.blueprint()) && restored.hostPosition().equals(position)
                    && restored.getUUID().equals(id) && restored.getBoundingBox().equals(assembly.getBoundingBox()),
                    "Assembly persistence changed geometry, owner, identity or bounds");
            helper.runAfterDelay(5, () -> {
                RocketEditorSessions.handle(player, new RocketEditorCommandPayload(session, 1, Action.DEPLOY, null));
                helper.assertTrue(host.deployedAssembly().equals(Optional.of(id)) && level.getEntity(id) == assembly,
                        "Repeated deployment duplicated its persistent assembly");
                var values = AstraRocketEditor.catalog().defaultValues(RocketVerificationParts.PART_ID);
                var left = new RocketPart(0, -1, RocketVerificationParts.PART_ID, new SpaceVector(-2, 0, 0),
                        new SpaceVector(1, 1, 1), 0, values);
                var right = new RocketPart(1, 0, RocketVerificationParts.PART_ID, new SpaceVector(2, 0, 0),
                        new SpaceVector(1, 1, 1), 0, values);
                assembly.configure(position, new RocketBlueprint("Open gap", List.of(left, right)));
                AABB gap = new AABB(assembly.position().add(-0.2, -0.2, -0.2), assembly.position().add(0.2, 0.2, 0.2));
                helper.assertTrue(assembly.getBoundingBox().intersects(gap) && level.getEntityCollisions(null, gap).isEmpty(),
                        "Multipart gap became solid through the parent bounds");
                helper.assertTrue(assembly.activeHitboxCount() == 2 && assembly.getParts()[1] == originalParts[1]
                        && level.getEntityOrPart(originalParts[1].getId()) == originalParts[1],
                        "Activating another child replaced its registered identity");
                assembly.configure(position, editedDraft());
                helper.assertTrue(!originalParts[1].isPickable() && !originalParts[1].canBeCollidedWith(),
                        "Inactive retained child still intercepts interaction or movement");
                assembly.discard();
                helper.assertTrue(host.deployedAssembly().isEmpty(), "Destroyed assembly retained orphan host ownership");
                removePlayer(player);
                level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
                helper.succeed();
            });
        });
    }

    private static ServerPlayer negotiatedPlayer(GameTestHelper helper) {
        var level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "rocket-test-player");
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(),
                false, ConnectionType.NEOFORGE);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation()) {
            @Override
            public boolean isSpectator() { return false; }
            @Override
            public boolean isCreative() { return true; }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        // The vanilla helper logs in an unnegotiated client. NeoForge's supported test setup must precede
        // placeNewPlayer, because normal login listeners immediately send registered engine snapshots.
        NetworkRegistry.configureMockConnection(connection);
        try {
            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            return player;
        } catch (RuntimeException exception) {
            level.getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
            throw exception;
        }
    }

    private static void removePlayer(ServerPlayer player) {
        player.removeData(RocketWorkshop.EDIT_SESSION);
        player.server.getPlayerList().remove(player);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
        channel.finishAndReleaseAll();
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != null
                || player.serverLevel().players().contains(player) || channel.isOpen()
                || player.hasData(RocketWorkshop.EDIT_SESSION)) {
            throw new IllegalStateException("Rocket fixture retained its player, connection or editor session after cleanup");
        }
    }

    private static UUID token(ServerPlayer player) {
        return player.getData(RocketWorkshop.EDIT_SESSION).orElseThrow().token();
    }
    private static RocketBlueprint editedDraft() {
        RocketBlueprint base = RocketVerificationParts.blueprint();
        RocketPart part = base.parts().getFirst();
        return base.withPart(new RocketPart(part.id(), part.parentId(), part.definitionId(), part.position(), part.size(),
                part.yawQuarterTurns(), Map.of(RocketVerificationParts.MODULE_ID, Map.of(
                        RocketVerificationParts.RANGE, RocketValue.number(42.5), RocketVerificationParts.STABILIZED,
                        RocketValue.flag(true), RocketVerificationParts.MODE, RocketValue.choice("wormhole")))));
    }
    private static void rejects(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, label + " was accepted");
    }
}
