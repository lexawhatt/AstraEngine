package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the host drain generation handoffs before retrying a not-yet-saveable chart chunk. In 1.21.1 a completed
 * save future plus a reacquired generation reference otherwise re-enqueues itself forever in one unload tick.
 * The host's pending holder, save routine and block/NBT ownership remain unchanged.
 */
@Mixin(ChunkMap.class)
abstract class PlanetaryChunkUnloadMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow private void scheduleUnload(long chunkPosition, ChunkHolder holder) { throw new AssertionError(); }
    @Unique private final Map<Long, ChunkHolder> astra$deferredUnloads = new LinkedHashMap<>();

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true)
    private void astra$deferBusyGeneration(long position, ChunkHolder holder, CallbackInfo callback) {
        var generator = level.getChunkSource().getGenerator();
        if ((generator instanceof EarthChunkGenerator || generator instanceof PlanetChunkGenerator)
                && holder.getSaveSyncFuture().isDone() && !holder.isReadyForSaving()) {
            astra$deferredUnloads.putIfAbsent(position, holder);
            callback.cancel();
        }
    }

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void astra$retryAfterHostGeneration(BooleanSupplier hasMoreTime, CallbackInfo callback) {
        if (astra$deferredUnloads.isEmpty()) { return; }
        var deferred = new ArrayList<Map.Entry<Long, ChunkHolder>>(Math.min(256, astra$deferredUnloads.size()));
        var iterator = astra$deferredUnloads.entrySet().iterator();
        for (int index = 0; index < 256 && iterator.hasNext(); index++) {
            var entry = iterator.next();
            deferred.add(Map.entry(entry.getKey(), entry.getValue()));
            iterator.remove();
        }
        for (var entry : deferred) {
            if (pendingUnloads.get(entry.getKey().longValue()) != entry.getValue()) { continue; }
            if (entry.getValue().isReadyForSaving()) { scheduleUnload(entry.getKey(), entry.getValue()); }
            else { astra$deferredUnloads.put(entry.getKey(), entry.getValue()); }
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void astra$releaseDeferredObservations(CallbackInfo callback) { astra$deferredUnloads.clear(); }
}
