package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.api.rocket.RocketEditorHost;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Persistent diagnostic host; the reusable editor API accepts any authorized RocketEditorHost block entity. */
public final class RocketEditorBlockEntity extends BlockEntity implements RocketEditorHost {
    private RocketBlueprint blueprint = new RocketBlueprint("Rocket", List.of());
    private long revision;
    private Optional<UUID> deployedAssembly = Optional.empty();

    public RocketEditorBlockEntity(BlockPos position, BlockState state) { super(RocketWorkshop.EDITOR_BLOCK_ENTITY.get(), position, state); }
    @Override
    public RocketBlueprint blueprint() { return blueprint; }
    @Override
    public long revision() { return revision; }
    @Override
    public boolean canEdit(ServerPlayer player) { return player.isCreative() || player.hasPermissions(2); }
    @Override
    public void setBlueprint(RocketBlueprint value) {
        requireServerThread();
        if (value == null || revision == Long.MAX_VALUE) { throw new IllegalArgumentException("Invalid rocket draft or exhausted revision"); }
        RocketWorkshop.catalog().validate(value);
        blueprint = value; revision++; setChanged();
    }
    @Override
    public Optional<UUID> deployedAssembly() { return deployedAssembly; }
    @Override
    public void setDeployedAssembly(Optional<UUID> value) {
        requireServerThread();
        if (value == null) { throw new IllegalArgumentException("Rocket deployment ownership cannot be null"); }
        deployedAssembly = value; setChanged();
    }
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("rocket_editor_version", 1); tag.putLong("revision", revision);
        tag.put("blueprint", RocketBlueprintCodec.encode(blueprint));
        deployedAssembly.ifPresent(id -> tag.putUUID("assembly", id));
    }
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Empty host update tags contain no private draft. A stored editor record is always strict and atomic.
        if (!tag.contains("rocket_editor_version") && !tag.contains("revision") && !tag.contains("blueprint") && !tag.contains("assembly")) { return; }
        if (!tag.contains("rocket_editor_version", Tag.TAG_INT) || tag.getInt("rocket_editor_version") != 1
                || !tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 0
                || !tag.contains("blueprint", Tag.TAG_COMPOUND) || tag.contains("assembly") && !tag.hasUUID("assembly")) {
            throw new IllegalArgumentException("Invalid persisted rocket editor record");
        }
        RocketBlueprint restored = RocketBlueprintCodec.decode(tag.getCompound("blueprint"));
        Optional<UUID> assembly = tag.hasUUID("assembly") ? Optional.of(tag.getUUID("assembly")) : Optional.empty();
        blueprint = restored; revision = tag.getLong("revision"); deployedAssembly = assembly;
    }
    private void requireServerThread() {
        if (level == null || level.isClientSide || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Rocket editor mutation requires its owning server level thread");
        }
    }
}
