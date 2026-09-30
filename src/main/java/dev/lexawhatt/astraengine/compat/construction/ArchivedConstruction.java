package dev.lexawhatt.astraengine.compat.construction;

import dev.lexawhatt.astraengine.AstraEngine;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Retains historical registry identities solely to preserve construction records in existing saves. */
public final class ArchivedConstruction {
    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, AstraEngine.MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, AstraEngine.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AstraEngine.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AstraEngine.MOD_ID);

    /** Historical block identity; intentionally absent from creative inventories. */
    public static final DeferredHolder<Block, ArchivedEditorBlock> EDITOR = BLOCKS.register(
            "rocket_editor", () -> new ArchivedEditorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(-1.0f, 3_600_000.0f).sound(SoundType.METAL)));
    /** Historical inventory identity; placing it does not create an editor. */
    public static final DeferredHolder<Item, BlockItem> EDITOR_ITEM = ITEMS.register(
            "rocket_editor", () -> new BlockItem(EDITOR.get(), new Item.Properties()));
    /** Historical block-entity identity; saves opaque records without interpreting their schema. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ArchivedEditorBlockEntity>>
            EDITOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("rocket_editor",
                    () -> BlockEntityType.Builder.of(ArchivedEditorBlockEntity::new, EDITOR.get()).build(null));
    /** Historical entity identity; its client renderer must be a no-op. */
    public static final DeferredHolder<EntityType<?>, EntityType<ArchivedAssemblyEntity>> ASSEMBLY =
            ENTITIES.register("rocket_assembly", () -> EntityType.Builder
                    .<ArchivedAssemblyEntity>of(ArchivedAssemblyEntity::new, MobCategory.MISC)
                    .sized(0.01f, 0.01f).clientTrackingRange(12).updateInterval(20)
                    .build("astraengine:rocket_assembly"));

    private ArchivedConstruction() {}

    /** Registers compatibility identities once on the mod event bus, before registries are frozen. */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        ENTITIES.register(bus);
    }
}
