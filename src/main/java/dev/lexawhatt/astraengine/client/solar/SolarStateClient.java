package dev.lexawhatt.astraengine.client.solar;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Connection-owned interpolation shared by Overworld sky, visual lightmap and the Sol flight renderer. */
public final class SolarStateClient {
    private StellarEvolutionSnapshot snapshot;
    private float previousDepletion;
    private float previousPhaseTicks;
    private long receivedAt;
    private boolean hud = true;

    /** Accepts only non-stale server snapshots. Phase and reset boundaries discard the old phase age. */
    public void receive(SolarReceivedEvent event) {
        StellarEvolutionSnapshot next = event.payload().snapshot();
        if (snapshot != null && next.revision() < snapshot.revision()) { return; }
        boolean reset = snapshot == null || snapshot.cycle() != next.cycle();
        previousDepletion = reset ? fraction(next) : depletion();
        previousPhaseTicks = reset || snapshot.phase() != next.phase() ? next.phaseTicks() : phaseTicks();
        snapshot = next;
        receivedAt = System.nanoTime();
    }

    public StellarEvolutionSnapshot snapshot() { return snapshot; }

    /** Rendering may lag by one snapshot interval, but never advances authoritative phase time. */
    public SolarVisual visual() {
        return snapshot == null ? SolarVisual.HEALTHY : SolarVisual.from(depletion(), snapshot.phase(), phaseTicks());
    }

    private float depletion() {
        if (snapshot == null) { return 0; }
        return lerp(previousDepletion, fraction(snapshot));
    }
    private float phaseTicks() { return snapshot == null ? 0 : lerp(previousPhaseTicks, snapshot.phaseTicks()); }
    private float lerp(float start, float end) {
        float fraction = (float) Math.clamp((System.nanoTime() - receivedAt) / 250_000_000.0, 0, 1);
        return start + (end - start) * fraction;
    }
    private static float fraction(StellarEvolutionSnapshot value) {
        return 1 - (float) value.remaining() / StellarEvolutionSnapshot.CAPACITY;
    }

    /** A small optional status display appears while the operator's solar scenario has state to inspect. */
    public void hud(RenderGuiEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        if (!hud || snapshot == null || game.level == null || !game.level.dimension().equals(Level.OVERWORLD)
                || game.options.hideGui || game.screen != null
                || snapshot.phase() == Phase.STABLE && snapshot.extracted() == 0 && !snapshot.running()) { return; }
        var graphics = event.getGuiGraphics();
        int right = graphics.guiWidth() - 12;
        int left = Math.max(12, right - 202);
        graphics.fill(left, 12, right, 49, 0xAD07101A);
        String phase = "astraengine.solar.phase." + snapshot.phase().name().toLowerCase(Locale.ROOT);
        graphics.drawString(game.font, Component.translatable("astraengine.solar.hud", Component.translatable(phase)),
                left + 7, 18, 0xFFFFD69C);
        Component state = Component.translatable(snapshot.running() ? "astraengine.solar.running" : "astraengine.solar.paused");
        graphics.drawString(game.font, Component.translatable("astraengine.solar.energy",
                String.format(Locale.ROOT, "%.1f", (1 - depletion()) * 100), state), left + 7, 32, 0xFFC7D8E5);
    }

    public void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra-sky")
                .then(Commands.literal("hud").then(Commands.argument("visible", BoolArgumentType.bool())
                        .executes(context -> { hud = BoolArgumentType.getBool(context, "visible"); return 1; }))));
    }

    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        snapshot = null; previousDepletion = 0; previousPhaseTicks = 0; receivedAt = 0;
    }
}
