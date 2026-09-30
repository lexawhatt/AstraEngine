package dev.lexawhatt.astraengine.client.compat;

import dev.lexawhatt.astraengine.AstraEngine;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Optional render integration through loaded-mod metadata and the Iris public API only.
 * Call on the client game/render thread after mod loading. Pack and shadow state are sampled on
 * every query, including after shader toggles, reloads and dimension changes. No world, connection,
 * shader setting, framebuffer, pipeline or GPU resource is retained or modified by this adapter.
 */
public final class RenderCompatibility {
    private static final Probe ABSENT = new Probe(IrisState.ABSENT, -1, false, false, "");
    private static volatile Probe failed;

    private RenderCompatibility() {
    }

    /**
     * True while a shader pack owns rendering. An installed but incompatible Iris API returns true
     * conservatively, so unknown foreign attachments never receive the ordinary Astra world passes.
     * diagnostics() distinguishes an actual active pack from unavailable API state.
     */
    public static boolean shaderPackActive() { return probe().packActive(); }

    /**
     * True during Iris shadow extraction. An incompatible installed API also returns true to suppress
     * custom rendering until the client is restarted with a supported API. Absent Iris returns false.
     */
    public static boolean shadowPass() { return probe().shadowPass(); }

    /** Whether owned visual composition must use the late world stage instead of ordinary host stages. */
    public static boolean lateWorldPasses() { return shaderPackActive(); }

    /**
     * Captures immutable installed versions and actual API facts for diagnostics on the client thread.
     * Pack/shadow/revision values are empty when unavailable; conservative fallback is never presented
     * as an observed active shader pack. Versions come from loaded metadata rather than file names.
     */
    public static Diagnostics diagnostics() {
        Probe current = probe();
        boolean available = current.state() == IrisState.AVAILABLE;
        return new Diagnostics(List.of(installed("sodium", "Sodium"), installed("iris", "Iris"),
                installed("sodium_extra", "Sodium Extra"), installed("reeses_sodium_options", "Reese's Sodium Options"),
                installed("chloride", "Chloride"), installed("distanthorizons", "Distant Horizons")), current.state(),
                available ? OptionalInt.of(current.minorRevision()) : OptionalInt.empty(),
                available ? Optional.of(current.packActive()) : Optional.empty(),
                available ? Optional.of(current.shadowPass()) : Optional.empty(), current.failure());
    }

    /** Compact English log summary; no shader setting is changed or compatibility guarantee inferred. */
    public static String status() {
        Diagnostics snapshot = diagnostics();
        return "Iris=" + snapshot.irisState() + ", API="
                + (snapshot.apiMinorRevision().isPresent() ? snapshot.apiMinorRevision().getAsInt() : "unavailable")
                + ", pack=" + snapshot.shaderPackInUse().map(String::valueOf).orElse("unknown")
                + ", shadow=" + snapshot.renderingShadowPass().map(String::valueOf).orElse("unknown")
                + ", conservative=" + snapshot.conservativeMode() + ", mods=["
                + snapshot.mods().stream().map(mod -> mod.id() + "=" + mod.version().orElse("absent"))
                        .collect(Collectors.joining(", ")) + "]"
                + (snapshot.failure().isEmpty() ? "" : ", failure=" + snapshot.failure());
    }

    /** Registers a client-only, read-only subtree; Brigadier merges it with the existing /astra-render root. */
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra-render").then(Commands.literal("compatibility")
                .executes(context -> {
                    Diagnostics snapshot = diagnostics();
                    String state = snapshot.irisState().name().toLowerCase(java.util.Locale.ROOT);
                    Component revision = snapshot.apiMinorRevision().isPresent()
                            ? Component.literal(Integer.toString(snapshot.apiMinorRevision().getAsInt()))
                            : Component.translatable("astraengine.compat.render.unavailable");
                    context.getSource().sendSuccess(() -> Component.translatable("astraengine.compat.render.summary",
                            Component.translatable("astraengine.compat.render." + state), revision,
                            booleanValue(snapshot.shaderPackInUse()), booleanValue(snapshot.renderingShadowPass())), false);
                    for (InstalledMod mod : snapshot.mods()) {
                        context.getSource().sendSuccess(() -> Component.translatable("astraengine.compat.render.mod",
                                mod.displayName(), mod.version().<Component>map(Component::literal)
                                        .orElseGet(() -> Component.translatable("astraengine.compat.render.not_installed"))), false);
                    }
                    if (snapshot.conservativeMode()) {
                        context.getSource().sendFailure(Component.translatable("astraengine.compat.render.incompatible_detail",
                                snapshot.failure()));
                        return 0;
                    }
                    return 1;
                })));
    }

    private static Component booleanValue(Optional<Boolean> value) {
        return value.map(flag -> Component.translatable("astraengine.compat.render." + (flag ? "yes" : "no")))
                .orElseGet(() -> Component.translatable("astraengine.compat.render.unavailable"));
    }

    private static InstalledMod installed(String id, String fallbackName) {
        return ModList.get().getModContainerById(id)
                .map(container -> new InstalledMod(id, container.getModInfo().getDisplayName(),
                        Optional.of(container.getModInfo().getVersion().toString())))
                .orElseGet(() -> new InstalledMod(id, fallbackName, Optional.empty()));
    }

    private static Probe probe() {
        if (!ModList.get().isLoaded("iris")) { return ABSENT; }
        Probe failure = failed;
        if (failure != null) { return failure; }
        try {
            // The optional class and its Iris symbols are resolved only after the loaded-mod check.
            return IrisBridge.probe();
        } catch (LinkageError unavailableApi) {
            return fail(unavailableApi);
        }
    }

    private static synchronized Probe fail(LinkageError cause) {
        if (failed == null) {
            String detail = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            failed = new Probe(IrisState.INCOMPATIBLE, -1, true, true, detail);
            AstraEngine.LOGGER.error("Installed Iris public render API is incompatible; Astra custom world passes are "
                    + "suppressed conservatively. Use /astra-render compatibility for loaded versions. "
                    + "No shader setting was changed.", cause);
        }
        return failed;
    }

    /** Whether the optional public API is absent, callable, or incompatible with its required methods. */
    public enum IrisState { ABSENT, AVAILABLE, INCOMPATIBLE }

    /** Loaded metadata for one tracked mod. An empty version means that the mod is not installed. */
    public record InstalledMod(String id, String displayName, Optional<String> version) {
        public InstalledMod {
            if (id == null || id.isBlank() || displayName == null || displayName.isBlank() || version == null) {
                throw new IllegalArgumentException("Render mod diagnostics require an ID, name and optional version");
            }
        }
    }

    /** Immutable diagnostic facts. Unavailable API fields are empty, not guessed from the installed mod list. */
    public record Diagnostics(List<InstalledMod> mods, IrisState irisState, OptionalInt apiMinorRevision,
            Optional<Boolean> shaderPackInUse, Optional<Boolean> renderingShadowPass, String failure) {
        public Diagnostics {
            if (mods == null || mods.stream().anyMatch(mod -> mod == null) || irisState == null
                    || apiMinorRevision == null || shaderPackInUse == null || renderingShadowPass == null || failure == null) {
                throw new IllegalArgumentException("Render compatibility diagnostics must not contain null values");
            }
            mods = List.copyOf(mods);
        }

        /** Unknown installed APIs disable custom world passes conservatively and log one actionable error. */
        public boolean conservativeMode() { return irisState == IrisState.INCOMPATIBLE; }
    }

    record Probe(IrisState state, int minorRevision, boolean packActive, boolean shadowPass, String failure) {}
}
