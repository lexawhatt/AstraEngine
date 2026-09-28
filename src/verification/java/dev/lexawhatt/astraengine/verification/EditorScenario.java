package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.editor.SceneEditorScreen;
import dev.lexawhatt.astraengine.client.editor.ShaderEditorScreen;
import dev.lexawhatt.astraengine.client.scene.SceneDocument;
import dev.lexawhatt.astraengine.client.scene.SceneObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Exercises the actual editor widgets and native frame output in a disposable local world. */
final class EditorScenario {
    private static final String PRESET = "verification-scene";
    private static final String INVALID_PRESET = "verification-invalid";
    private static final String SHADER_NAME = "verification-tint";
    private static final String VALID_SHADER = """
            #version 150
            uniform sampler2D SceneColor;
            in vec2 texCoord;
            out vec4 fragColor;
            void main() {
                vec4 scene = texture(SceneColor, texCoord);
                fragColor = vec4(scene.rgb * vec3(0.35, 0.8, 1.0) + vec3(0.0, 0.06, 0.1), scene.a);
            }
            """;
    private static final List<String> PAGES = List.of("position", "rotation", "scale", "material", "light");
    private static final List<String> KINDS = List.of("sphere", "box", "ring", "disk", "point", "spot", "directional");
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private SceneDocument expected;
    private int step;
    private int ticks;
    private long editorStartTime;
    private int[] enabledPixels;
    private double previewDifference;
    private int[] shaderPixels;
    private double shaderDifference;
    private boolean checkedReservedUniform;
    private boolean minimumScaleConfigured;

    EditorScenario(boolean restart) {
        this.restart = restart;
        AstraEngine.LOGGER.info("ASTRA_EDITOR_GRAPHICS {} transparency={} restart={}",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency(), restart);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (minecraft.screen instanceof SceneEditorScreen screen && screen.busy()) { return false; }
        if (minecraft.screen instanceof ShaderEditorScreen screen && screen.shaderService().busy()) { return false; }
        ticks++;
        look();
        return restart ? restartTick() : createTick();
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                server(this::prepareWorld);
                command("astra-render environment space");
                command("astra-render quality high");
                command("astra-render bloom true");
                next();
            }
            case 1 -> {
                if (ticks < 70) { return false; }
                command("astra-editor");
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().objects().isEmpty(), "New client session must start with an empty scene");
                require(!editor().isPauseScreen(), "Editor must keep the integrated world running");
                editorStartTime = minecraft.level.getGameTime();
                create("sphere", -2.5, 201.7, 8);
                vector("scale", 1.15, 1.15, 1.15);
                material(0.08, 0.45, 1, 0.5);
                next();
            }
            case 3 -> {
                create("box", 3, 201.5, 9);
                vector("scale", 0.9, 1.1, 0.9);
                vector("rotation", 12, 28, 5);
                material(1, 0.3, 0.08, 0.25);
                next();
            }
            case 4 -> {
                create("ring", 0.5, 202.2, 10.5);
                vector("scale", 1.8, 1.8, 1.8);
                vector("rotation", 65, 15, 0);
                material(0.95, 0.65, 0.1, 1);
                next();
            }
            case 5 -> {
                create("disk", 0.5, 200.7, 7);
                vector("scale", 0.65, 0.65, 0.65);
                vector("rotation", 90, 0, 0);
                material(0.55, 0.1, 0.95, 0.2);
                next();
            }
            case 6 -> {
                create("point", -3, 202.5, 4);
                material(0.02, 0.15, 1, 0);
                page("light");
                field("intensity", "4");
                field("range", "14");
                click("apply");
                next();
            }
            case 7 -> {
                create("spot", 3, 203, 2);
                vector("rotation", 10, 0, 0);
                material(1, 0.2, 0.02, 0);
                page("light");
                field("intensity", "5");
                field("range", "18");
                field("inner_degrees", "15");
                field("outer_degrees", "30");
                click("apply");
                verifyFixture(editor().document());
                expected = editor().document();
                next();
            }
            case 8 -> {
                if (ticks < 40) { return false; }
                require(minecraft.level.getGameTime() > editorStartTime + 20,
                        "World time stopped while editor was open");
                click("duplicate");
                require(editor().document().objects().size() == 7, "Duplicate widget did not add an object");
                click("undo");
                require(editor().document().equals(expected), "Undo did not restore the original scene");
                click("redo");
                require(editor().document().objects().size() == 7, "Redo did not restore the duplicate");
                click("delete");
                require(editor().document().equals(expected), "Deleting the selected duplicate changed another object");
                clickLabel("spot_1");
                page("position");
                field("x", "NaN");
                click("apply");
                require(editor().document().equals(expected), "Invalid numeric input mutated the scene");
                field("x", "3");
                preset(PRESET);
                click("save");
                next();
            }
            case 9 -> {
                if (ticks < 15) { return false; }
                require(Files.isRegularFile(presetPath(PRESET)), "Save widget did not write a preset");
                shot("01-editor-six-objects");
                click("delete");
                require(editor().document().objects().size() == 5, "Delete widget did not remove the selection");
                click("load");
                next();
            }
            case 10 -> {
                if (ticks < 15) { return false; }
                require(editor().document().equals(expected), "Preset load did not restore every edited field");
                Files.writeString(presetPath(INVALID_PRESET), "{\"version\":999,\"objects\":[]}");
                preset(INVALID_PRESET);
                click("load");
                next();
            }
            case 11 -> {
                if (ticks < 15) { return false; }
                require(editor().document().equals(expected), "Rejected preset replaced the working scene");
                shot("02-editor-rejected-preset");
                preset(PRESET);
                click("done");
                next();
            }
            case 12 -> {
                if (ticks < 30) { return false; }
                require(minecraft.screen == null, "Done widget did not return to the world");
                enabledPixels = shot("03-scene-preview-on");
                command("astra-editor");
                next();
            }
            case 13 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().equals(expected), "Reopening the editor lost the current scene");
                click("preview");
                click("done");
                next();
            }
            case 14 -> {
                if (ticks < 30) { return false; }
                int[] disabled = shot("04-scene-preview-off");
                previewDifference = difference(enabledPixels, disabled);
                require(previewDifference > 0.003, "Preview toggle did not visibly alter the scene: " + previewDifference);
                command("astra-editor");
                next();
            }
            case 15 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                click("preview");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 16 -> {
                if (ticks < 30) { return false; }
                for (String inspectorPage : PAGES) {
                    page(inspectorPage);
                    assertWidgetBounds();
                }
                page("position");
                require(editor().document().equals(expected), "Window resize mutated the scene");
                shot("05-editor-resized");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 17 -> {
                if (ticks < 30) { return false; }
                assertWidgetBounds();
                require(editor().document().equals(expected), "Resource reload lost the active scene");
                shot("06-editor-after-reload");
                click("done");
                next();
            }
            case 18 -> {
                if (ticks < 30) { return false; }
                shot("07-scene-after-reload");
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F7));
                next();
            }
            case 19 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().equals(expected), "F7 reopen did not preserve the document");
                if (ticks < 20) { return false; }
                shot("08-editor-keybinding");
                click("shaders");
                next();
            }
            case 20 -> {
                require(!shader().shaderService().hasProgram(), "GLSL must not compile automatically when its editor opens");
                shaderSource(VALID_SHADER);
                shaderInput(SHADER_NAME);
                shaderClick("apply");
                require(shader().shaderService().hasProgram() && shader().shaderService().enabled(),
                        "Apply did not create an enabled shader program: " + shader().shaderService().diagnostics());
                require(shader().shaderService().appliedSource().equals(VALID_SHADER), "Applied GLSL differs from editor input");
                shaderClick("save");
                next();
            }
            case 21 -> {
                if (ticks < 20) { return false; }
                require(Files.readString(shaderPath()).equals(VALID_SHADER), "GLSL Save did not preserve source text");
                shot("11-glsl-applied");
                shaderSource("#version 150\nout vec4 fragColor;\nvoid main() { fragColor = ; }\n");
                shaderClick("apply");
                require(shader().shaderService().hasProgram() && shader().shaderService().enabled()
                                && shader().shaderService().appliedSource().equals(VALID_SHADER),
                        "A compiler error discarded the last working shader program");
                require(shader().shaderService().diagnostics().toLowerCase(java.util.Locale.ROOT).contains("failed"),
                        "Invalid GLSL did not expose compiler diagnostics");
                next();
            }
            case 22 -> {
                if (ticks < 20) { return false; }
                if (!checkedReservedUniform) {
                    shot("12-glsl-compiler-error");
                    shaderSource("""
                            #version 150
                            uniform sampler2D SceneColor;
                            uniform ivec2 Time[2];
                            in vec2 texCoord;
                            out vec4 fragColor;
                            void main() {
                                vec4 scene = texture(SceneColor, texCoord);
                                fragColor = scene + vec4(float(Time[1].x) * 0.01);
                            }
                            """);
                    shaderClick("apply");
                    require(shader().shaderService().hasProgram() && shader().shaderService().enabled()
                                    && shader().shaderService().appliedSource().equals(VALID_SHADER),
                            "A reserved uniform type/array mismatch replaced the working shader");
                    require(GL11.glGetError() == GL11.GL_NO_ERROR, "Reserved uniform rejection produced a GL error");
                    checkedReservedUniform = true;
                    ticks = 0;
                    return false;
                }
                shot("12b-glsl-uniform-rejected");
                shaderClick("load");
                next();
            }
            case 23 -> {
                if (ticks < 20) { return false; }
                require(shader().shaderService().draft().equals(VALID_SHADER), "GLSL Load did not restore saved source text");
                require(shader().shaderService().appliedSource().equals(VALID_SHADER), "Loading text changed the applied program");
                shaderClick("back");
                require(editor().document().equals(expected), "Opening the GLSL editor mutated the scene document");
                click("done");
                next();
            }
            case 24 -> {
                if (ticks < 30) { return false; }
                shaderPixels = shot("13-glsl-last-good-world");
                command("astra-editor");
                next();
            }
            case 25 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                click("shaders");
                shaderClick("disable");
                require(!shader().shaderService().enabled(), "Disable widget did not disable the editable shader");
                shaderClick("back");
                click("done");
                next();
            }
            case 26 -> {
                if (ticks < 30) { return false; }
                int[] disabledShader = shot("14-glsl-disabled-world");
                shaderDifference = difference(shaderPixels, disabledShader);
                require(shaderDifference > 0.01, "Editable GLSL did not visibly affect the world: " + shaderDifference);
                command("astra-editor");
                next();
            }
            case 27 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                next();
            }
            case 28 -> {
                if (ticks < 20) { return false; }
                if (!minimumScaleConfigured) {
                    // GLFW resizing is asynchronous. Use the settled framebuffer before selecting auto scale.
                    // Forced Unicode rounds the normal maximum scale of three up to four at 1280x720.
                    minecraft.options.forceUnicodeFont().set(true);
                    minecraft.options.guiScale().set(0);
                    minecraft.resizeDisplay();
                    minimumScaleConfigured = true;
                    ticks = 0;
                    return false;
                }
                require(editor().width == 320 && editor().height == 180,
                        "Expected minimum logical screen 320x180, got " + editor().width + "x" + editor().height);
                require(editor().document().equals(expected), "Small-screen fallback changed the scene");
                shot("15-editor-small-screen-fallback");
                click("smaller_ui");
                next();
            }
            case 29 -> {
                if (ticks < 20) { return false; }
                require(editor().width >= 640 && editor().height >= 360, "Smaller UI action did not restore usable controls");
                require(editor().document().equals(expected), "Recovering the small-screen UI lost the scene");
                shot("16-editor-small-screen-recovered");
                minecraft.options.forceUnicodeFont().set(false);
                minecraft.options.guiScale().set(2);
                minecraft.resizeDisplay();
                click("shaders");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 30 -> {
                if (ticks < 30) { return false; }
                require(!shader().shaderService().hasProgram(), "Resource reload retained the old editable GPU program");
                require(shader().shaderService().draft().equals(VALID_SHADER), "Resource reload discarded the GLSL draft");
                shot("17-glsl-after-resource-reload");
                shaderClick("apply");
                require(shader().shaderService().hasProgram(), "GLSL could not be reapplied after resource reload");
                shaderClick("back");
                click("done");
                next();
            }
            case 31 -> {
                command("astra visit alpha");
                next();
            }
            case 32 -> {
                if (!minecraft.level.dimension().location().toString().equals("astraengine:alpha") || ticks < 100) {
                    return false;
                }
                shot("19-foreign-dimension");
                command("astra-editor");
                next();
            }
            case 33 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().equals(expected), "Travel discarded the dimension-scoped scene draft");
                require(!findButton(label("add")).active, "Editor allowed adding an object into a different dimension");
                if (ticks < 20) { return false; }
                shot("20-foreign-dimension-editor");
                click("done");
                command("astra leave");
                next();
            }
            case 34 -> {
                if (!minecraft.level.dimension().location().toString().equals("minecraft:overworld") || ticks < 60) {
                    return false;
                }
                shot("21-scene-after-return");
                server(server -> require(server.overworld().getBlockState(new BlockPos(-1, 201, 5))
                        .is(Blocks.CHISELED_STONE_BRICKS), "Scene editing or travel changed the real fixture blocks"));
                command("astra-editor");
                next();
            }
            case 35 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().equals(expected) && findButton(label("add")).active,
                        "Returning home did not reactivate the retained scene document");
                click("done");
                next();
            }
            case 36 -> {
                Files.writeString(minecraft.gameDirectory.toPath().resolve("editor-measurements.txt"),
                        "Preview on/off RGB mean absolute difference=" + previewDifference
                                + "\nEditable GLSL enabled/disabled RGB mean absolute difference=" + shaderDifference
                                + "\nObjects=6; shapes=4; lights=2\nWidget interaction, undo/redo, rejected numeric input, "
                                + "save/load, rejected preset, resize, reload, F7 reopen, GLSL compile/error recovery, "
                                + "reserved uniform rejection, source save/load, effect disable, minimum-screen recovery, "
                                + "shader reload disposal and dimension isolation/return passed.\n");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected editor fixture step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 50) { return false; }
                command("astra-render environment space");
                command("astra-render quality high");
                command("astra-editor");
                next();
            }
            case 1 -> {
                if (!(minecraft.screen instanceof SceneEditorScreen)) { return false; }
                require(editor().document().objects().isEmpty(), "Client login unexpectedly auto-applied a local preset");
                require(Files.isRegularFile(presetPath(PRESET)), "Persisted preset missing after JVM restart");
                preset(PRESET);
                click("load");
                next();
            }
            case 2 -> {
                if (ticks < 30) { return false; }
                verifyFixture(editor().document());
                shot("09-editor-restart-loaded");
                click("shaders");
                require(!shader().shaderService().hasProgram(), "Restart unexpectedly auto-compiled a persisted shader");
                shaderInput(SHADER_NAME);
                shaderClick("load");
                next();
            }
            case 3 -> {
                if (ticks < 20) { return false; }
                require(shader().shaderService().draft().equals(VALID_SHADER), "Saved GLSL text lost across JVM restart");
                require(!shader().shaderService().hasProgram(), "Load unexpectedly compiled a shader without Apply");
                shaderClick("apply");
                require(shader().shaderService().hasProgram(), "Persisted GLSL failed to compile after restart");
                next();
            }
            case 4 -> {
                if (ticks < 20) { return false; }
                shot("18-glsl-restart-loaded");
                shaderClick("back");
                click("done");
                next();
            }
            case 5 -> {
                if (ticks < 30) { return false; }
                shot("10-scene-restart-loaded");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected editor restart step " + step);
        }
        return false;
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(100000, 0, false, false);
        for (int x = -9; x <= 9; x++) {
            for (int z = -5; z <= 15; z++) {
                for (int y = 199; y <= 207; y++) {
                    boolean floor = y == 199;
                    boolean back = z == 15 && y <= 204;
                    level.setBlockAndUpdate(new BlockPos(x, y, z), floor || back
                            ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int y = 200; y <= 202; y++) {
            level.setBlockAndUpdate(new BlockPos(-1, y, 5), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }

    private void create(String kind, double x, double y, double z) {
        for (int attempts = 0; attempts < KINDS.size(); attempts++) {
            Button selectedKind = choice("kind", KINDS);
            if (selectedKind.getMessage().getString().equals(label("kind." + kind))) {
                click("add");
                vector("position", x, y, z);
                return;
            }
            click(selectedKind);
        }
        throw new IllegalStateException("Could not select shape kind " + kind);
    }

    private void vector(String page, double x, double y, double z) {
        page(page);
        field("x", Double.toString(x));
        field("y", Double.toString(y));
        field("z", Double.toString(z));
        click("apply");
    }

    private void material(double red, double green, double blue, double emission) {
        page("material");
        field("red", Double.toString(red));
        field("green", Double.toString(green));
        field("blue", Double.toString(blue));
        field("emission", Double.toString(emission));
        click("apply");
    }

    private void page(String desired) {
        for (int attempts = 0; attempts < PAGES.size(); attempts++) {
            Button selectedPage = choice("page", PAGES);
            if (selectedPage.getMessage().getString().equals(label("page." + desired))) { return; }
            click(selectedPage);
        }
        throw new IllegalStateException("Could not select properties page " + desired);
    }

    private Button choice(String prefix, List<String> options) {
        return editor().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> options.stream().anyMatch(option -> button.getMessage().getString()
                        .equals(label(prefix + "." + option))))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing " + prefix + " cycle widget"));
    }

    private void field(String field, String value) {
        input(label("field." + field), value);
    }

    private void preset(String value) {
        input(label("preset"), value);
    }

    private void input(String label, String value) {
        Screen screen = minecraft.screen;
        require(screen != null, "No screen is open for input");
        EditBox box = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(candidate -> candidate.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing input widget " + label));
        click(box);
        int length = box.getValue().length();
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        for (int index = 0; index < length; index++) {
            screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        }
        for (int index = 0; index < value.length(); index++) {
            screen.charTyped(value.charAt(index), 0);
        }
        require(box.getValue().equals(value), "Native text input failed for " + label + ": " + box.getValue());
    }

    private void click(String key) {
        clickLabel(label(key));
    }

    private void clickLabel(String label) {
        click(findButton(label));
    }

    private Button findButton(String label) {
        require(minecraft.screen != null, "No screen is open for a button click");
        return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> candidate.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + label));
    }

    private void click(AbstractWidget widget) {
        require(widget.active && widget.visible, "Widget is inactive: " + widget.getMessage().getString());
        Screen screen = minecraft.screen;
        require(screen != null, "No screen is open for a widget click");
        double x = widget.getX() + widget.getWidth() / 2.0;
        double y = widget.getY() + widget.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Widget did not handle its click: " + widget.getMessage().getString());
        screen.mouseReleased(x, y, 0);
    }

    private void shaderClick(String key) {
        clickLabel(Component.translatable("astraengine.shader." + key).getString());
    }

    private void shaderInput(String value) {
        input(Component.translatable("astraengine.shader.name").getString(), value);
    }

    private void shaderSource(String source) {
        String label = Component.translatable("astraengine.shader.source").getString();
        MultiLineEditBox box = shader().children().stream().filter(MultiLineEditBox.class::isInstance)
                .map(MultiLineEditBox.class::cast).filter(candidate -> candidate.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing GLSL source editor"));
        click(box);
        // Text goes through the actual multiline widget and its change listener; Apply remains a native button click.
        box.setValue(source);
        require(box.getValue().equals(source), "GLSL source widget did not retain entered text");
    }

    private ShaderEditorScreen shader() {
        require(minecraft.screen instanceof ShaderEditorScreen, "GLSL editor is not active at step " + step);
        return (ShaderEditorScreen) minecraft.screen;
    }

    private Path shaderPath() {
        return minecraft.gameDirectory.toPath().resolve("config/astraengine/shaders/" + SHADER_NAME + ".fsh");
    }

    private void assertWidgetBounds() {
        List<AbstractWidget> widgets = editor().children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).toList();
        for (var child : editor().children()) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                require(widget.getX() >= 0 && widget.getY() >= 0
                                && widget.getX() + widget.getWidth() <= editor().width
                                && widget.getY() + widget.getHeight() <= editor().height,
                        "Visible editor widget lies outside resized screen: " + widget.getMessage().getString());
            }
        }
        for (int first = 0; first < widgets.size(); first++) {
            for (int second = first + 1; second < widgets.size(); second++) {
                AbstractWidget a = widgets.get(first);
                AbstractWidget b = widgets.get(second);
                boolean overlap = a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                        && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
                require(!overlap, "Editor widgets overlap: " + a.getMessage().getString() + " / "
                        + b.getMessage().getString());
            }
        }
    }

    private void verifyFixture(SceneDocument document) {
        require(document.dimension().equals("minecraft:overworld"), "Scene bound to the wrong dimension");
        require(document.objects().size() == 6, "Expected six edited objects, got " + document.objects().size());
        require(document.objects().stream().filter(object -> object.kind().isLight()).count() == 2,
                "Point and spot lights missing from edited scene");
        for (SceneObject.Kind kind : List.of(SceneObject.Kind.SPHERE, SceneObject.Kind.BOX, SceneObject.Kind.RING,
                SceneObject.Kind.DISK, SceneObject.Kind.POINT, SceneObject.Kind.SPOT)) {
            require(document.objects().stream().filter(object -> object.kind() == kind).count() == 1,
                    "Scene must contain exactly one " + kind);
        }
        SceneObject sphere = document.objects().stream().filter(object -> object.kind() == SceneObject.Kind.SPHERE)
                .findFirst().orElseThrow();
        require(Math.abs(sphere.position().x() + 2.5) < 0.0001 && Math.abs(sphere.position().y() - 201.7) < 0.0001
                        && Math.abs(sphere.scale().x() - 1.15) < 0.0001,
                "Edited transform was not retained");
        SceneObject box = document.objects().stream().filter(object -> object.kind() == SceneObject.Kind.BOX)
                .findFirst().orElseThrow();
        require(Math.abs(box.rotation().y() - 28) < 0.0001 && Math.abs(box.color().x() - 1) < 0.0001,
                "Edited rotation/material was not retained");
    }

    private int[] shot(String name) throws Exception {
        minecraft.gui.getChat().clearMessages(false);
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        int[] pixels;
        try (NativeImage frame = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            frame.writeToFile(path);
            // The central world area excludes HUD, hand, chat and the outer editor panels.
            int left = frame.getWidth() / 4;
            int right = frame.getWidth() * 3 / 4;
            int top = frame.getHeight() / 4;
            int bottom = frame.getHeight() * 3 / 4;
            pixels = new int[(right - left) * (bottom - top)];
            int index = 0;
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    pixels[index++] = frame.getPixelRGBA(x, y);
                }
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_EDITOR_SCREENSHOT {}", name);
        return pixels;
    }

    private static double difference(int[] first, int[] second) {
        require(first.length == second.length, "Preview comparison requires matching framebuffer dimensions");
        double sum = 0;
        for (int index = 0; index < first.length; index++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                sum += Math.abs(((first[index] >> shift) & 255) - ((second[index] >> shift) & 255));
            }
        }
        return sum / (first.length * 3.0 * 255);
    }

    private SceneEditorScreen editor() {
        require(minecraft.screen instanceof SceneEditorScreen, "Editor screen is not active at step " + step);
        return (SceneEditorScreen) minecraft.screen;
    }

    private Path presetPath(String name) {
        return minecraft.gameDirectory.toPath().resolve("config/astraengine/scenes/" + name + ".json");
    }

    private static String label(String key) {
        return Component.translatable("astraengine.editor." + key).getString();
    }

    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void look() { minecraft.player.setYRot(0); minecraft.player.setXRot(0); }

    private void next() {
        AstraEngine.LOGGER.info("ASTRA_EDITOR_STEP {} complete", step);
        step++;
        ticks = 0;
    }

    private void server(Consumer<MinecraftServer> action) {
        var server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
