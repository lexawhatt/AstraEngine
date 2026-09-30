package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import java.math.BigDecimal;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Actual map aim, speed command and held movement; optional already-authorized return frames an entered system. */
final class ManualSystemVisit {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final RocketController controller;
    private final String origin;
    private final String target;
    private final boolean observationAfterVisit;
    private final boolean rollDuringEntry;
    private double speed;
    private int step;
    private int ticks;
    private int movementTickLimit = 400;

    ManualSystemVisit(RocketController controller, String target, boolean observationAfterVisit) {
        this(controller, target, observationAfterVisit, false);
    }

    ManualSystemVisit(RocketController controller, String target, boolean observationAfterVisit, boolean rollDuringEntry) {
        this.rollDuringEntry = rollDuringEntry;
        this.controller = controller;
        this.origin = controller.snapshot().systemId();
        this.target = target;
        this.observationAfterVisit = observationAfterVisit;
        require(!origin.equals(target) && controller.snapshot().discoveredSystems().contains(target), "Manual target must be another charted system");
    }

    boolean tick() {
        ticks++;
        if (ticks >= (step == 3 ? movementTickLimit : 400)) {
            throw new IllegalStateException("Manual system visit timed out: " + diagnostics());
        }
        switch (step) {
            case 0 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { tap(GLFW.GLFW_KEY_M); }
                next();
            }
            case 1 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                click("nearby"); select(controller.system(target).name());
                if (!controller.snapshot().visitedSystems().contains(target)) {
                    require(!button(Component.translatable("astraengine.map.jump").getString()).active,
                            "An unvisited charted target enabled fast travel");
                }
                click("aim_system");
                SpaceVector destination = controller.system(target).galaxyPosition().subtract(controller.currentSystem().galaxyPosition())
                        .multiply(CosmosGenerator.LIGHT_YEAR);
                double distance = destination.distance(controller.snapshot().position());
                speed = Math.clamp(distance / 1.5, FlightDynamics.MIN_SPEED, FlightDynamics.MAX_SPEED);
                // A genuine intergalactic leg may take tens of seconds even at the maximum inspection speed.
                movementTickLimit = Math.clamp((int) Math.ceil(distance / speed * 20) + 200, 400, 3600);
                minecraft.player.connection.sendCommand("astra-flight speed " + BigDecimal.valueOf(speed).toPlainString());
                next();
            }
            case 2 -> {
                SpaceVector direction = controller.system(target).galaxyPosition().subtract(controller.currentSystem().galaxyPosition())
                        .multiply(CosmosGenerator.LIGHT_YEAR).subtract(controller.snapshot().position()).normalized();
                if (ticks < 15 || controller.orientation().forward().dot(direction) < 0.999999
                        || controller.snapshot().orientation().forward().dot(direction) < 0.999999
                        || !controller.orientation().equals(controller.targetOrientation())
                        || !controller.snapshot().orientation().equals(controller.targetOrientation())
                        || controller.snapshot().speedMetersPerSecond() != speed) { return false; }
                hold(GLFW.GLFW_KEY_W, true);
                if (rollDuringEntry) { hold(GLFW.GLFW_KEY_Q, true); }
                next();
            }
            case 3 -> {
                if (controller.snapshot().systemId().equals(origin)) { return false; }
                hold(GLFW.GLFW_KEY_W, false); hold(GLFW.GLFW_KEY_B, true);
                if (rollDuringEntry) { hold(GLFW.GLFW_KEY_Q, false); }
                require(controller.snapshot().systemId().equals(target), "Manual flight entered an unexpected intervening system");
                require(controller.snapshot().visitedSystems().contains(target), "Physical entry did not unlock the target");
                require(controller.snapshot().speedMetersPerSecond() <= FlightDynamics.LOCAL_MAX_SPEED,
                        "Manual system entry did not reduce inspection speed");
                AstraEngine.LOGGER.info("ASTRA_MANUAL_VISIT origin={} target={} entryPosition={} visited={}",
                        origin, target, controller.snapshot().position(), controller.snapshot().visitedSystems());
                next();
            }
            case 4 -> {
                if (ticks < 10) { return false; }
                hold(GLFW.GLFW_KEY_B, false);
                require(controller.snapshot().velocity().length() == 0, "Manual arrival did not stop after release/brake");
                if (!observationAfterVisit) { return true; }
                require(controller.snapshot().visitedSystems().contains(origin), "The return system is not unlocked");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, origin); next();
            }
            case 5 -> {
                if (ticks < 90 || controller.snapshot().jumpTicks() != 0 || !controller.snapshot().systemId().equals(origin)) { return false; }
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, target); next();
            }
            case 6 -> {
                return ticks >= 100 && controller.snapshot().jumpTicks() == 0 && controller.snapshot().systemId().equals(target);
            }
            default -> throw new IllegalStateException("Unknown manual visit stage " + step);
        }
        return false;
    }

    private String diagnostics() {
        return "stage=" + step + ", ticks=" + ticks + ", movementLimit=" + movementTickLimit + ", origin=" + origin + ", target=" + target
                + ", requestedSpeed=" + speed + ", acknowledgedSpeed=" + controller.snapshot().speedMetersPerSecond()
                + ", displayed=" + controller.orientation() + ", targetOrientation=" + controller.targetOrientation()
                + ", acknowledgedOrientation=" + controller.snapshot().orientation()
                + ", currentSystem=" + controller.snapshot().systemId() + ", position=" + controller.snapshot().position()
                + ", screen=" + minecraft.screen + ", focused=" + minecraft.isWindowActive();
    }

    private void select(String name) {
        for (int page = 0; page < 64; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(value -> value.getMessage().getString().equals(name) || value.getMessage().getString().equals("> " + name))
                    .findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            clickWidget(button(">"));
        }
        throw new IllegalStateException("Manual target missing from chart: " + name);
    }
    private void click(String key) { clickWidget(button(Component.translatable("astraengine.map." + key).getString())); }
    private Button button(String label) {
        return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private void clickWidget(Button button) {
        require(button.visible && button.active, "Manual visit widget unavailable: " + button.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Manual visit widget did not handle click"); screen.mouseReleased(x, y, 0);
    }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void next() { step++; ticks = 0; }
    private void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
