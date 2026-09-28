package dev.lexawhatt.astraengine.client.editor;

import dev.lexawhatt.astraengine.client.render.EditableShaderPass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Native nonpausing GLSL text editor with explicit compilation and scrollable driver diagnostics. */
public final class ShaderEditorScreen extends Screen {
    private final Screen parent;
    private final EditableShaderPass service;
    private String fileName = "my_effect";
    private MultiLineEditBox source;
    private MultiLineEditBox diagnostic;
    private EditBox name;
    private Button save;
    private Button load;
    private Button enable;
    private int rightX;
    private int rightWidth;
    private int diagnosticBottom;

    /** Returns to the supplied parent, retaining draft text in the shared client service. */
    public ShaderEditorScreen(Screen parent, EditableShaderPass service) {
        super(text("title"));
        if (service == null) { throw new IllegalArgumentException("Shader editor requires an owned shader pass"); }
        this.parent = parent;
        this.service = service;
    }

    /** Exposes read-only status/explicit actions to native verification and embedding tools. */
    public EditableShaderPass shaderService() { return service; }

    @Override
    protected void init() {
        int margin = 10;
        int sourceWidth = Math.max(140, (int) ((width - 38) * 0.64));
        int sourceHeight = Math.max(54, height - 128);
        rightX = margin + sourceWidth + 18;
        rightWidth = width - rightX - 18;
        int nameWidth = Math.min(180, Math.max(72, width - 224));
        name = addRenderableWidget(new EditBox(font, margin, 42, nameWidth, 20, text("name")));
        name.setMaxLength(48);
        name.setValue(fileName);
        name.setResponder(value -> fileName = value);
        int x = margin + nameWidth + 5;
        save = button("save", x, 42, 48, () -> service.save(name.getValue()));
        load = button("load", x + 52, 42, 48, () -> service.load(name.getValue()));
        button("example", x + 104, 42, 72, () -> {
            service.setDraft(EditableShaderPass.EXAMPLE_SOURCE);
            source.setValue(service.draft());
        });
        source = addRenderableWidget(new MultiLineEditBox(font, margin, 80, sourceWidth, sourceHeight,
                text("placeholder"), text("source")));
        source.setCharacterLimit(EditableShaderPass.MAX_SOURCE_BYTES);
        source.setValue(service.draft());
        source.setValueListener(service::setDraft);
        int logHeight = Math.max(42, sourceHeight / 2);
        diagnostic = addRenderableWidget(new ReadOnlyTextBox(rightX, 80, rightWidth, logHeight));
        diagnostic.setValue(service.diagnostics());
        diagnosticBottom = 80 + logHeight;
        button("apply", margin, height - 26, 76, () -> service.apply(source.getValue()));
        enable = button(service.enabled() ? "disable" : "enable", margin + 80, height - 26, 76,
                () -> service.setEnabled(!service.enabled()));
        button("back", width - margin - 64, height - 26, 64, this::onClose);
        setInitialFocus(source);
        updateControls();
    }

    private Button button(String key, int x, int y, int buttonWidth, Runnable action) {
        return addRenderableWidget(Button.builder(text(key), ignored -> action.run())
                .bounds(x, y, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        // Async load changes only the shared draft; user edits already update it synchronously.
        if (!source.getValue().equals(service.draft())) { source.setValue(service.draft()); }
        if (!diagnostic.getValue().equals(service.diagnostics())) { diagnostic.setValue(service.diagnostics()); }
        updateControls();
    }

    private void updateControls() {
        save.active = !service.busy();
        load.active = !service.busy();
        enable.active = service.hasProgram();
        enable.setMessage(text(service.enabled() ? "disable" : "enable"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, 73, 0xD5091320);
        graphics.fill(0, height - 34, width, height, 0xD5091320);
        graphics.drawString(font, title, 10, 9, 0xFFE0F3FF);
        graphics.drawString(font, font.plainSubstrByWidth(text("hint").getString(), width - 20), 10, 24, 0xFF93ABBD);
        graphics.drawString(font, text("source"), 10, 68, 0xFFB9D4E8);
        graphics.drawString(font, text("diagnostics"), rightX, 68, 0xFFB9D4E8);
        if (diagnosticBottom + 14 < height - 40) {
            graphics.enableScissor(rightX, diagnosticBottom + 14, width - 10, height - 40);
            graphics.drawWordWrap(font, text("interface"), rightX, diagnosticBottom + 14, rightWidth, 0xFFE0EAF4);
            graphics.disableScissor();
        }
        graphics.drawString(font, font.plainSubstrByWidth(service.status().getString(), Math.max(30, width - 258)),
                174, height - 20, 0xFFC5DFCF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Keeps the world and live shader preview visible instead of applying the normal pause-screen blur. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_ENTER) {
            service.apply(source.getValue());
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_S) {
            service.save(name.getValue());
            return true;
        }
        if (source.isFocused() && keyCode == GLFW.GLFW_KEY_TAB && !hasControlDown() && !hasAltDown()) {
            for (int index = 0; index < 4; index++) { source.charTyped(' ', 0); }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) { minecraft.setScreen(parent); }
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("astraengine.shader." + key, arguments);
    }

    private final class ReadOnlyTextBox extends MultiLineEditBox {
        private ReadOnlyTextBox(int x, int y, int boxWidth, int boxHeight) {
            super(ShaderEditorScreen.this.font, x, y, boxWidth, boxHeight, text("diagnostic_empty"), text("diagnostics"));
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) { return false; }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (Screen.isCopy(keyCode) || Screen.isSelectAll(keyCode)
                    || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
                    || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                    || keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END
                    || keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            return false;
        }
    }
}
