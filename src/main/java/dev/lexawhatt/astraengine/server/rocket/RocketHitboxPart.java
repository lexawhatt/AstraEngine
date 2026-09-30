package dev.lexawhatt.astraengine.server.rocket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/** One stable registered collision/picking slice; its parent owns persistence and shader geometry. */
public final class RocketHitboxPart extends PartEntity<RocketAssemblyEntity> {
    private boolean active;
    RocketHitboxPart(RocketAssemblyEntity parent) { super(parent); }
    void update(AABB box, boolean enabled) {
        active = enabled; blocksBuilding = enabled;
        setPos((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5); setBoundingBox(box);
    }
    public boolean active() { return active && !getParent().isRemoved(); }
    @Override
    public boolean canBeCollidedWith() { return active(); }
    @Override
    public boolean isPickable() { return active(); }
    @Override
    public boolean shouldBeSaved() { return false; }
    @Override
    public boolean is(Entity entity) { return this == entity || getParent() == entity; }
    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) { return active() ? getParent().interact(player, hand) : InteractionResult.PASS; }
    @Override
    public InteractionResult interactAt(Player player, Vec3 point, InteractionHand hand) { return interact(player, hand); }
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
