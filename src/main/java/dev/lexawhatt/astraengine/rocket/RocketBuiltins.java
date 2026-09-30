package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.List;

/** Diagnostic authoring parts using the same open descriptor contract as consumer-defined parts. */
public final class RocketBuiltins {
    private RocketBuiltins() {
    }

    /** A fresh immutable catalog containing the twelve diagnostic definitions. */
    public static RocketCatalog catalog() {
        return new RocketCatalog(definitions());
    }

    /** Immutable diagnostic definitions; consumers add their own namespaced identities during registration. */
    public static List<RocketPartDefinition> definitions() {
        return List.of(
                part("command_pod", "Command pod", "control", RocketGeometryKind.FRUSTUM,
                        new SpaceVector(2, 2, 2), 1, 0.25, new SpaceVector(0.85, 0.9, 0.95),
                        RocketMaterialStyle.WINDOW, RocketStandardModules.mass(800, 0),
                        RocketStandardModules.control(true, false), RocketStandardModules.power(-1, 2),
                        RocketStandardModules.thermal(0.3, 0)),
                part("fuel_tank", "Propellant tank", "propulsion", RocketGeometryKind.CYLINDER,
                        new SpaceVector(2, 4, 2), 1, 1, new SpaceVector(0.9, 0.9, 0.86),
                        RocketMaterialStyle.TANK, RocketStandardModules.mass(250, 2000)),
                part("engine", "Ideal thrust engine", "propulsion", RocketGeometryKind.FRUSTUM,
                        new SpaceVector(2, 1.5, 2), 1, 0.55, new SpaceVector(0.4, 0.45, 0.5),
                        RocketMaterialStyle.NOZZLE, RocketStandardModules.mass(500, 0),
                        RocketStandardModules.thrustEngine(120_000, 320), RocketStandardModules.power(-5, 0),
                        RocketStandardModules.thermal(120, 0)),
                part("rcs", "RCS controller", "control", RocketGeometryKind.BOX,
                        new SpaceVector(0.6, 0.6, 0.6), 1, 1, new SpaceVector(0.65, 0.7, 0.75),
                        RocketMaterialStyle.NOZZLE, RocketStandardModules.mass(30, 15),
                        RocketStandardModules.control(false, true), RocketStandardModules.power(-1, 0),
                        RocketStandardModules.thermal(2, 0)),
                part("reaction_wheel", "Reaction wheel", "control", RocketGeometryKind.CYLINDER,
                        new SpaceVector(1.8, 0.6, 1.8), 1, 1, new SpaceVector(0.65, 0.65, 0.75),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(80, 0),
                        RocketStandardModules.control(false, true), RocketStandardModules.power(-2, 0),
                        RocketStandardModules.thermal(0.8, 0)),
                part("radiator", "Radiator", "thermal", RocketGeometryKind.BOX,
                        new SpaceVector(2.5, 2, 0.15), 1, 1, new SpaceVector(0.48, 0.5, 0.55),
                        RocketMaterialStyle.RADIATOR, RocketStandardModules.mass(45, 0),
                        RocketStandardModules.power(-0.2, 0), RocketStandardModules.thermal(0, 50)),
                part("solar_panel", "Solar panel", "electrical", RocketGeometryKind.BOX,
                        new SpaceVector(3, 1.4, 0.12), 1, 1, new SpaceVector(0.08, 0.2, 0.65),
                        RocketMaterialStyle.SOLAR, RocketStandardModules.mass(35, 0),
                        RocketStandardModules.power(12, 0)),
                part("battery", "Battery", "electrical", RocketGeometryKind.CYLINDER,
                        new SpaceVector(1.8, 0.6, 1.8), 1, 1, new SpaceVector(0.32, 0.35, 0.4),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(100, 0),
                        RocketStandardModules.power(0, 100)),
                part("structure", "Structural segment", "structure", RocketGeometryKind.CYLINDER,
                        new SpaceVector(2, 0.8, 2), 1, 1, new SpaceVector(0.6, 0.62, 0.65),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(80, 0)),
                part("decoupler", "Decoupler", "structure", RocketGeometryKind.CYLINDER,
                        new SpaceVector(2, 0.35, 2), 1, 1, new SpaceVector(0.8, 0.5, 0.16),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(40, 0)),
                part("docking_port", "Docking port", "structure", RocketGeometryKind.CYLINDER,
                        new SpaceVector(1.5, 0.45, 1.5), 1, 1, new SpaceVector(0.65, 0.67, 0.7),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(70, 0),
                        RocketStandardModules.power(-0.1, 0), RocketStandardModules.thermal(0.05, 0)),
                part("warp_drive", "Warp drive demonstrator", "exotic", RocketGeometryKind.BOX,
                        new SpaceVector(2.4, 2, 2.4), 1, 1, new SpaceVector(0.32, 0.18, 0.6),
                        RocketMaterialStyle.PANEL, RocketStandardModules.mass(2000, 0),
                        RocketStandardModules.power(-5000, 10_000), RocketStandardModules.thermal(3000, 0),
                        new RocketModuleDefinition("astraengine:warp", "Unmodeled warp capability", List.of(
                                RocketParameterDefinition.flag("enabled", "Enabled", false),
                                RocketParameterDefinition.number("field_strength", "Field strength", "", 0, 100, 1),
                                RocketParameterDefinition.choice("mode", "Field mode",
                                        List.of("cruise", "burst"), "cruise")))));
    }

    /** Three-part command/tank/engine blueprint; the host offsets its minimum Y to place it on the ground. */
    public static RocketBlueprint starter(RocketCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("Starter rocket requires a catalog");
        }
        RocketBlueprint blueprint = new RocketBlueprint("Starter rocket", List.of(
                instance(catalog, 0, -1, "command_pod", new SpaceVector(0, 3, 0)),
                instance(catalog, 1, 0, "fuel_tank", SpaceVector.ZERO),
                instance(catalog, 2, 1, "engine", new SpaceVector(0, -2.75, 0))));
        catalog.validate(blueprint);
        return blueprint;
    }

    private static RocketPart instance(RocketCatalog catalog, int id, int parentId, String name,
                                       SpaceVector position) {
        String definitionId = "astraengine:" + name;
        return new RocketPart(id, parentId, definitionId, position,
                catalog.requireDefinition(definitionId).defaultSize(), 0, catalog.defaultValues(definitionId));
    }

    private static RocketPartDefinition part(String name, String displayName, String category,
                                             RocketGeometryKind geometry, SpaceVector size,
                                             double bottomRatio, double topRatio, SpaceVector color,
                                             RocketMaterialStyle material, RocketModuleDefinition... modules) {
        return new RocketPartDefinition("astraengine:" + name, displayName, "astraengine:" + category,
                new RocketVisual(geometry, bottomRatio, topRatio, color, material), size, List.of(modules));
    }
}
