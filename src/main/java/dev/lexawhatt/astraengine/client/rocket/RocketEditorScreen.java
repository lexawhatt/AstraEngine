package dev.lexawhatt.astraengine.client.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketBuiltins;
import dev.lexawhatt.astraengine.rocket.RocketModuleDefinition;
import dev.lexawhatt.astraengine.rocket.RocketParameterDefinition;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import dev.lexawhatt.astraengine.rocket.RocketPlacement;
import dev.lexawhatt.astraengine.rocket.RocketStats;
import dev.lexawhatt.astraengine.rocket.RocketValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Schema-driven modular rocket construction with an isolated shader viewport and explicit server saves. */
public final class RocketEditorScreen extends Screen {
    private final RocketEditorClient editor;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, RocketValue> formValues = new LinkedHashMap<>();
    private String nameDraft;
    private String search = "";
    private String renderedSearch = "";
    private final Map<String, Button> valueButtons = new LinkedHashMap<>();
    private final Map<String, String> valueLabels = new LinkedHashMap<>();
    private String librarySelection;
    private boolean assemblyList;
    private int firstRow;
    private int page;
    private int moduleIndex;
    private int parameterPage;
    private int symmetry = 1;
    private RocketPlacement.Attachment attachment = RocketPlacement.Attachment.BOTTOM;
    private RocketPart renderedPart;
    private int renderedPage = -1;
    private int renderedModule = -1;
    private int renderedParameterPage = -1;
    private int seenRevision;
    private int rightX;
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private int listRows;
    private double yaw = -30;
    private double pitch = 12;
    private double zoom = 1;
    private boolean orbiting;
    private boolean compact;
    private Button saveButton;
    private Button deployButton;
    private Button applyButton;

    /** The controller owns this server-authorized draft; opening a screen does not create a new session. */
    public RocketEditorScreen(RocketEditorClient editor) {
        super(text("title"));
        this.editor = Objects.requireNonNull(editor);
        nameDraft = editor.blueprint().name();
        librarySelection = editor.catalog().definitions().getFirst().id();
    }

    /** Immutable draft inspection for integrations and native verification. */
    public RocketBlueprint blueprint() { return editor.blueprint(); }
    public RocketEditorClient controller() { return editor; }

    @Override
    protected void init() {
        Map<String, String> oldFields = new LinkedHashMap<>();
        Map<String, RocketValue> oldValues = Map.copyOf(formValues);
        boolean preserve = Objects.equals(renderedPart, editor.selected()) && renderedPage == page
                && renderedModule == moduleIndex && renderedParameterPage == parameterPage;
        if (preserve) { fields.forEach((key, value) -> oldFields.put(key, value.getValue())); }
        fields.clear(); formValues.clear(); valueButtons.clear(); valueLabels.clear();
        renderedSearch = search;
        compact = width < 600 || height < 360;
        saveButton = null; deployButton = null; applyButton = null;
        seenRevision = editor.revision();
        if (compact) {
            button(text("smaller_ui"), width / 2 - 90, height / 2, 180, () -> {
                minecraft.options.guiScale().set(1); minecraft.resizeDisplay();
            });
            button(text("close"), width / 2 - 90, height / 2 + 25, 180, this::onClose);
            return;
        }
        rightX = width - 192;
        viewportX = 146; viewportY = 68; viewportWidth = rightX - viewportX - 8;
        viewportHeight = height - viewportY - 112;
        EditBox name = new EditBox(font, 150, 10, Math.max(80, Math.min(210, width - 362)), 18, text("name"));
        name.setMaxLength(64); name.setValue(nameDraft); name.setResponder(value -> nameDraft = value);
        addRenderableWidget(name);
        button(text("close"), width - 74, 8, 66, this::onClose);
        int toolbar = (width - 16) / 7;
        button(text("undo"), 8, 36, toolbar - 4, () -> { editor.undo(); nameDraft = blueprint().name(); rebuildWidgets(); });
        button(text("redo"), 8 + toolbar, 36, toolbar - 4, () -> { editor.redo(); nameDraft = blueprint().name(); rebuildWidgets(); });
        button(text("starter"), 8 + toolbar * 2, 36, toolbar - 4, () -> {
            editor.commit(RocketBuiltins.starter(editor.catalog())); nameDraft = blueprint().name(); rebuildWidgets();
        });
        button(text("restore"), 8 + toolbar * 3, 36, toolbar - 4, () -> {
            editor.reloadSaved(); nameDraft = blueprint().name(); rebuildWidgets();
        });
        button(text("delete"), 8 + toolbar * 4, 36, toolbar - 4, () -> { editor.removeSelected(); rebuildWidgets(); });
        saveButton = button(text("save"), 8 + toolbar * 5, 36, toolbar - 4, () -> save(false));
        deployButton = button(text("deploy"), 8 + toolbar * 6, 36, toolbar - 4, () -> save(true));
        initLibrary();
        initInspector();
        if (preserve) {
            fields.forEach((key, field) -> { if (oldFields.containsKey(key)) { field.setValue(oldFields.get(key)); } });
            for (String key : List.copyOf(formValues.keySet())) {
                if (oldValues.containsKey(key)) { formValues.put(key, oldValues.get(key));
                    if (valueButtons.containsKey(key)) {
                        valueButtons.get(key).setMessage(Component.literal(valueLabels.get(key) + ": " + valueLabel(oldValues.get(key))));
                    } }
            }
        }
        renderedPart = editor.selected(); renderedPage = page; renderedModule = moduleIndex;
        renderedParameterPage = parameterPage;
        updateActions();
    }

    @Override
    protected void rebuildWidgets() {
        Component focus = getFocused() instanceof EditBox box ? box.getMessage() : null;
        int cursor = getFocused() instanceof EditBox box ? box.getCursorPosition() : 0;
        super.rebuildWidgets();
        if (focus != null) {
            for (var child : children()) {
                if (child instanceof EditBox box && box.getMessage().equals(focus)) {
                    setFocused(box); box.setCursorPosition(Math.min(cursor, box.getValue().length())); break;
                }
            }
        }
    }

    private void initLibrary() {
        button(text(assemblyList ? "assembly" : "catalog"), 8, 72, 126, () -> {
            assemblyList = !assemblyList; firstRow = 0; rebuildWidgets();
        });
        EditBox filter = new EditBox(font, 8, 98, 126, 17, text("search"));
        filter.setHint(text("search")); filter.setValue(search);
        filter.setResponder(value -> { search = value; firstRow = 0; }); addRenderableWidget(filter);
        listRows = Math.max(1, (height - 282) / 21);
        if (assemblyList) {
            List<RocketPart> parts = blueprint().parts();
            firstRow = Math.clamp(firstRow, 0, Math.max(0, parts.size() - listRows));
            for (int i = 0; i < listRows && firstRow + i < parts.size(); i++) {
                RocketPart part = parts.get(firstRow + i);
                String name = editor.catalog().requireDefinition(part.definitionId()).displayName();
                String label = (part.equals(editor.selected()) ? "> " : "") + part.id() + " " + name;
                button(Component.literal(label), 8, 122 + i * 21, 126, () -> { editor.select(part.id()); rebuildWidgets(); })
                        .setTooltip(Tooltip.create(Component.literal(name + " / " + part.definitionId())));
            }
        } else {
            List<RocketPartDefinition> library = library();
            firstRow = Math.clamp(firstRow, 0, Math.max(0, library.size() - listRows));
            for (int i = 0; i < listRows && firstRow + i < library.size(); i++) {
                RocketPartDefinition definition = library.get(firstRow + i);
                button(Component.literal((definition.id().equals(librarySelection) ? "> " : "") + definition.displayName()),
                        8, 122 + i * 21, 126, () -> { librarySelection = definition.id(); rebuildWidgets(); })
                        .setTooltip(Tooltip.create(Component.literal(definition.id() + " / " + definition.category())));
            }
        }
        int bottom = height - 132;
        button(text("attach." + attachment.name().toLowerCase(Locale.ROOT)), 8, bottom - 24, 76, () -> {
            attachment = RocketPlacement.Attachment.values()[(attachment.ordinal() + 1) % RocketPlacement.Attachment.values().length];
            rebuildWidgets();
        });
        button(text("symmetry", symmetry), 88, bottom - 24, 46, () -> {
            symmetry = symmetry == 1 ? 2 : symmetry == 2 ? 4 : 1; rebuildWidgets();
        });
        button(Component.literal("<"), 8, bottom, 26, () -> { firstRow = Math.max(0, firstRow - listRows); rebuildWidgets(); });
        button(text("add"), 38, bottom, 66, () -> { editor.attach(librarySelection, attachment, symmetry); rebuildWidgets(); });
        button(Component.literal(">"), 108, bottom, 26, () -> { firstRow += listRows; rebuildWidgets(); });
    }

    private List<RocketPartDefinition> library() {
        String filter = search.toLowerCase(Locale.ROOT);
        return editor.catalog().definitions().stream().filter(definition -> definition.displayName().toLowerCase(Locale.ROOT).contains(filter)
                || definition.id().contains(filter) || definition.category().contains(filter)).toList();
    }

    private void initInspector() {
        for (int i = 0; i < 3; i++) {
            int target = i;
            button(text("page." + i), rightX + 6 + i * 60, 72, 58, () -> { page = target; rebuildWidgets(); });
        }
        RocketPart part = editor.selected();
        if (part == null) { return; }
        if (page == 0) {
            numberField("x", text("field.x").getString(), part.position().x(), 0);
            numberField("y", text("field.y").getString(), part.position().y(), 1);
            numberField("z", text("field.z").getString(), part.position().z(), 2);
            numberField("yaw", text("field.yaw").getString(), part.yawQuarterTurns(), 3);
        } else if (page == 1) {
            numberField("width", text("field.width").getString(), part.size().x(), 0);
            numberField("height", text("field.height").getString(), part.size().y(), 1);
            numberField("depth", text("field.depth").getString(), part.size().z(), 2);
        } else {
            List<RocketModuleDefinition> modules = editor.catalog().requireDefinition(part.definitionId()).modules();
            if (modules.isEmpty()) { return; }
            moduleIndex = Math.floorMod(moduleIndex, modules.size());
            RocketModuleDefinition module = modules.get(moduleIndex);
            button(Component.literal(module.displayName()), rightX + 6, 98, 178, () -> {
                moduleIndex = (moduleIndex + 1) % modules.size(); parameterPage = 0; rebuildWidgets();
            }).setTooltip(Tooltip.create(Component.literal(module.id())));
            int count = Math.max(1, Math.min(6, (height - 222) / 28));
            int pages = Math.max(1, (module.parameters().size() + count - 1) / count);
            parameterPage = Math.floorMod(parameterPage, pages);
            for (int i = 0; i < count && parameterPage * count + i < module.parameters().size(); i++) {
                RocketParameterDefinition parameter = module.parameters().get(parameterPage * count + i);
                RocketValue value = part.moduleValues().get(module.id()).get(parameter.key());
                formValues.put(parameter.key(), value);
                String label = parameter.displayName() + (parameter.unit().isEmpty() ? "" : " (" + parameter.unit() + ")");
                int y = 124 + i * 28;
                if (parameter.kind() == RocketValue.Kind.NUMBER) {
                    field(parameter.key(), label, format(value.number()), rightX + 6, y + 10, 178);
                } else {
                    valueLabels.put(parameter.key(), label);
                    Button valueButton = button(Component.literal(label + ": " + valueLabel(value)), rightX + 6, y + 8, 178, () -> {
                        RocketValue current = formValues.get(parameter.key());
                        RocketValue next = parameter.kind() == RocketValue.Kind.BOOLEAN ? RocketValue.flag(!current.flag())
                                : RocketValue.choice(parameter.choices().get((parameter.choices().indexOf(current.choice()) + 1)
                                        % parameter.choices().size()));
                        formValues.put(parameter.key(), next);
                        valueButtons.get(parameter.key()).setMessage(Component.literal(label + ": " + valueLabel(next)));
                    });
                    valueButtons.put(parameter.key(), valueButton);
                }
            }
            if (pages > 1) {
                button(text("parameters", parameterPage + 1, pages), rightX + 6, height - 152, 178, () -> {
                    parameterPage = (parameterPage + 1) % pages; rebuildWidgets();
                });
            }
        }
        applyButton = button(text("apply"), rightX + 6, height - 128, 178, this::apply);
    }

    private void numberField(String key, String label, double value, int row) {
        field(key, label, format(value), rightX + 6, 113 + row * 30, 178);
    }

    private EditBox field(String key, String label, String value, int x, int y, int fieldWidth) {
        EditBox box = new EditBox(font, x, y, fieldWidth, 17, Component.literal(label));
        box.setMaxLength(48); box.setValue(value); addRenderableWidget(box); fields.put(key, box);
        return box;
    }

    private static String valueLabel(RocketValue value) {
        return value.kind() == RocketValue.Kind.BOOLEAN ? Boolean.toString(value.flag()) : value.choice();
    }

    private double number(String key) {
        double value = Double.parseDouble(fields.get(key).getValue());
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("A finite number is required"); }
        return value;
    }

    private void apply() {
        RocketPart part = editor.selected();
        if (part == null || editor.busy()) { return; }
        try {
            SpaceVector position = part.position(); SpaceVector size = part.size(); int rotation = part.yawQuarterTurns();
            Map<String, Map<String, RocketValue>> modules = new LinkedHashMap<>(part.moduleValues());
            if (page == 0) {
                position = new SpaceVector(number("x"), number("y"), number("z"));
                double value = number("yaw");
                if (value != Math.rint(value) || value < 0 || value > 3) { throw new IllegalArgumentException("Yaw must be an integer quarter turn"); }
                rotation = (int) value;
            } else if (page == 1) {
                size = new SpaceVector(number("width"), number("height"), number("depth"));
            } else {
                RocketModuleDefinition definition = editor.catalog().requireDefinition(part.definitionId()).modules().get(moduleIndex);
                Map<String, RocketValue> values = new LinkedHashMap<>(part.moduleValues().get(definition.id()));
                values.putAll(formValues);
                for (String key : fields.keySet()) { values.put(key, RocketValue.number(number(key))); }
                modules.put(definition.id(), Map.copyOf(values));
            }
            RocketPart updated = new RocketPart(part.id(), part.parentId(), part.definitionId(), position, size, rotation, modules);
            List<RocketPart> parts = new ArrayList<>(blueprint().parts()); parts.set(parts.indexOf(part), updated);
            if (editor.commit(new RocketBlueprint(blueprint().name(), parts))) { rebuildWidgets(); }
        } catch (IllegalArgumentException invalid) { editor.reject(invalid.getMessage()); }
    }

    private void save(boolean deploy) {
        if (editor.busy()) { return; }
        try {
            if (editor.commit(new RocketBlueprint(nameDraft, blueprint().parts()))) { editor.save(deploy); }
        } catch (IllegalArgumentException invalid) { editor.reject(invalid.getMessage()); }
    }

    @Override
    public void tick() {
        if (blueprint() == null) { minecraft.setScreen(null); return; }
        if (seenRevision != editor.revision() || !renderedSearch.equals(search)) { rebuildWidgets(); }
        updateActions();
    }

    private void updateActions() {
        boolean available = !editor.busy();
        if (saveButton != null) { saveButton.active = available; }
        if (deployButton != null) { deployButton.active = available && !blueprint().parts().isEmpty(); }
        if (applyButton != null) { applyButton.active = available; }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF07111B);
        graphics.fill(0, 0, width, 31, 0xFF102331);
        graphics.drawString(font, title, 10, 13, 0xFFB5EBEE);
        if (compact) {
            graphics.drawCenteredString(font, text("small"), width / 2, height / 2 - 22, 0xFFC2D6DF);
            super.render(graphics, mouseX, mouseY, partialTick); return;
        }
        graphics.fill(4, 66, 138, height - 108, 0xFF0E1D29);
        graphics.fill(rightX, 66, width - 4, height - 108, 0xFF0E1D29);
        if (!editor.renderer().renderPreview(graphics, viewportX, viewportY, viewportWidth, viewportHeight,
                editor.catalog(), blueprint(), editor.selectedIndex(), yaw, pitch, zoom)) {
            graphics.drawCenteredString(font, text("shader_wait"), viewportX + viewportWidth / 2,
                    viewportY + viewportHeight / 2, 0xFFEBC378);
        }
        graphics.drawString(font, text("orbit_hint"), viewportX + 8, height - 125, 0xFF91ACBE);
        RocketPart part = editor.selected();
        if (part != null) {
            for (EditBox field : fields.values()) {
                graphics.drawString(font, field.getMessage(), field.getX(), field.getY() - 10, 0xFFA7C3D1);
            }
        }
        renderStats(graphics);
        graphics.enableScissor(8, height - 22, width - 8, height);
        graphics.drawString(font, editor.feedback(), 10, height - 17, 0xFFE7C982);
        graphics.disableScissor();
        graphics.drawString(font, text(editor.dirty() || !nameDraft.equals(blueprint().name()) ? "unsaved" : "saved"),
                Math.max(365, width - 215), 14, 0xFF8ACDCC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderStats(GuiGraphics graphics) {
        RocketStats stats = RocketStats.calculate(editor.catalog(), blueprint());
        graphics.fill(4, height - 104, width - 4, height - 26, 0xFF0B202A);
        Component[] lines = {
            text("stats.mass", metric(stats.wetMassKg(), 0), metric(stats.fuelMassKg(), 0)),
            text("stats.thrust", metric(stats.thrustNewtons() / 1000, 1), metric(stats.thrustToWeightRatio(), 2)),
            text("stats.burn", metric(stats.deltaVMetersPerSecond(), 0), metric(stats.burnTimeSeconds(), 1)),
            text("stats.power", metric(stats.powerKilowatts(), 1), metric(stats.energyKWh(), 1)),
            text("stats.heat", metric(stats.heatKilowatts(), 1), metric(stats.coolingKilowatts(), 1))
        };
        for (int i = 0; i < lines.length; i++) {
            int column = i < 3 ? 0 : 1; int row = i < 3 ? i : i - 3;
            graphics.drawString(font, lines[i], 12 + column * (width / 2), height - 95 + row * 17, 0xFFC3DCE2);
        }
        graphics.drawString(font, text("part_count", blueprint().parts().size(), 32), 12, height - 43, 0xFF8DD5D0);
        if (!stats.diagnostics().isEmpty()) {
            String diagnostic = text("diagnostic." + stats.diagnostics().getFirst().name().toLowerCase(Locale.ROOT)).getString();
            graphics.drawString(font, font.plainSubstrByWidth(diagnostic, width / 2 - 22), width / 2 + 12,
                    height - 61, 0xFFE7C982);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inViewport(mouseX, mouseY)) {
            if (button == 1) { orbiting = true; return true; }
            if (button == 0) {
                int index = editor.renderer().pickPart(viewportX, viewportY, viewportWidth, viewportHeight,
                        editor.catalog(), blueprint(), mouseX, mouseY, yaw, pitch, zoom);
                if (index >= 0) { editor.select(blueprint().parts().get(index).id()); rebuildWidgets(); }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (orbiting && button == 1) {
            yaw = Math.IEEEremainder(yaw + deltaX * 0.65, 360); pitch = Math.clamp(pitch + deltaY * 0.65, -85, 85); return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1) { orbiting = false; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (inViewport(mouseX, mouseY)) { zoom = Math.clamp(zoom * Math.pow(0.88, vertical), 0.25, 4); return true; }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_S) { save(false); return true; }
        if (!(getFocused() instanceof EditBox) && hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) { editor.redo(); } else { editor.undo(); } return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && fields.containsValue(getFocused())) { apply(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean inViewport(double x, double y) {
        return !compact && x >= viewportX && x < viewportX + viewportWidth && y >= viewportY && y < viewportY + viewportHeight;
    }

    private Button button(Component label, int x, int y, int buttonWidth, Runnable action) {
        return addRenderableWidget(Button.builder(label, ignored -> action.run()).bounds(x, y, buttonWidth, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { editor.close(); super.onClose(); }

    @Override
    public void removed() { editor.renderer().releasePreview(); }

    private static String metric(double value, int decimals) { return String.format(Locale.ROOT, "%,." + decimals + "f", value).trim(); }
    private static String format(double value) {
        String result = Double.toString(value);
        return result.endsWith(".0") ? result.substring(0, result.length() - 2) : result;
    }
    private static Component text(String key, Object... args) { return RocketEditorClient.text(key, args); }
}
