package dev.lexawhatt.astraengine.client.flight;

import dev.lexawhatt.astraengine.cosmos.CosmicRegion;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.PulsarGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Connection-owned, non-pausing public atlas. Selection never mutates discovery or navigation.
 * Chart and aim goes through the owning server; private custom content remains in the ordinary chart.
 */
public final class UniverseAtlasScreen extends Screen {
    private final RocketController controller;
    private List<GalaxyDescriptor> galaxies = List.of();
    private List<CosmicRegion> regions = List.of();
    private CosmosSystem pulsar;
    private int selectedGalaxy;
    private int selectedRegion;
    private int columnX;
    private int columnWidth;
    private Button aim;
    private Button jump;

    /** Opens the public atlas for this connection's navigation controller on the client thread. */
    public UniverseAtlasScreen(RocketController controller) {
        super(text("title"));
        if (controller == null) { throw new IllegalArgumentException("The atlas needs a navigation controller"); }
        this.controller = controller;
    }

    /** Owning navigation controller, for host integrations and native widget verification. */
    public RocketController controller() { return controller; }

    @Override
    protected void init() {
        if (width < 560 || height < 360) {
            addRenderableWidget(Button.builder(Component.translatable("astraengine.map.smaller_ui"), button -> {
                minecraft.options.guiScale().set(1); minecraft.resizeDisplay();
            }).bounds(width / 2 - 100, height / 2, 200, 20).build());
            addRenderableWidget(Button.builder(text("close"), button -> onClose())
                    .bounds(width / 2 - 100, height / 2 + 26, 200, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(text("close"), button -> onClose())
                .bounds(width - 78, 12, 62, 20).build());
        if (controller.snapshot() == null) { return; }
        long seed = controller.snapshot().galaxySeed();
        galaxies = UniverseGenerator.galaxies(seed);
        regions = UniverseGenerator.regions(seed, selectedGalaxy);
        pulsar = PulsarGenerator.landmark(seed, selectedGalaxy);
        int leftWidth = Math.min(220, (width - 48) / 3);
        columnX = 30 + leftWidth;
        columnWidth = width - columnX - 16;
        for (GalaxyDescriptor galaxy : galaxies) {
            addRenderableWidget(Button.builder(Component.literal((galaxy.index() == selectedGalaxy ? "> " : "")
                    + galaxy.name()), button -> {
                selectedGalaxy = galaxy.index(); selectedRegion = 0; rebuildWidgets();
            }).bounds(16, 58 + galaxy.index() * 22, leftWidth, 20).build());
        }
        for (CosmicRegion region : regions) {
            addRenderableWidget(Button.builder(Component.literal((region.index() == selectedRegion ? "> " : "")
                    + region.name()), button -> {
                selectedRegion = region.index(); rebuildWidgets();
            }).bounds(columnX, 58 + region.index() * 22, columnWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal((selectedRegion == regions.size() ? "> " : "")
                + pulsar.name()), button -> {
            selectedRegion = regions.size(); rebuildWidgets();
        }).bounds(columnX, 58 + regions.size() * 22, columnWidth, 20).build());
        jump = addRenderableWidget(Button.builder(Component.translatable("astraengine.map.jump"), button -> {
            if (controller.canJumpTo(selectedSystemId())) {
                controller.action(dev.lexawhatt.astraengine.network.FlightActionPayload.Action.JUMP_SYSTEM, selectedSystemId());
                onClose();
            }
        }).bounds(columnX, height - 80, columnWidth, 20).build());
        int buttonWidth = (columnWidth - 8) / 2;
        aim = addRenderableWidget(Button.builder(text("aim"), button -> {
            if (controller.chartAtlasSystem(selectedSystemId())) { onClose(); }
        }).bounds(columnX, height - 54, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(text("map"), button -> controller.openMap(dev.lexawhatt.astraengine.client.map.NavigationMapAccess.View.SYSTEM))
                .bounds(columnX + buttonWidth + 8, height - 54, buttonWidth, 20).build());
        refreshActions();
    }

    @Override
    public void tick() {
        if (galaxies.isEmpty() && controller.snapshot() != null && width >= 560 && height >= 360) {
            rebuildWidgets();
        }
        refreshActions();
    }

    private void refreshActions() {
        if (aim == null) { return; }
        boolean idle = controller.active() && controller.snapshot().jumpTicks() == 0;
        boolean known = controller.snapshot() != null
                && controller.snapshot().discoveredSystems().contains(selectedSystemId());
        aim.active = idle && (known || controller.snapshot().discoveredSystems().size() < 256);
        jump.active = idle && controller.canJumpTo(selectedSystemId());
    }

    private boolean pulsarSelected() { return selectedRegion == regions.size(); }
    private String selectedSystemId() { return pulsarSelected() ? pulsar.id() : regions.get(selectedRegion).systemId(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFA070D16);
        graphics.drawString(font, title, 16, 18, 0xFFE8F5F4);
        if (width < 560 || height < 360) {
            graphics.drawCenteredString(font, Component.translatable("astraengine.map.small"),
                    width / 2, height / 2 - 24, 0xFFB1CBD1);
        } else if (galaxies.isEmpty() || controller.snapshot() == null) {
            graphics.drawString(font, text("waiting"), 16, 44, 0xFFB1CBD1);
        } else {
            GalaxyDescriptor galaxy = galaxies.get(selectedGalaxy);
            CosmicRegion region = pulsarSelected() ? null : regions.get(selectedRegion);
            SpaceVector position = region == null ? pulsar.galaxyPosition() : region.centerLightYears();
            graphics.drawString(font, text("galaxies", galaxies.size()), 16, 42, 0xFF8FE3DA);
            graphics.drawString(font, text("regions"), columnX, 42, 0xFF8FE3DA);
            graphics.drawString(font, kind(galaxy.kind().name()), 16, height - 66, 0xFF8FE3DA);
            graphics.drawString(font, text("galaxy_radius", format(galaxy.radiusLightYears())),
                    16, height - 52, 0xFFB1CBD1);
            graphics.drawString(font, kind(region == null ? "PULSAR" : region.kind().name()), columnX, height - 124, 0xFF8FE3DA);
            graphics.drawString(font, text("distance", RocketController.distance(position
                    .distance(controller.galaxyPosition()) * CosmosGenerator.LIGHT_YEAR)),
                    columnX, height - 108, 0xFFB1CBD1);
            graphics.drawString(font, region == null ? text("pulsar_radius", format(pulsar.bodies().getFirst().radiusMeters() / 1000))
                    : text("region_radius", format(region.radiusLightYears())),
                    columnX, height - 92, 0xFFB1CBD1);
            String state = controller.visited(selectedSystemId()) ? "visited"
                    : controller.snapshot().discoveredSystems().contains(selectedSystemId()) ? "charted" : "uncharted";
            graphics.drawString(font, text(state), columnX, height - 76, 0xFFEED4AA);
            Component hint = text(controller.active() ? "manual_hint" : "enter_hint");
            graphics.drawString(font, hint, 16, height - 18, 0xFF7E9EA9);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_M) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** The atlas paints its own background; the host blur must not blur its already drawn labels. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%,.1f", value); }
    private static Component kind(String name) { return text("kind." + name.toLowerCase(Locale.ROOT)); }
    private static Component text(String key, Object... args) {
        return Component.translatable("astraengine.atlas." + key, args);
    }
}
