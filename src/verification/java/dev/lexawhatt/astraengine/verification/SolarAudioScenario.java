package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import java.nio.file.Files;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Observes real host sound requests, decoded source channels and cleanup in a disposable solar-cycle world. */
final class SolarAudioScenario {
    private static final String IMPACT = "solar_supernova";
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Queue<SoundInstance> requests = new ConcurrentLinkedQueue<>();
    private final Queue<SoundInstance> sources = new ConcurrentLinkedQueue<>();
    private final Queue<String> events = new ConcurrentLinkedQueue<>();
    private final long started = System.nanoTime();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private StellarEvolutionSnapshot snapshot;
    private int step;
    private int ticks;
    private long tensionBeforeResume;
    private long cycle;

    SolarAudioScenario() {
        NeoForge.EVENT_BUS.addListener((SolarReceivedEvent event) -> snapshot = event.payload().snapshot());
        NeoForge.EVENT_BUS.addListener((PlaySoundEvent event) -> observe("request", event.getOriginalSound(), requests));
        NeoForge.EVENT_BUS.addListener((PlaySoundSourceEvent event) -> observe("buffered_source", event.getSound(), sources));
        NeoForge.EVENT_BUS.addListener((PlayStreamingSourceEvent event) -> observe("streaming_source", event.getSound(), sources));
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
        minecraft.options.getSoundSourceOptionInstance(SoundSource.AMBIENT).set(1.0);
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
        AstraEngine.LOGGER.info("ASTRA_SOLAR_AUDIO_BEGIN {}", minecraft.getSoundManager().getDebugString());
    }

    boolean disconnected() { return step >= 18; }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        require(ticks < 1600, "Solar audio step timed out");
        switch (step) {
            case 0 -> {
                if (snapshot == null) { return false; }
                require(!snapshot.running(), "Audio fixture did not start with an idle diagnostic Sun");
                server(this::prepareHome);
                command("astra-audio enabled true");
                command("astra-audio volume 0.6");
                command("astra sun demo 10");
                next();
            }
            case 1 -> {
                if (snapshot.phase() != Phase.DISTENDED || count(sources, "solar_tension") == 0) { return false; }
                require(active("solar_tension"), "Tension clip did not remain active on its native audio channel");
                require(count(requests, IMPACT) == 0, "Impact played before supernova");
                tensionBeforeResume = count(requests, "solar_tension");
                command("astra sun pause");
                next();
            }
            case 2 -> {
                if (ticks < 25 || snapshot.running()) { return false; }
                requireStopped("Pause");
                command("astra sun resume");
                next();
            }
            case 3 -> {
                if (ticks < 15 || count(requests, "solar_tension") <= tensionBeforeResume) { return false; }
                require(active("solar_tension"), "Resumed tension did not reach the native sound engine");
                command("astra visit alpha");
                next();
            }
            case 4 -> {
                if (!in("astraengine:alpha") || ticks < 25) { return false; }
                requireStopped("Leaving the Sol observation context");
                require(count(requests, IMPACT) == 0, "Leaving Sol emitted a stale impact");
                command("astra leave");
                next();
            }
            case 5 -> {
                if (!in("minecraft:overworld") || ticks < 15) { return false; }
                require(count(requests, "solar_tension") > tensionBeforeResume + 1,
                        "Returning to an evolving Sun did not recover its tension bed");
                next();
            }
            case 6 -> {
                if (snapshot.phase() != Phase.COLLAPSING || count(sources, "solar_collapse") == 0) { return false; }
                require(active("solar_collapse"), "Collapse sound did not reach its native source");
                require(count(requests, IMPACT) == 0, "Collapse emitted impact before the event boundary");
                next();
            }
            case 7 -> {
                if (snapshot.phase() != Phase.SUPERNOVA || count(sources, IMPACT) == 0
                        || count(sources, "solar_rumble") == 0) { return false; }
                requireSingleImpact("Observed collapse-to-supernova boundary");
                cycle = snapshot.cycle();
                command("astra sun pause");
                next();
            }
            case 8 -> {
                if (ticks < 25 || snapshot.running()) { return false; }
                requireStopped("Supernova pause");
                requireSingleImpact("Repeated paused snapshots");
                command("astra sun resume");
                next();
            }
            case 9 -> {
                if (ticks < 25) { return false; }
                require(active("solar_rumble"), "Supernova resume did not restore its quiet rumble bed");
                requireSingleImpact("Resume inside supernova");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 10 -> {
                if (ticks < 25) { return false; }
                requireSingleImpact("Resource reload inside supernova");
                command("astra-audio enabled false");
                next();
            }
            case 11 -> {
                if (ticks < 25) { return false; }
                requireStopped("Client audio toggle");
                command("astra-audio enabled true");
                next();
            }
            case 12 -> {
                if (ticks < 20) { return false; }
                requireSingleImpact("Client audio re-enable inside supernova");
                require(active("solar_rumble"), "Re-enabling audio did not restore its quiet rumble bed");
                command("astra visit alpha");
                next();
            }
            case 13 -> {
                if (!in("astraengine:alpha") || ticks < 25) { return false; }
                requireStopped("Late supernova context departure");
                command("astra leave");
                next();
            }
            case 14 -> {
                if (!in("minecraft:overworld") || ticks < 15) { return false; }
                require(snapshot.cycle() == cycle && snapshot.phase() == Phase.SUPERNOVA,
                        "Late-entry scenario did not return inside the existing supernova");
                requireSingleImpact("Late entry inside the existing cycle");
                command("astra sun reset");
                next();
            }
            case 15 -> {
                if (ticks < 25 || snapshot.running()) { return false; }
                require(snapshot.phase() == Phase.STABLE && snapshot.remaining() == StellarEvolutionSnapshot.CAPACITY,
                        "Solar reset did not restore the idle model");
                requireStopped("Solar reset");
                tensionBeforeResume = count(requests, "solar_tension");
                command("astra sun demo 10");
                next();
            }
            case 16 -> {
                if (snapshot.phase() != Phase.DISTENDED || count(requests, "solar_tension") <= tensionBeforeResume) {
                    return false;
                }
                require(snapshot.cycle() != cycle && active("solar_tension"), "New cycle did not create its own active voice");
                requireSingleImpact("Starting a later cycle before its explosion");
                next();
            }
            case 17 -> {
                // Match the host pause-menu order: close the level connection before joining server shutdown.
                next();
                minecraft.level.disconnect();
                minecraft.disconnect(new TitleScreen());
            }
            case 18 -> {
                if (ticks < 25) { return false; }
                require(minecraft.level == null && minecraft.player == null, "Audio logout fixture retained a client world");
                requireStopped("Actual client logout");
                requireSingleImpact("Logout");
                StringBuilder report = new StringBuilder("elapsed_ms,kind,sound,loop,relative,category,volume\n");
                events.forEach(value -> report.append(value).append('\n'));
                Files.createDirectories(minecraft.gameDirectory.toPath().resolve("evidence"));
                Files.writeString(minecraft.gameDirectory.toPath().resolve("evidence/solar-audio-events.csv"), report.toString());
                AstraEngine.LOGGER.info("ASTRA_SOLAR_AUDIO_PASSED requests={} decodedSources={} impacts={}",
                        requests.size(), sources.size(), count(sources, IMPACT));
                return true;
            }
            default -> throw new IllegalStateException("Unexpected solar audio step " + step);
        }
        return false;
    }

    private void observe(String kind, SoundInstance sound, Queue<SoundInstance> destination) {
        if (!sound.getLocation().getNamespace().equals(AstraEngine.MOD_ID)
                || !sound.getLocation().getPath().startsWith("solar_")) { return; }
        destination.add(sound);
        events.add((System.nanoTime() - started) / 1_000_000 + "," + kind + "," + sound.getLocation()
                + "," + sound.isLooping() + "," + sound.isRelative() + "," + sound.getSource() + ","
                + (kind.equals("request") ? "unresolved" : Float.toString(sound.getVolume())));
        AstraEngine.LOGGER.info("ASTRA_SOLAR_AUDIO_EVENT kind={} id={} loop={} category={}",
                kind, sound.getLocation(), sound.isLooping(), sound.getSource());
    }

    private long count(Queue<SoundInstance> sounds, String id) {
        return sounds.stream().filter(sound -> sound.getLocation().getPath().equals(id)).count();
    }
    private boolean active(String id) {
        return requests.stream().anyMatch(sound -> sound.getLocation().getPath().equals(id)
                && minecraft.getSoundManager().isActive(sound));
    }
    private void requireStopped(String reason) {
        require(requests.stream().noneMatch(sound -> minecraft.getSoundManager().isActive(sound)),
                reason + " left a controller-owned sound active");
        AstraEngine.LOGGER.info("ASTRA_SOLAR_AUDIO_STOPPED reason={}", reason);
    }
    private void requireSingleImpact(String reason) {
        require(count(requests, IMPACT) == 1 && count(sources, IMPACT) == 1,
                reason + " changed the one-impact playback count");
    }
    private boolean in(String id) { return minecraft.level != null && minecraft.level.dimension().location().toString().equals(id); }
    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void prepareHome(MinecraftServer server) {
        server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.overworld().setDayTime(1000);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_SOLAR_AUDIO_STEP {} complete phase={}", step, snapshot == null ? "none" : snapshot.phase());
        step++;
        ticks = 0;
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (solar audio step " + step + ", ticks " + ticks + ")"); }
    }
}
