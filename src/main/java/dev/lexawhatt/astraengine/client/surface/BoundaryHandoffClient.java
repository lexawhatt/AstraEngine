package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.network.BoundaryHandoffPayload;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.PlanetaryLevelView;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Prepared geographic handoff owned by one actual client connection. Host level changes use server-observed
 * local sections immediately, while normal chunk packets replace them. Only this validated handoff suppresses
 * the host's intermediary waiting frame. Ordinary travel, death and unprepared respawns retain host behavior.
 */
public final class BoundaryHandoffClient {
    private Prepared pending;
    private EarthBoundarySnapshot snapshot;
    private long expiresAt;
    private boolean installed;

    /** Main client thread; receives only a matching already acknowledged local section observation. */
    public void prepare(BoundaryHandoffPayload payload, EarthBoundarySnapshot view) {
        var game = Minecraft.getInstance();
        if (payload.cancelled()) { clear(); return; }
        if (game.level == null || view == null || !view.complete() || view.revision() != payload.revision()
                || !game.level.dimension().location().toString().equals(payload.source().dimensionId())
                || !view.source().equals(payload.source())
                || view.sections().stream().noneMatch(section -> section.chart().equals(payload.target()))) { return; }
        pending = new Prepared(net.minecraft.resources.ResourceLocation.parse(payload.target().dimensionId()),
                payload.target(), payload.feet(), payload.velocity(), payload.orientation());
        snapshot = view; installed = false;
        expiresAt = System.nanoTime() + 5_000_000_000L;
    }

    /** Uses the same real-section bridge for a prepared free surface/space boundary, with no guided camera path. */
    public void prepare(dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffPayload payload, EarthBoundarySnapshot view) {
        var game = Minecraft.getInstance();
        if (payload.cancelled()) { clear(); return; }
        if (game.level == null || !game.level.dimension().location().equals(payload.source())) { return; }
        if (payload.chart() != null && (view == null || !view.complete() || view.revision() != payload.revision()
                || !view.source().equals(payload.chart()) || !view.visibleFrom(payload.chart(), payload.feet()))) { return; }
        pending = new Prepared(payload.target(), payload.chart(), payload.feet(), payload.velocity(), payload.orientation());
        snapshot = view; installed = false; expiresAt = System.nanoTime() + 5_000_000_000L;
    }

    /** A bounded prepared view exists for exactly this imminent host destination. */
    public boolean canBridge(ResourceKey<Level> target) {
        return pending != null && System.nanoTime() <= expiresAt && target != null
                && target.location().toString().equals(pending.dimension().toString());
    }

    /** Runs after the host has created its new player and Level, before any frame can show a zero-position camera. */
    public void install() {
        var game = Minecraft.getInstance();
        if (game.level == null || game.player == null || !canBridge(game.level.dimension()) || installed) { return; }
        var position = pending.feet();
        game.player.absMoveTo(position.x(), position.y(), position.z(), pending.orientation().yaw(), pending.orientation().pitch());
        game.player.setOldPosAndRot();
        game.player.setDeltaMovement(new Vec3(pending.velocity().x(), pending.velocity().y(), pending.velocity().z()));
        ((PlanetaryLevelView) game.level).astra$geography(pending.chart(), snapshot);
        if (pending.chart() != null) { seed(game.level); }
        installed = true;
        if (game.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen) { game.setScreen(null); }
    }

    /** Restores transferred velocity after the host's absolute position packet clears its old-world components. */
    public void moved() {
        var game = Minecraft.getInstance();
        if (installed && game.level != null && game.player != null && canBridge(game.level.dimension())) {
            game.player.setDeltaMovement(new Vec3(pending.velocity().x(), pending.velocity().y(), pending.velocity().z()));
            game.player.setOldPosAndRot();
            clear();
        }
    }

    private void seed(ClientLevel level) {
        var chunks = new LinkedHashMap<ChunkPos, LevelChunk>();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var cache = level.getChunkSource();
        cache.updateViewCenter((int) Math.floor(pending.feet().x()) >> 4, (int) Math.floor(pending.feet().z()) >> 4);
        for (var observed : snapshot.sections()) {
            if (!observed.chart().equals(pending.chart())) { continue; }
            var pos = observed.section();
            var chunk = chunks.computeIfAbsent(new ChunkPos(pos.x(), pos.z()), key -> new LevelChunk(level, key));
            var section = chunk.getSection(level.getSectionIndexFromSectionY(pos.y()));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, Block.stateById(observed.state((y * 16 + z) * 16 + x)), false);
                    }
                }
            }
            section.fillBiomesFromNoise((x, y, z, sampler) -> biomes.getHolder(observed.biome(
                    ((y & 3) * 4 + (z & 3)) * 4 + (x & 3))).orElseThrow(), null,
                    pos.minBlockX() >> 2, pos.minBlockY() >> 2, pos.minBlockZ() >> 2);
        }
        for (var entry : chunks.entrySet()) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                for (var section : entry.getValue().getSections()) { section.write(buffer); }
                cache.replaceWithPacketData(entry.getKey().x, entry.getKey().z, buffer, new CompoundTag(), ignored -> {});
            } finally { buffer.release(); }
        }
        for (var observed : snapshot.sections()) {
            if (!observed.chart().equals(pending.chart())) { continue; }
            var sky = new DataLayer(); var block = new DataLayer();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int light = observed.light((y * 16 + z) * 16 + x);
                        sky.set(x, y, z, light >> 4); block.set(x, y, z, light & 15);
                    }
                }
            }
            SectionPos pos = observed.section();
            cache.getLightEngine().queueSectionData(LightLayer.SKY, pos, sky);
            cache.getLightEngine().queueSectionData(LightLayer.BLOCK, pos, block);
            cache.getLightEngine().setLightEnabled(new ChunkPos(pos.x(), pos.z()), true);
            level.setSectionDirtyWithNeighbors(pos.x(), pos.y(), pos.z());
        }
    }

    private record Prepared(net.minecraft.resources.ResourceLocation dimension,
            dev.lexawhatt.astraengine.surface.CubeStorageChart chart,
            dev.lexawhatt.astraengine.cosmos.SpaceVector feet, dev.lexawhatt.astraengine.cosmos.SpaceVector velocity,
            dev.lexawhatt.astraengine.cosmos.FlightOrientation orientation) { }

    private void clear() { pending = null; snapshot = null; installed = false; expiresAt = 0; }
}
