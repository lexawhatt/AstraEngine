package dev.lexawhatt.astraengine.compat.construction;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Invisible, noninteractive save record; native entity storage retains identity and pose. */
public final class ArchivedAssemblyEntity extends Entity {
    private static final List<String> FIELDS = List.of("rocket_assembly_version", "host_position", "blueprint");
    private CompoundTag archived = new CompoundTag();

    /** Creates an inert compatibility entity through the native historical type registration. */
    public ArchivedAssemblyEntity(EntityType<? extends ArchivedAssemblyEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    /** Does not run environmental mechanics or delete records when their old host is absent. */
    @Override
    public void tick() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        archived = new CompoundTag();
        for (String key : FIELDS) {
            Tag value = tag.get(key);
            if (value != null) {
                archived.put(key, value.copy());
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        for (String key : FIELDS) {
            Tag value = archived.get(key);
            if (value != null) {
                tag.put(key, value.copy());
            }
        }
    }
}
