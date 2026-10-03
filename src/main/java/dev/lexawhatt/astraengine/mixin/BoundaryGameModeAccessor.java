package dev.lexawhatt.astraengine.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Initializes isolated neighboring mining progress without broadcasting a real game-mode change. */
@Mixin(ServerPlayerGameMode.class)
public interface BoundaryGameModeAccessor {
    @Invoker("setGameModeForPlayer") void astra$actionGameType(GameType current, GameType previous);
    @Accessor("isDestroyingBlock") boolean astra$destroying();
    @Accessor("hasDelayedDestroy") boolean astra$delayedDestroy();
}
