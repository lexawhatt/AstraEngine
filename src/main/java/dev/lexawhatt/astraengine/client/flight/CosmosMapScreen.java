package dev.lexawhatt.astraengine.client.flight;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Non-pausing chart of visible objects with server-owned visit/fast-travel eligibility. */
public final class CosmosMapScreen extends Screen {
    private final RocketController controller;
    private final List<Marker> markers = new ArrayList<>();
    private boolean galactic;
    private String selection;
    private String displayedSystem;
    private int page;
    private double zoom = 1;
    private int panelX;
    private int canvasRight;
    private int rows;
    private int knownCount;
    private Button navigate;
    private Button target;
    private Button scan;
    private Button mode;

    public CosmosMapScreen(RocketController controller) {
        super(text("title"));
        this.controller = controller;
        selection = controller.targetBody();
        displayedSystem = controller.currentSystem().id();
    }

    /** The owning client controller, exposed for host integrations and navigation UI verification. */
    public RocketController controller() { return controller; }

    @Override
    protected void init() {
        if (width < 440 || height < 270) {
            addRenderableWidget(Button.builder(text("smaller_ui"), button -> {
                minecraft.options.guiScale().set(1); minecraft.resizeDisplay();
            }).bounds(width / 2 - 100, height / 2, 200, 20).build());
            addRenderableWidget(Button.builder(text("close"), button -> onClose())
                    .bounds(width / 2 - 100, height / 2 + 26, 200, 20).build());
            return;
        }
        panelX = width - 212;
        canvasRight = panelX - 12;
        rows = Math.max(2, (height - 226) / 22);
        addRenderableWidget(Button.builder(text("local"), button -> switchChart(false)).bounds(14, 37, 110, 20).build());
        addRenderableWidget(Button.builder(text("nearby"), button -> switchChart(true)).bounds(130, 37, 130, 20).build());
        addRenderableWidget(Button.builder(text("atlas"), button -> minecraft.setScreen(new UniverseAtlasScreen(controller)))
                .bounds(230, 12, 112, 20).build());
        addRenderableWidget(Button.builder(text("close"), button -> onClose()).bounds(width - 72, 12, 58, 20).build());
        List<Entry> entries = entries();
        knownCount = entries.size();
        page = Math.clamp(page, 0, Math.max(0, (entries.size() - 1) / rows));
        for (int index = page * rows; index < Math.min(entries.size(), (page + 1) * rows); index++) {
            Entry entry = entries.get(index);
            addRenderableWidget(Button.builder(Component.literal((entry.id.equals(selection) ? "> " : "") + entry.name),
                    button -> { selection = entry.id; rebuildWidgets(); })
                    .bounds(panelX, 59 + (index % rows) * 22, 198, 20).build());
        }
        int navigationY = 62 + rows * 22;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
                .bounds(panelX, navigationY, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
                .bounds(width - 42, navigationY, 28, 20).build()).active = (page + 1) * rows < entries.size();
        target = addRenderableWidget(Button.builder(text("target"), button -> {
            if (galactic) { controller.aimAtSystem(selection); }
            else { controller.setTargetBody(selection); }
            onClose();
        }).bounds(panelX, height - 143, 198, 20).build());
        navigate = addRenderableWidget(Button.builder(text(galactic ? "jump" : "approach"), button -> {
            if (controller.snapshot() != null && controller.snapshot().approaching()) {
                controller.action(FlightActionPayload.Action.BRAKE, "");
            } else {
                controller.action(galactic ? FlightActionPayload.Action.JUMP_SYSTEM : FlightActionPayload.Action.APPROACH_BODY, selection);
            }
            onClose();
        }).bounds(panelX, height - 119, 198, 20).build());
        scan = addRenderableWidget(Button.builder(text("scan"), button -> controller.action(FlightActionPayload.Action.SCAN, ""))
                .bounds(panelX, height - 95, 198, 20).build());
        mode = addRenderableWidget(Button.builder(text(controller.active() ? "leave" : "enter"), button -> {
            controller.action(FlightActionPayload.Action.TOGGLE, ""); onClose();
        }).bounds(panelX, height - 71, 198, 20).build());
        addRenderableWidget(Button.builder(text("smoothing", String.format(Locale.ROOT, "%.2f", controller.smoothing())), button -> {
            controller.cycleSmoothing(); rebuildWidgets();
        }).bounds(14, height - 34, 164, 20).build());
        addRenderableWidget(Button.builder(text("exposure", String.format(Locale.ROOT, "%.2f", controller.exposure())), button -> {
            controller.cycleExposure(); rebuildWidgets();
        }).bounds(184, height - 34, 148, 20).build());
        refreshActions();
    }

    private void switchChart(boolean value) {
        galactic = value; page = 0; zoom = 1;
        selection = value ? controller.currentSystem().id() : controller.targetBody();
        rebuildWidgets();
    }

    private List<Entry> entries() {
        if (galactic) {
            return controller.discoveredSystems().stream().map(system -> new Entry(system.id(), system.name())).toList();
        }
        if (controller.snapshot() == null) { return List.of(); }
        return controller.currentSystem().bodies().stream().map(body -> new Entry(body.id(), body.name())).toList();
    }

    @Override
    public void tick() {
        if (!controller.currentSystem().id().equals(displayedSystem)) {
            displayedSystem = controller.currentSystem().id();
            selection = galactic ? displayedSystem : controller.targetBody(); page = 0; zoom = 1;
            rebuildWidgets();
        } else if (entries().size() != knownCount && width >= 440 && height >= 270) { rebuildWidgets(); }
        refreshActions();
    }

    private void refreshActions() {
        if (navigate == null) { return; }
        boolean idle = controller.active() && controller.snapshot().jumpTicks() == 0;
        boolean approaching = controller.active() && controller.snapshot().approaching();
        boolean selected = entries().stream().anyMatch(entry -> entry.id.equals(selection));
        navigate.setMessage(text(approaching ? "cancel_approach" : galactic ? "jump" : "approach"));
        navigate.active = approaching || (idle && selected && (!galactic
                || controller.canJumpTo(selection)));
        navigate.setTooltip(galactic && selected && !controller.visited(selection)
                ? Tooltip.create(text("unvisited")) : null);
        target.setMessage(text(galactic ? "aim_system" : "target"));
        target.active = selected && (!galactic || idle);
        scan.active = idle;
        mode.setMessage(text(controller.active() ? "leave" : "enter"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF5070D16);
        graphics.drawString(font, title, 14, 16, 0xFFE8F5F4);
        if (width < 440 || height < 270) {
            graphics.drawCenteredString(font, text("small"), width / 2, height / 2 - 24, 0xFFB1CBD1);
            super.render(graphics, mouseX, mouseY, partialTick); return;
        }
        graphics.drawString(font, text(galactic ? "discovered" : "system", galactic ? controller.discoveredSystems().size()
                : controller.currentSystem().name()), panelX, 41, 0xFF8FE3DA);
        graphics.fill(14, 65, canvasRight, height - 71, 0xFF050A11);
        graphics.enableScissor(14, 65, canvasRight, height - 71);
        renderChart(graphics, mouseX, mouseY);
        graphics.disableScissor();
        graphics.drawString(font, text("chart_hint"), 20, height - 65, 0xFF7E9EA9);
        renderSelection(graphics);
        graphics.drawCenteredString(font, Component.literal((page + 1) + " / " + Math.max(1, (knownCount + rows - 1) / rows)),
                panelX + 99, 68 + rows * 22, 0xFFA4BEC8);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderChart(GuiGraphics graphics, int mouseX, int mouseY) {
        markers.clear();
        int centerX = (14 + canvasRight) / 2, centerY = (65 + height - 71) / 2;
        double extent = Math.min(canvasRight - 14, height - 136) * 0.43;
        double units = galactic ? 14 : controller.currentSystem().bodies().stream()
                .mapToDouble(body -> body.orbitMeters() * (1 + body.eccentricity())).max().orElse(CosmosGenerator.AU) * 1.1;
        double scale = extent / Math.max(1, units) * zoom;
        for (int index = -4; index <= 4; index++) {
            int x = centerX + index * 40, y = centerY + index * 40;
            graphics.fill(x, 65, x + 1, height - 71, 0xFF10232D);
            graphics.fill(14, y, canvasRight, y + 1, 0xFF10232D);
        }
        if (galactic) {
            SpaceVector origin = controller.currentSystem().galaxyPosition();
            for (CosmosSystem system : controller.discoveredSystems()) {
                SpaceVector delta = system.galaxyPosition().subtract(origin);
                int color = switch (system.kind()) {
                    case BLACK_HOLE -> 0xFFE7ACF7;
                    case SUPERNOVA -> 0xFFFF9F80;
                    case BINARY -> 0xFFE6D493;
                    default -> 0xFFA8D5F0;
                };
                if (!controller.visited(system.id())) { color = 0xFF687D94; }
                marker(graphics, system.id(), system.name(), centerX + delta.x() * scale, centerY + delta.z() * scale, color);
            }
        } else if (controller.snapshot() != null) {
            for (CelestialBody body : controller.currentSystem().bodies()) {
                if (body.orbitMeters() > 0) {
                    SpaceVector previous = body.positionAt(0);
                    for (int index = 1; index <= 96; index++) {
                        SpaceVector next = body.positionAt(body.orbitalPeriodSeconds() * index / 96);
                        line(graphics, centerX + previous.x() * scale, centerY + previous.z() * scale,
                                centerX + next.x() * scale, centerY + next.z() * scale, 0xFF23414D);
                        previous = next;
                    }
                }
                SpaceVector position = body.positionAt(controller.timeSeconds());
                int color = 0xFF000000 | ((int) (body.color().x() * 200 + 55) << 16)
                        | ((int) (body.color().y() * 200 + 55) << 8) | (int) (body.color().z() * 200 + 55);
                marker(graphics, body.id(), body.name(), centerX + position.x() * scale, centerY + position.z() * scale, color);
            }
            SpaceVector position = controller.visualPosition();
            int x = (int) (centerX + position.x() * scale), y = (int) (centerY + position.z() * scale);
            if (x >= 14 && x < canvasRight && y >= 65 && y < height - 71) {
                graphics.fill(x - 5, y, x + 6, y + 1, 0xFF64FFD4);
                graphics.fill(x, y - 5, x + 1, y + 6, 0xFF64FFD4);
                graphics.drawString(font, text("you"), x - font.width(text("you")) - 8, y + 5, 0xFF64FFD4);
            }
        }
        renderLabels(graphics, mouseX, mouseY);
        String scaleLabel = galactic ? String.format(Locale.ROOT, "%.2f ly", 40 / scale)
                : RocketController.distance(40 / scale);
        graphics.drawString(font, text("scale", scaleLabel), 20, 71, 0xFF9CBEC9);
    }

    private void marker(GuiGraphics graphics, String id, String name, double px, double py, int color) {
        if (px < 20 || px > canvasRight - 8 || py < 88 || py > height - 79) { return; }
        int x = (int) px, y = (int) py;
        if (id.equals(selection)) {
            graphics.fill(x - 6, y - 6, x + 7, y + 7, 0xFF3E7180);
        }
        graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
        markers.add(new Marker(id, name, x, y, color));
    }

    private void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Marker> ordered = new ArrayList<>(markers);
        ordered.sort(java.util.Comparator.comparingInt(marker -> marker.id.equals(selection) ? 0
                : Math.hypot(marker.x - mouseX, marker.y - mouseY) < 12 ? 1 : 2));
        List<LabelBox> occupied = new ArrayList<>();
        for (Marker marker : ordered) {
            int labelWidth = font.width(marker.name);
            int x = marker.x + 8;
            if (x + labelWidth >= canvasRight - 5) { x = marker.x - labelWidth - 8; }
            int y = marker.y - 4;
            LabelBox box = new LabelBox(x - 2, y - 2, x + labelWidth + 3, y + 11);
            if (x < 16 || occupied.stream().anyMatch(box::overlaps)) { continue; }
            graphics.fill(box.left, box.top, box.right, box.bottom, 0xC0050A11);
            graphics.drawString(font, marker.name, x, y, marker.color);
            occupied.add(box);
        }
    }

    private void renderSelection(GuiGraphics graphics) {
        String detail = "";
        if (galactic) {
            CosmosSystem selected = controller.discoveredSystems().stream().filter(value -> value.id().equals(selection)).findFirst().orElse(null);
            if (selected != null) {
                detail = selected.name() + "  /  " + String.format(Locale.ROOT, "%.2f ly", selected.galaxyPosition()
                        .distance(controller.galaxyPosition())) + "  /  "
                        + text(controller.visited(selected.id()) ? "visited" : "unvisited").getString();
            }
        } else {
            CelestialBody selected = controller.currentSystem().bodies().stream().filter(value -> value.id().equals(selection)).findFirst().orElse(null);
            if (selected != null) { detail = selected.name() + "  /  R " + RocketController.distance(selected.radiusMeters())
                    + "  /  " + RocketController.distance(selected.positionAt(controller.timeSeconds()).distance(controller.visualPosition())); }
        }
        graphics.drawString(font, font.plainSubstrByWidth(detail, canvasRight - 22), 20, height - 51, 0xFFE1D3AF);
    }

    private void line(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        // Bound raster work even when the chart is zoomed far into an orbit.
        int steps = Math.min(160, (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1))));
        for (int index = 0; index <= steps; index++) {
            double fraction = steps == 0 ? 0 : (double) index / steps;
            double x = x1 + (x2 - x1) * fraction, y = y1 + (y2 - y1) * fraction;
            if (x >= 14 && x < canvasRight && y >= 65 && y < height - 71) {
                graphics.fill((int) x, (int) y, (int) x + 1, (int) y + 1, color);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Marker closest = markers.stream().filter(marker -> Math.hypot(marker.x - mouseX, marker.y - mouseY) < 12)
                    .min(java.util.Comparator.comparingDouble(marker -> Math.hypot(marker.x - mouseX, marker.y - mouseY))).orElse(null);
            if (closest != null) { selection = closest.id; rebuildWidgets(); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= 14 && mouseX < canvasRight && mouseY >= 65 && mouseY < height - 71) {
            zoom = Math.clamp(zoom * Math.pow(1.3, vertical), 0.25, 1_000_000); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_M) { onClose(); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }

    /** The chart paints its own background before widgets; the host blur would blur the chart itself. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean isPauseScreen() { return false; }
    private static Component text(String key, Object... values) { return Component.translatable("astraengine.map." + key, values); }
    private record Entry(String id, String name) {}
    private record Marker(String id, String name, int x, int y, int color) {}
    private record LabelBox(int left, int top, int right, int bottom) {
        boolean overlaps(LabelBox other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }
}
