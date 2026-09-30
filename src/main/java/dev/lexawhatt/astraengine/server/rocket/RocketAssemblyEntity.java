package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.api.rocket.AstraRocketEditor;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.rocket.RocketEditorHost;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketBounds;
import dev.lexawhatt.astraengine.rocket.RocketGeometry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Persistent stationary assembly; visual geometry is shader-owned, collision/picking uses fixed multipart children. */
public final class RocketAssemblyEntity extends Entity {
    private static final EntityDataAccessor<CompoundTag> BLUEPRINT = SynchedEntityData.defineId(RocketAssemblyEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private final RocketHitboxPart[] parts = new RocketHitboxPart[96];
    private RocketBlueprint blueprint = new RocketBlueprint("Rocket", List.of());
    private BlockPos hostPosition = BlockPos.ZERO;
    private int activeParts;

    public RocketAssemblyEntity(EntityType<? extends RocketAssemblyEntity> type, Level level) {
        super(type, level);
        for (int index = 0; index < parts.length; index++) { parts[index] = new RocketHitboxPart(this); }
        setId(ENTITY_COUNTER.getAndAdd(parts.length + 1) + 1);
        setNoGravity(true); noPhysics = true; updateBounds();
    }

    /** Immutable authoritative or synchronized draft, safe for the client renderer to retain. */
    public RocketBlueprint blueprint() { return blueprint; }
    /** Owning editor position in this entity's dimension; no cross-world reference is retained. */
    public BlockPos hostPosition() { return hostPosition; }
    /** Number of active close collision slices; unused fixed children remain noncollidable and unpickable. */
    public int activeHitboxCount() { return activeParts; }

    /** Updates the immutable deployed snapshot on the owning server; entity identity and child identities stay fixed. */
    public void configure(BlockPos owner, RocketBlueprint value) {
        if (level().isClientSide || !level().getServer().isSameThread() || owner == null) {
            throw new IllegalStateException("Rocket assembly configuration requires its server thread and owner");
        }
        RocketWorkshop.catalog().validate(value);
        if (value.parts().isEmpty()) { throw new IllegalArgumentException("An empty rocket cannot be deployed"); }
        hostPosition = owner.immutable(); blueprint = value;
        entityData.set(BLUEPRINT, RocketBlueprintCodec.encode(value)); updateBounds();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(BLUEPRINT, new CompoundTag()); }
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLUEPRINT.equals(key) && entityData.get(BLUEPRINT).contains("version")) {
            try {
                RocketBlueprint incoming = RocketBlueprintCodec.decode(entityData.get(BLUEPRINT));
                if (incoming.parts().isEmpty()) { throw new IllegalArgumentException("Empty deployed rocket snapshot"); }
                blueprint = incoming;
                updateBounds();
            } catch (IllegalArgumentException exception) {
                AstraEngine.LOGGER.warn("Rejected rocket assembly {} snapshot; retaining valid geometry", getUUID(), exception);
            }
        }
    }
    @Override
    public void setId(int id) {
        super.setId(id);
        if (parts != null) { for (int index = 0; index < parts.length; index++) { if (parts[index] != null) { parts[index].setId(id + index + 1); } } }
    }
    @Override
    public void setPos(double x, double y, double z) { super.setPos(x, y, z); if (parts != null && parts[0] != null) { updateBounds(); } }
    @Override
    public void setYRot(float yaw) { super.setYRot(0); }
    @Override
    public void setXRot(float pitch) { super.setXRot(0); }
    @Override
    public boolean isMultipartEntity() { return true; }
    @Override
    public RocketHitboxPart[] getParts() { return parts.clone(); }
    @Override
    public boolean canBeCollidedWith() { return false; }
    @Override
    public boolean isPickable() { return false; }
    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }
    @Override
    public void tick() {
        super.tick(); setDeltaMovement(Vec3.ZERO); updateBounds();
        if (!level().isClientSide && level().hasChunkAt(hostPosition)) {
            var host = level().getBlockEntity(hostPosition);
            if (!(host instanceof RocketEditorHost editor) || editor.deployedAssembly().filter(getUUID()::equals).isEmpty()) { discard(); }
        }
    }
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && !level().isClientSide && level().hasChunkAt(hostPosition)
                && level().getBlockEntity(hostPosition) instanceof RocketEditorHost host
                && host.deployedAssembly().filter(getUUID()::equals).isPresent()) {
            host.setDeployedAssembly(java.util.Optional.empty());
        }
        super.remove(reason);
    }
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) { return InteractionResult.SUCCESS; }
        if (player instanceof ServerPlayer serverPlayer && level().getBlockEntity(hostPosition) instanceof RocketEditorHost host
                && host.deployedAssembly().filter(getUUID()::equals).isPresent()) {
            return AstraRocketEditor.open(serverPlayer, hostPosition) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("rocket_assembly_version", 1); tag.putLong("host_position", hostPosition.asLong());
        tag.put("blueprint", RocketBlueprintCodec.encode(blueprint));
    }
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (!tag.contains("rocket_assembly_version", Tag.TAG_INT) || tag.getInt("rocket_assembly_version") != 1
                || !tag.contains("host_position", Tag.TAG_LONG) || !tag.contains("blueprint", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid persisted rocket assembly");
        }
        RocketBlueprint restored = RocketBlueprintCodec.decode(tag.getCompound("blueprint"));
        if (restored.parts().isEmpty()) { throw new IllegalArgumentException("Persisted assembly has no parts"); }
        blueprint = restored; hostPosition = BlockPos.of(tag.getLong("host_position"));
        entityData.set(BLUEPRINT, RocketBlueprintCodec.encode(restored)); updateBounds();
    }

    /** Exact world AABBs used for deployment validation and host collisions, without a solid overall parent box. */
    public static List<AABB> collisionBoxes(RocketBlueprint blueprint, Vec3 origin) {
        return RocketGeometry.collisionBoxes(RocketWorkshop.catalog(), blueprint).stream().map(bounds -> worldBox(bounds, origin)).toList();
    }
    private void updateBounds() {
        if (parts == null || parts[0] == null || blueprint == null) { return; }
        List<AABB> boxes = collisionBoxes(blueprint, position()); activeParts = boxes.size();
        if (activeParts > parts.length) { throw new IllegalStateException("Rocket collision slice budget exceeded"); }
        AABB overall = new AABB(position(), position());
        for (int index = 0; index < parts.length; index++) {
            boolean active = index < boxes.size(); AABB bounds = active ? boxes.get(index) : new AABB(position(), position());
            parts[index].update(bounds, active);
            if (active) { overall = index == 0 ? bounds : overall.minmax(bounds); }
        }
        setBoundingBox(overall);
    }
    private static AABB worldBox(RocketBounds bounds, Vec3 origin) {
        return new AABB(bounds.min().x() + origin.x, bounds.min().y() + origin.y, bounds.min().z() + origin.z,
                bounds.max().x() + origin.x, bounds.max().y() + origin.y, bounds.max().z() + origin.z);
    }
}
