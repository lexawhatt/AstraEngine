package dev.lexawhatt.astraengine.client.solar;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import java.util.EnumMap;
import java.util.EnumSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-thread cinematic sonification of the shared Sol state. Owns only its four sound voices;
 * the host owns decoded audio resources and MASTER/AMBIENT gain. No simulation or packet authority.
 */
public final class SolarAudioController implements ResourceManagerReloadListener {
    private final SolarStateClient solar;
    private final RocketController rocket;
    private final SolarAudioEnvelope envelope = new SolarAudioEnvelope();
    private final EnumMap<Cue, Voice> voices = new EnumMap<>(Cue.class);
    private final EnumSet<Cue> unavailable = EnumSet.noneOf(Cue.class);
    private boolean enabled = true;
    private float volume = 0.75f;
    private long impactsPlayed;
    private long observedCycle = -1;
    private String observedContext;

    /** Shares only the accepted server snapshots and virtual camera coordinates of this connection. */
    public SolarAudioController(SolarStateClient solar, RocketController rocket) {
        if (solar == null || rocket == null) { throw new IllegalArgumentException("Solar audio services must not be null"); }
        this.solar = solar;
        this.rocket = rocket;
    }

    public boolean enabled() { return enabled; }
    public float volume() { return volume; }
    /** Controller-owned pending/playing voices, useful for checking lifecycle cleanup without inspecting OpenAL. */
    public int activeVoices() { return voices.size(); }
    /** Impact playback submissions in this connection; this is not proof of audible device output. */
    public long impactsPlayed() { return impactsPlayed; }

    /** Follows the latest server phase on the client game thread; it never accumulates phase time. */
    public void tick(ClientTickEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        String context = context(game);
        var snapshot = solar.snapshot();
        boolean listening = enabled && volume > 0 && context != null && !game.isPaused()
                && game.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                && game.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
        if (snapshot == null || context == null || observedCycle != snapshot.cycle()
                || !context.equals(observedContext)) {
            stopVoices();
        }
        observedContext = context;
        observedCycle = snapshot == null ? -1 : snapshot.cycle();
        SolarAudioEnvelope.Frame frame = envelope.observe(snapshot, context, listening);
        double meters = rocket.active() ? rocket.visualPosition().length() : SolarAudioEnvelope.ASTRONOMICAL_UNIT_METERS;
        float gain = volume * SolarAudioEnvelope.distanceGain(meters);
        updateLoop(Cue.TENSION, frame.tension() * gain);
        updateLoop(Cue.COLLAPSE, frame.collapse() * gain);
        updateLoop(Cue.RUMBLE, frame.rumble() * gain);
        if (frame.triggerImpact() && gain > 0) {
            stopVoice(Cue.IMPACT);
            voices.put(Cue.IMPACT, play(Cue.IMPACT, frame.impact() * gain));
            impactsPlayed++;
        }
        Voice impact = voices.get(Cue.IMPACT);
        if (impact != null) {
            if (frame.impact() <= 0 || impact.isStopped()) { stopVoice(Cue.IMPACT); }
            else { impact.setGain(frame.impact() * gain); }
        }
    }

    private String context(Minecraft game) {
        if (game.level == null || game.player == null || !game.player.isAlive()) { return null; }
        if (game.level.dimension().equals(Level.OVERWORLD)) { return "overworld"; }
        if (rocket.active() && rocket.snapshot().systemId().equals("sol") && !rocket.snapshot().interstellarJump()) {
            // Local guidance changes input epochs without moving the observer to a new audio context.
            return "sol";
        }
        return null;
    }

    private void updateLoop(Cue cue, float gain) {
        if (gain <= 0.0001f) { stopVoice(cue); unavailable.remove(cue); return; }
        if (unavailable.contains(cue)) { return; }
        Voice voice = voices.get(cue);
        if (voice == null) { voices.put(cue, play(cue, gain)); }
        else {
            voice.setGain(gain);
            // Loading streamed buffers is asynchronous. Give the host three seconds before
            // recovering an expired channel, and retry once per continuous cue to avoid
            // continually replaying missing/broken resource-pack audio.
            if (++voice.observedTicks > 60 && !Minecraft.getInstance().getSoundManager().isActive(voice)) {
                stopVoice(cue);
                if (voice.retried) { unavailable.add(cue); }
                else {
                    Voice replacement = play(cue, gain);
                    replacement.retried = true;
                    voices.put(cue, replacement);
                }
            }
        }
    }

    private Voice play(Cue cue, float gain) {
        Voice voice = new Voice(cue, gain);
        Minecraft.getInstance().getSoundManager().play(voice);
        return voice;
    }

    private void stopVoice(Cue cue) {
        Voice voice = voices.remove(cue);
        if (voice != null) {
            voice.finish();
            Minecraft.getInstance().getSoundManager().stop(voice);
        }
    }

    private void stopVoices() {
        for (Cue cue : Cue.values()) { stopVoice(cue); }
        unavailable.clear();
    }

    /** Stops voices and breaks event continuity on the reload apply thread; the next snapshot is only a baseline. */
    @Override
    public void onResourceManagerReload(ResourceManager resources) {
        stopVoices();
        envelope.interrupt();
        observedContext = null;
    }

    /** Disconnect is the end of this observer lifetime; resources themselves remain host-owned. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopVoices();
        envelope.disconnect();
        observedCycle = -1;
        observedContext = null;
        impactsPlayed = 0;
    }

    /** Session-local audio controls, independent of the host AMBIENT and MASTER sliders. */
    public void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra-audio")
                .then(Commands.literal("enabled").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> {
                            enabled = BoolArgumentType.getBool(context, "enabled");
                            stopVoices(); envelope.interrupt();
                            return status(context.getSource());
                        })))
                .then(Commands.literal("volume").then(Commands.argument("value", FloatArgumentType.floatArg(0, 1))
                        .executes(context -> {
                            volume = FloatArgumentType.getFloat(context, "value");
                            if (volume == 0) { stopVoices(); envelope.interrupt(); }
                            return status(context.getSource());
                        })))
                .then(Commands.literal("status").executes(context -> status(context.getSource()))));
    }

    private int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("astraengine.audio.status", enabled, volume), false);
        return 1;
    }

    private enum Cue {
        TENSION("solar_tension"), COLLAPSE("solar_collapse"), IMPACT("solar_supernova"), RUMBLE("solar_rumble");
        private final ResourceLocation id;
        Cue(String id) { this.id = ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, id); }
    }

    private static final class Voice extends AbstractTickableSoundInstance {
        private int remainingTicks;
        private int observedTicks;
        private boolean retried;
        private Voice(Cue cue, float gain) {
            super(SoundEvent.createVariableRangeEvent(cue.id), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            looping = cue != Cue.IMPACT;
            relative = true;
            attenuation = Attenuation.NONE;
            remainingTicks = cue == Cue.IMPACT ? 80 : Integer.MAX_VALUE;
            setGain(gain);
        }
        private void setGain(float gain) { volume = Math.clamp(gain, 0, 1); }
        private void finish() { stop(); }
        @Override
        public void tick() {
            if (!looping && --remainingTicks <= 0) { stop(); }
        }
    }
}
