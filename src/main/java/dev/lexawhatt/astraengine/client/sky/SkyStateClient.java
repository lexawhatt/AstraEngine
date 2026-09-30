package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.network.SkyProfileReceivedEvent;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import dev.lexawhatt.astraengine.sky.SkySample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Connection-owned sky settings; time comes from Minecraft's existing server time synchronization. */
public final class SkyStateClient {
    private PlanetarySkyProfile profile = PlanetarySkyProfile.DEFAULT;
    private long revision = -1;
    private float localPollution;
    private float targetPollution;
    private int sampleTicks;
    private ClientLevel sampledLevel;

    /** Accepts server-authored immutable settings on the client thread. Reload does not clear them. */
    public void receive(SkyProfileReceivedEvent event) {
        var payload = event.payload();
        if (payload.revision() < revision) { return; }
        profile = payload.profile();
        revision = payload.revision();
    }

    /** Current validated connection profile; callers cannot mutate it. */
    public PlanetarySkyProfile profile() { return profile; }

    /** Last accepted revision, or -1 before the first server snapshot. */
    public long revision() { return revision; }

    /** Samples host day time; paused/frozen time has no partial-tick orbital drift. */
    public SkySample sample(ClientLevel level, float partialTick) {
        boolean advancing = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)
                && level.tickRateManager().runsNormally() && !Minecraft.getInstance().isPaused();
        // NeoForge uses -1 as the vanilla-rate sentinel, not as backwards time.
        double rate = level.getDayTimePerTick() < 0 ? 1 : level.getDayTimePerTick();
        double fraction = advancing ? level.getDayTimeFraction() + Math.clamp(partialTick, 0, 1) * rate : 0;
        if (!Double.isFinite(fraction)) { fraction = 0; }
        // Reduce before adding optional NeoForge accelerated-time fractions, retaining precision at long epochs.
        long yearTicks = (long) profile.yearDays() * 24_000;
        fraction %= yearTicks;
        long whole = (long) Math.floor(fraction);
        return SkyEphemeris.sample(profile, Math.floorMod(level.getDayTime(), yearTicks) + whole, fraction - whole);
    }

    /** Combined background and nearby emitted-light pollution in [0,1], presentation only. */
    public float pollution() {
        return (float) (1 - (1 - profile.lightPollution()) * (1 - localPollution));
    }

    /** Samples at most 75 loaded block-light cells every ten ticks, without loading any chunks. */
    public void tick(ClientTickEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        ClientLevel level = game.level;
        if (level != sampledLevel) {
            sampledLevel = level;
            localPollution = 0;
            targetPollution = 0;
            sampleTicks = 0;
        }
        if (level == null || !level.dimension().equals(Level.OVERWORLD) || game.isPaused()) { return; }
        if (sampleTicks++ % 10 == 0) {
            BlockPos center = BlockPos.containing(game.gameRenderer.getMainCamera().getPosition());
            double sum = 0;
            double weights = 0;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = -1; y <= 1; y++) {
                        BlockPos position = center.offset(x * 4, y * 3, z * 4);
                        if (!level.hasChunkAt(position)) { continue; }
                        double weight = 1.0 / (1 + x * x + y * y + z * z);
                        double light = level.getBrightness(LightLayer.BLOCK, position) / 15.0;
                        sum += light * light * weight;
                        weights += weight;
                    }
                }
            }
            targetPollution = weights == 0 ? 0 : (float) Math.min(0.85, Math.sqrt(sum / weights) * 0.85);
        }
        localPollution += (targetPollution - localPollution) * 0.08f;
    }

    /** Disconnect discards all profile and local-light state, including references to the old level. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        profile = PlanetarySkyProfile.DEFAULT;
        revision = -1;
        sampledLevel = null;
        localPollution = 0;
        targetPollution = 0;
        sampleTicks = 0;
    }
}
