package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Client-local inspection light. No item, server illumination or resource economy is implied. */
public final class FlashlightController {
    private final KeyMapping key = new KeyMapping("key.astraengine.flashlight", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.astraengine");
    private final RenderOptions options;

    /** Shares the renderer's connection-scoped light switch. */
    public FlashlightController(RenderOptions options) { this.options = options; }

    /** Host Controls owns rebinding and persistence. */
    public void registerKeys(RegisterKeyMappingsEvent event) { event.register(key); }

    /** Drain menu clicks without replaying them after closing a screen. */
    public void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        while (key.consumeClick()) {
            if (game.level != null && game.player != null && game.screen == null
                    && !game.isPaused() && game.isWindowActive()) {
                options.toggleFlashlight();
            }
        }
    }

    /** Never carry an enabled lamp or a queued key press into another connection. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        options.clearFlashlight();
        while (key.consumeClick()) { }
    }
}
