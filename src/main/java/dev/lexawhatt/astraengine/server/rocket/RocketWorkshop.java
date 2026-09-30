package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.rocket.RegisterRocketPartsEvent;
import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload;
import dev.lexawhatt.astraengine.network.RocketEditorStateReceivedEvent;
import dev.lexawhatt.astraengine.rocket.RocketBuiltins;
import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Native registrations and immutable part definitions. Runtime editing state belongs to player attachments. */
public final class RocketWorkshop {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, AstraEngine.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, AstraEngine.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AstraEngine.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, AstraEngine.MOD_ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AstraEngine.MOD_ID);
    public static final DeferredHolder<Block, RocketEditorBlock> EDITOR = BLOCKS.register("rocket_editor", () -> new RocketEditorBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f).sound(SoundType.METAL)));
    public static final DeferredHolder<Item, BlockItem> EDITOR_ITEM = ITEMS.register("rocket_editor", () -> new BlockItem(EDITOR.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RocketEditorBlockEntity>> EDITOR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "rocket_editor", () -> BlockEntityType.Builder.of(RocketEditorBlockEntity::new, EDITOR.get()).build(null));
    public static final DeferredHolder<EntityType<?>, EntityType<RocketAssemblyEntity>> ASSEMBLY = ENTITIES.register("rocket_assembly", () -> EntityType.Builder
            .<RocketAssemblyEntity>of(RocketAssemblyEntity::new, MobCategory.MISC).sized(0.01f, 0.01f).clientTrackingRange(12).updateInterval(1)
            .build("astraengine:rocket_assembly"));
    /** Nonserialized, noncopied own-player session; no token, host or player reference survives logout/restart. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Optional<RocketEditorSessions.Session>>> EDIT_SESSION = ATTACHMENTS.register(
            "rocket_editor_session", () -> AttachmentType.<Optional<RocketEditorSessions.Session>>builder(Optional::empty).build());
    private static RocketCatalog catalog;
    private RocketWorkshop() {}

    /** Registers native game types and one pre-world definition phase on the mod event bus. Call once. */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); BLOCK_ENTITIES.register(bus); ENTITIES.register(bus); ATTACHMENTS.register(bus);
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            if (catalog != null) { throw new IllegalStateException("Rocket catalog initialized twice"); }
            RegisterRocketPartsEvent registration = new RegisterRocketPartsEvent(RocketBuiltins.definitions());
            ModLoader.postEvent(registration); catalog = registration.freeze();
        }));
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) { event.accept(EDITOR_ITEM.get()); }
        });
    }

    /** Immutable definitions installed by matching mods during native setup; contains no world/session state. */
    public static RocketCatalog catalog() {
        if (catalog == null) { throw new IllegalStateException("Rocket part catalog is unavailable before common setup"); }
        return catalog;
    }

    /** Registers logical-server session expiry once; attachments are never serialized or copied to respawned players. */
    public static void registerEvents() {
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player) { RocketEditorSessions.tick(player); }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> event.getEntity().removeData(EDIT_SESSION));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> event.getEntity().removeData(EDIT_SESSION));
    }

    /** MAIN-thread own-player commands and server-authorized UI snapshots; common code never loads client classes. */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(RocketEditorStatePayload.TYPE, RocketEditorStatePayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new RocketEditorStateReceivedEvent(payload)));
        event.registrar("1").playToServer(RocketEditorCommandPayload.TYPE, RocketEditorCommandPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) { RocketEditorSessions.handle(player, payload); }
        });
    }
}
