package dev.lexawhatt.astraengine.compat.construction;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Opaque historical fields, with no catalog, validation, editor, synchronization or ticker. */
public final class ArchivedEditorBlockEntity extends BlockEntity {
    private static final List<String> FIELDS = List.of("rocket_editor_version", "revision", "blueprint", "assembly");
    private CompoundTag archived = new CompoundTag();

    /** Native chunk-owned compatibility record; loading and saving use the owning level thread. */
    public ArchivedEditorBlockEntity(BlockPos position, BlockState state) {
        super(ArchivedConstruction.EDITOR_BLOCK_ENTITY.get(), position, state);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        archived = new CompoundTag();
        for (String key : FIELDS) {
            Tag value = tag.get(key);
            if (value != null) {
                archived.put(key, value.copy());
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (String key : FIELDS) {
            Tag value = archived.get(key);
            if (value != null) {
                tag.put(key, value.copy());
            }
        }
    }
}
