package dev.lexawhatt.astraengine.client.map;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.Event;

/**
 * Physical-client, client-thread NeoForge game-bus event for every engine map entry point.
 * A consumer can replace the system map or atlas with its own Screen, using only the supplied access handle.
 * The default is retained unless replaced; later listeners see earlier replacements. Register exactly one
 * intended owner per view and use event priority when coordinating multiple consumers.
 */
public final class NavigationMapOpeningEvent extends Event {
    private final NavigationMapAccess.View view;
    private final NavigationMapAccess access;
    private final Screen defaultScreen;
    private Screen screen;

    /** Engine entry point. All fields are required and owned by the current physical client. */
    public NavigationMapOpeningEvent(NavigationMapAccess.View view, NavigationMapAccess access, Screen defaultScreen) {
        if (view == null || access == null || defaultScreen == null) {
            throw new IllegalArgumentException("Map opening requires a view, access and default screen");
        }
        this.view = view; this.access = access; this.defaultScreen = defaultScreen; screen = defaultScreen;
    }

    /** Which map the player requested. */
    public NavigationMapAccess.View view() { return view; }
    /** Connection-scoped read/action boundary for the consumer screen. */
    public NavigationMapAccess access() { return access; }
    /** Original engine screen, available to listeners that only augment it through host ScreenEvents. */
    public Screen defaultScreen() { return defaultScreen; }
    /** Screen currently selected for opening. */
    public Screen screen() { return screen; }
    /** Replaces the screen before host initialization. Null is rejected; do not recursively open a map here. */
    public void setScreen(Screen replacement) {
        if (replacement == null) { throw new IllegalArgumentException("A replacement map screen is required"); }
        screen = replacement;
    }
}
