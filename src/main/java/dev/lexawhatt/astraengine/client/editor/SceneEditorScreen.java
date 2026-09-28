package dev.lexawhatt.astraengine.client.editor;

import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.scene.SceneDocument;
import dev.lexawhatt.astraengine.client.scene.SceneObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Non-pausing scene inspector built from host widgets; the uncovered center remains a live world preview. */
public final class SceneEditorScreen extends Screen {
    private enum Page { POSITION, ROTATION, SCALE, MATERIAL, LIGHT }
    private final SceneEditor editor;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private SceneObject.Kind createKind = SceneObject.Kind.SPHERE;
    private Page page = Page.POSITION;
    private Page renderedPage;
    private SceneObject renderedObject;
    private EditBox preset;
    private String presetName = "scene";
    private int firstRow;
    private int seenRevision;
    private int leftWidth;
    private int rightX;
    private int rowCount;
    private int fieldStart;
    private int fieldSpacing;
    private boolean compactFallback;

    /** The controller outlives this screen so closing the editor keeps its explicit preview and draft. */
    public SceneEditorScreen(SceneEditor editor) {
        super(SceneEditor.text("title"));
        this.editor = editor;
    }

    /** Immutable inspection snapshot; editing still goes through the screen's validated actions. */
    public SceneDocument document() { return editor.document(); }
    public boolean busy() { return editor.busy(); }

    @Override
    protected void rebuildWidgets() {
        EditBox focused = getFocused() instanceof EditBox box ? box : null;
        Component label = focused == null ? null : focused.getMessage();
        int cursor = focused == null ? 0 : focused.getCursorPosition();
        super.rebuildWidgets();
        if (label != null) {
            for (var child : children()) {
                if (child instanceof EditBox box && box.getMessage().equals(label)) {
                    setFocused(box);
                    box.moveCursorTo(cursor, false);
                    break;
                }
            }
        }
    }

    @Override
    protected void init() {
        if (preset != null) { presetName = preset.getValue(); }
        Map<String, String> drafts = new LinkedHashMap<>();
        if (page == renderedPage && Objects.equals(renderedObject, editor.selected())) {
            fields.forEach((key, field) -> drafts.put(key, field.getValue()));
        }
        fields.clear();
        compactFallback = width < 320 || height < 240;
        if (compactFallback) {
            seenRevision = editor.revision();
            button("done", Math.max(4, width - 54), 4, 48, this::onClose);
            button("smaller_ui", 10, Math.max(80, height - 32), Math.min(150, width - 20), () -> {
                minecraft.options.guiScale().set(1);
                minecraft.resizeDisplay();
            });
            return;
        }
        leftWidth = Math.clamp(width / 4, 100, 152);
        rightX = width - Math.clamp(width / 3, 154, 202);
        seenRevision = editor.revision();
        button("done", width - 54, 4, 48, this::onClose);
        int toolbarWidth = Math.max(39, (width - 16) / 7);
        action("undo", 6, 26, toolbarWidth - 2, editor::undo, editor.canUndo());
        action("redo", 6 + toolbarWidth, 26, toolbarWidth - 2, editor::redo, editor.canRedo());
        button("preview", 6 + toolbarWidth * 2, 26, toolbarWidth - 2, editor::togglePreview)
                .setTooltip(Tooltip.create(SceneEditor.text(editor.preview() ? "preview_on" : "preview_off")));
        button("flashlight", 6 + toolbarWidth * 3, 26, toolbarWidth - 2, () -> editor.options().toggleFlashlight());
        button("environment", 6 + toolbarWidth * 4, 26, toolbarWidth - 2, () -> editor.options().cycleEnvironment())
                .setTooltip(Tooltip.create(Component.literal(editor.options().environment())));
        button("quality", 6 + toolbarWidth * 5, 26, toolbarWidth - 2, () -> editor.options().cycleQuality());
        button("shaders", 6 + toolbarWidth * 6, 26, toolbarWidth - 2,
                () -> minecraft.setScreen(new ShaderEditorScreen(this, editor.shaders())));

        button("kind." + lower(createKind), 10, 64, leftWidth - 16, () -> {
            createKind = SceneObject.Kind.values()[(createKind.ordinal() + 1) % SceneObject.Kind.values().length];
        });
        action("add", 10, 86, leftWidth - 16, () -> editor.create(createKind), !editor.busy() && editor.inDimension());
        rowCount = Math.max(1, (height - 218) / 19);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, document().objects().size() - rowCount));
        for (int i = 0; i < rowCount && firstRow + i < document().objects().size(); i++) {
            SceneObject object = document().objects().get(firstRow + i);
            Component rowLabel = Component.literal(object.id()).withStyle(Objects.equals(object, editor.selected())
                    ? ChatFormatting.AQUA : ChatFormatting.WHITE);
            Button row = addRenderableWidget(Button.builder(rowLabel, button -> {
                editor.select(object.id());
                rebuildWidgets();
            }).bounds(10, 112 + i * 19, leftWidth - 16, 18).build());
            row.setTooltip(Tooltip.create(SceneEditor.text("kind." + lower(object.kind()))));
        }
        button("previous", 10, height - 99, (leftWidth - 18) / 2, () -> firstRow = Math.max(0, firstRow - rowCount));
        button("next", 12 + (leftWidth - 18) / 2, height - 99, (leftWidth - 18) / 2,
                () -> firstRow = Math.min(Math.max(0, document().objects().size() - rowCount), firstRow + rowCount));
        action("duplicate", 10, height - 77, (leftWidth - 18) / 2, editor::duplicate, !editor.busy() && editor.selected() != null);
        action("delete", 12 + (leftWidth - 18) / 2, height - 77, (leftWidth - 18) / 2, editor::delete,
                !editor.busy() && editor.selected() != null);
        action("new", 10, height - 55, leftWidth - 16, editor::newScene, !editor.busy());

        SceneObject object = editor.selected();
        if (object != null) {
            button("page." + lower(page), rightX + 8, 64, width - rightX - 16, () -> {
                page = Page.values()[(page.ordinal() + 1) % Page.values().length];
            });
            fieldStart = 88;
            fieldSpacing = Math.clamp((height - 59 - fieldStart) / 5, 18, 22);
            switch (page) {
                case POSITION -> vector(object.position());
                case ROTATION -> vector(object.rotation());
                case SCALE -> vector(object.scale());
                case MATERIAL -> {
                    field("red", object.color().x());
                    field("green", object.color().y());
                    field("blue", object.color().z());
                    field("emission", object.emission());
                    field("inner_radius", object.innerRadius());
                }
                case LIGHT -> {
                    field("intensity", object.intensity());
                    field("range", object.range());
                    field("inner_degrees", object.innerDegrees());
                    field("outer_degrees", object.outerDegrees());
                }
            }
            drafts.forEach((key, value) -> { if (fields.containsKey(key)) { fields.get(key).setValue(value); } });
            button("visible", rightX + 8, height - 55, (width - rightX - 18) / 2,
                    () -> editor.apply(copy(object, !object.visible())))
                    .setTooltip(Tooltip.create(SceneEditor.text(object.visible() ? "visible_on" : "visible_off")));
            action("apply", rightX + 10 + (width - rightX - 18) / 2, height - 55,
                    (width - rightX - 18) / 2, this::apply, !editor.busy());
        }
        renderedPage = page;
        renderedObject = object;
        preset = addRenderableWidget(new EditBox(font, 10, height - 29, Math.max(70, width - 208), 18,
                SceneEditor.text("preset")));
        preset.setMaxLength(48);
        preset.setValue(presetName);
        preset.setHint(SceneEditor.text("preset"));
        preset.setTooltip(Tooltip.create(SceneEditor.text("preset_hint")));
        button("browse", width - 191, height - 29, 56, this::browsePresets);
        action("save", width - 131, height - 29, 59, () -> editor.save(preset.getValue()), !editor.busy());
        action("load", width - 68, height - 29, 58, () -> editor.load(preset.getValue()), !editor.busy());
    }

    private Button button(String key, int x, int y, int size, Runnable action) {
        return addRenderableWidget(Button.builder(SceneEditor.text(key), button -> {
            action.run();
            if (minecraft.screen == this) { rebuildWidgets(); }
        }).bounds(x, y, size, 18).build());
    }

    private void action(String key, int x, int y, int size, Runnable action, boolean enabled) {
        button(key, x, y, size, action).active = enabled;
    }

    private void vector(LightVector value) {
        field("x", value.x()); field("y", value.y()); field("z", value.z());
    }

    private void field(String key, double value) {
        int y = fieldStart + fields.size() * fieldSpacing;
        EditBox box = addRenderableWidget(new EditBox(font, rightX + (width - rightX) / 2, y,
                (width - rightX) / 2 - 9, 18, SceneEditor.text("field." + key)));
        box.setMaxLength(24);
        box.setValue(String.format(Locale.ROOT, "%.4f", value));
        box.setEditable(!editor.busy());
        fields.put(key, box);
    }

    private double number(String key) {
        double number = Double.parseDouble(fields.get(key).getValue());
        if (!Double.isFinite(number)) { throw new IllegalArgumentException("A finite number is required: " + key); }
        return number;
    }

    private LightVector vector() { return new LightVector(number("x"), number("y"), number("z")); }

    private void apply() {
        SceneObject object = editor.selected();
        if (object == null) { return; }
        try {
            editor.apply(new SceneObject(object.id(), object.kind(),
                    page == Page.POSITION ? vector() : object.position(),
                    page == Page.ROTATION ? vector() : object.rotation(),
                    page == Page.SCALE ? vector() : object.scale(),
                    page == Page.MATERIAL ? new LightVector(number("red"), number("green"), number("blue")) : object.color(),
                    page == Page.MATERIAL ? (float) number("emission") : object.emission(),
                    page == Page.MATERIAL ? (float) number("inner_radius") : object.innerRadius(),
                    page == Page.LIGHT ? (float) number("intensity") : object.intensity(),
                    page == Page.LIGHT ? (float) number("range") : object.range(),
                    page == Page.LIGHT ? (float) number("inner_degrees") : object.innerDegrees(),
                    page == Page.LIGHT ? (float) number("outer_degrees") : object.outerDegrees(), object.visible()));
        } catch (IllegalArgumentException invalid) { editor.error(invalid); }
    }

    private SceneObject copy(SceneObject object, boolean visible) {
        return new SceneObject(object.id(), object.kind(), object.position(), object.rotation(), object.scale(), object.color(),
                object.emission(), object.innerRadius(), object.intensity(), object.range(), object.innerDegrees(), object.outerDegrees(), visible);
    }

    private void browsePresets() {
        if (editor.presetNames().isEmpty()) { return; }
        int index = editor.presetNames().indexOf(preset.getValue());
        preset.setValue(editor.presetNames().get((index + 1) % editor.presetNames().size()));
    }

    @Override
    public void tick() {
        if (minecraft.level == null) { onClose(); return; }
        if (seenRevision != editor.revision()) { rebuildWidgets(); }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (compactFallback) {
            graphics.fill(0, 0, width, height, 0xE50B101A);
            graphics.drawWordWrap(font, SceneEditor.text("small_window"), 10, 35, Math.max(20, width - 20), 0xFFDEE7F3);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.fill(0, 0, width, 49, 0xEE0B101A);
        graphics.fill(4, 50, leftWidth, height - 34, 0xE5151D29);
        graphics.fill(rightX, 50, width - 4, height - 34, 0xE5151D29);
        graphics.fill(0, height - 33, width, height, 0xEE0B101A);
        graphics.drawString(font, title, 10, 9, 0xFFA2EDDA);
        int toolbarWidth = Math.max(39, (width - 16) / 7);
        graphics.fill(6 + toolbarWidth * 2, 45, 6 + toolbarWidth * 3 - 2, 47,
                editor.preview() ? 0xFFA2EDDA : 0xFF785348);
        graphics.fill(6 + toolbarWidth * 3, 45, 6 + toolbarWidth * 4 - 2, 47,
                editor.options().flashlight() ? 0xFFA2EDDA : 0xFF785348);
        graphics.drawString(font, SceneEditor.text("objects", document().objects().size(), SceneDocument.MAX_OBJECTS),
                10, 53, 0xFFD2DFEB);
        String selection = editor.selected() == null ? SceneEditor.text("inspector").getString() : editor.selected().id();
        graphics.drawString(font, selection, rightX + 8, 53, 0xFFD2DFEB);
        int index = 0;
        for (String key : fields.keySet()) {
            graphics.drawString(font, SceneEditor.text("field." + key), rightX + 8, fieldStart + index++ * fieldSpacing + 5, 0xFFADC0D3);
        }
        int centerWidth = rightX - leftWidth - 12;
        if (centerWidth > 60) {
            String dimension = document().dimension();
            graphics.drawString(font, font.plainSubstrByWidth(dimension, centerWidth), leftWidth + 6, 55, 0xFFA2EDDA);
            Component hint = SceneEditor.text(editor.inDimension() ? editor.preview() ? "preview_on" : "preview_off" : "wrong_dimension");
            graphics.drawWordWrap(font, hint, leftWidth + 6, 70, centerWidth, 0xFFDEE7F3);
            graphics.drawWordWrap(font, SceneEditor.text("look_hint"), leftWidth + 6, height - 78, centerWidth, 0xFFDEE7F3);
        }
        graphics.drawString(font, font.plainSubstrByWidth(editor.status().getString(), width - 20), 10, height - 9, 0xFFFFD596);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Do not blur/dim the live preview with Minecraft's usual menu background. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_F7) { onClose(); return true; }
        if (!(getFocused() instanceof EditBox) && hasControlDown()) {
            if (key == GLFW.GLFW_KEY_Z) { editor.undo(); return true; }
            if (key == GLFW.GLFW_KEY_Y) { editor.redo(); return true; }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!compactFallback && x < leftWidth && y >= 110 && y < height - 100) {
            firstRow = Math.clamp(firstRow - (int) Math.signum(scrollY), 0, Math.max(0, document().objects().size() - rowCount));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
