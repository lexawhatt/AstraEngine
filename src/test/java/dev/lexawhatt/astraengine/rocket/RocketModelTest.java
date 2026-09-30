package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketModelTest {
    private final RocketCatalog catalog = RocketBuiltins.catalog();

    @Test
    void starterSharesValidTreeVisualBoundsAndPhysicalEstimates() {
        RocketBlueprint blueprint = RocketBuiltins.starter(catalog);
        catalog.validate(blueprint);
        assertEquals(3, blueprint.parts().size());
        assertEquals(new SpaceVector(2, 7.5, 2), RocketGeometry.bounds(blueprint).size());
        assertEquals(-3.5, RocketGeometry.bounds(blueprint).min().y());
        assertEquals(7, RocketGeometry.collisionBoxes(catalog, blueprint).size());
        RocketStats stats = RocketStats.calculate(catalog, blueprint);
        assertEquals(1550, stats.dryMassKg());
        assertEquals(2000, stats.fuelMassKg());
        assertEquals(3550, stats.wetMassKg());
        assertEquals(120_000, stats.thrustNewtons());
        assertEquals(320, stats.effectiveSpecificImpulseSeconds(), 1.0e-10);
        assertEquals(9.80665 * 320 * Math.log(3550.0 / 1550), stats.deltaVMetersPerSecond(), 1.0e-9);
        assertEquals(120_000 / (3550 * 9.80665), stats.thrustToWeightRatio(), 1.0e-12);
        assertEquals(2000 * 320 * 9.80665 / 120_000, stats.burnTimeSeconds(), 1.0e-9);
        assertEquals(-6, stats.powerKilowatts());
        assertTrue(stats.flightReady());
        assertTrue(stats.diagnostics().contains(RocketDiagnostic.NO_ATTITUDE_CONTROL));
    }

    @Test
    void customWormholeAndControllerSchemasRemainTypedImmutableAndUnmodeled() {
        List<String> modes = new ArrayList<>(List.of("local", "wormhole"));
        List<RocketParameterDefinition> fields = new ArrayList<>(List.of(
                RocketParameterDefinition.number("aperture", "Aperture", "m", 0.1, 100, 3),
                RocketParameterDefinition.flag("enabled", "Enabled", false),
                RocketParameterDefinition.choice("mode", "Mode", modes, "wormhole")));
        RocketModuleDefinition wormhole = new RocketModuleDefinition("solartech:wormhole", "Wormhole", fields);
        RocketModuleDefinition controller = new RocketModuleDefinition("solartech:adaptive_control", "Controller",
                List.of(RocketParameterDefinition.number("gain", "Gain", "", 0, 10, 0.7)));
        List<RocketPartDefinition> definitions = new ArrayList<>(catalog.definitions());
        definitions.add(new RocketPartDefinition("solartech:bridge", "Wormhole bridge", "solartech:exotic",
                new RocketVisual(RocketGeometryKind.BOX, 1, 1, new SpaceVector(0.2, 0.3, 0.8),
                        RocketMaterialStyle.PANEL), new SpaceVector(2, 1, 2),
                List.of(RocketStandardModules.mass(500, 0), wormhole, controller)));
        RocketCatalog consumer = new RocketCatalog(definitions);
        fields.clear();
        modes.clear();
        definitions.clear();
        RocketBlueprint draft = RocketPlacement.attach(consumer, new RocketBlueprint("Consumer", List.of()),
                -1, "solartech:bridge", RocketPlacement.Attachment.TOP, 1);
        RocketPart original = draft.requirePart(0);
        RocketPart changed = original.withValue(wormhole.id(), "aperture", RocketValue.number(12))
                .withValue(wormhole.id(), "enabled", RocketValue.flag(true));
        draft = draft.withPart(changed);
        consumer.validate(draft);
        assertEquals(3, original.moduleValues().get(wormhole.id()).get("aperture").number());
        assertEquals(12, changed.moduleValues().get(wormhole.id()).get("aperture").number());
        assertEquals("wormhole", changed.moduleValues().get(wormhole.id()).get("mode").choice());
        assertThrows(UnsupportedOperationException.class, () -> changed.moduleValues().clear());
        assertThrows(UnsupportedOperationException.class, () -> changed.moduleValues().get(wormhole.id()).clear());
        assertThrows(UnsupportedOperationException.class, () -> consumer.definitions().clear());
        RocketStats stats = RocketStats.calculate(consumer, draft);
        assertEquals(0, stats.thrustNewtons());
        assertEquals(0, stats.deltaVMetersPerSecond());
        assertTrue(stats.diagnostics().contains(RocketDiagnostic.UNMODELED_MODULES));
        assertFalse(stats.flightReady());
        RocketBlueprint valid = draft;
        assertThrows(IllegalArgumentException.class, () -> consumer.validate(valid.withPart(
                changed.withValue(wormhole.id(), "aperture", RocketValue.number(101)))));
        assertThrows(IllegalArgumentException.class, () -> consumer.validate(valid.withPart(
                changed.withValue(wormhole.id(), "mode", RocketValue.choice("other")))));
        assertThrows(IllegalArgumentException.class, () -> consumer.validate(valid.withPart(
                changed.withValue(wormhole.id(), "enabled", RocketValue.number(1)))));
    }

    @Test
    void nestedInstanceDataCannotBeMutatedAfterConstructionAndSchemasRequireCompleteFields() {
        RocketPart original = RocketBuiltins.starter(catalog).requirePart(0);
        Map<String, Map<String, RocketValue>> modules = new LinkedHashMap<>();
        original.moduleValues().forEach((id, values) -> modules.put(id, new LinkedHashMap<>(values)));
        RocketPart copy = replaceValues(original, modules);
        modules.get(RocketStandardModules.MASS).clear();
        modules.clear();
        catalog.validate(copy);
        assertEquals(original, copy);
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(replaceValues(original, Map.of())));
        Map<String, Map<String, RocketValue>> extra = new LinkedHashMap<>(copy.moduleValues());
        Map<String, RocketValue> mass = new LinkedHashMap<>(extra.get(RocketStandardModules.MASS));
        mass.put("extra", RocketValue.number(1));
        extra.put(RocketStandardModules.MASS, mass);
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(replaceValues(original, extra)));
        RocketPart absent = new RocketPart(0, -1, "missing:part", SpaceVector.ZERO, original.size(), 0, Map.of());
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(absent));
        assertTrue(catalog.findDefinition("missing:part").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.findDefinition(null));
    }

    @Test
    void treeValidationHandlesUnorderedTreesAndRejectsCyclesOrphansDuplicateIdsAndMultipleRoots() {
        RocketBlueprint starter = RocketBuiltins.starter(catalog);
        RocketPart root = starter.requirePart(0);
        RocketPart tank = starter.requirePart(1);
        RocketPart engine = starter.requirePart(2);
        assertEquals(3, new RocketBlueprint("Unordered", List.of(engine, tank, root)).parts().size());
        assertThrows(IllegalArgumentException.class, () -> new RocketBlueprint("Duplicate", List.of(root, root)));
        assertThrows(IllegalArgumentException.class, () -> new RocketBlueprint("Missing", List.of(root, engine)));
        assertThrows(IllegalArgumentException.class, () -> new RocketBlueprint("Roots",
                List.of(root, withParent(tank, -1))));
        assertThrows(IllegalArgumentException.class, () -> new RocketBlueprint("Cycle",
                List.of(root, withParent(tank, 2), engine)));
        assertThrows(IllegalArgumentException.class, () -> new RocketPart(0, 0, root.definitionId(),
                root.position(), root.size(), 0, root.moduleValues()));
    }

    @Test
    void radialSymmetryUsesParentYawAndConservativeFaceContacts() {
        RocketPart root = new RocketPart(10, -1, "astraengine:structure", new SpaceVector(3, 1, -2),
                new SpaceVector(4, 1, 2), 1, catalog.defaultValues("astraengine:structure"));
        RocketBlueprint before = new RocketBlueprint("Radial", List.of(root));
        RocketBlueprint after = RocketPlacement.attach(catalog, before, 10, "astraengine:solar_panel",
                RocketPlacement.Attachment.RADIAL, 4);
        assertEquals(1, before.parts().size());
        assertEquals(5, after.parts().size());
        List<RocketPlacement.AttachmentPoint> sockets = RocketPlacement.attachmentPoints(root);
        assertEquals(6, sockets.size());
        for (int i = 0; i < 4; i++) {
            RocketPart child = after.parts().get(i + 1);
            RocketPlacement.AttachmentPoint socket = sockets.get(i + 2);
            assertEquals((1 + i) % 4, child.yawQuarterTurns());
            assertEquals(root.id(), child.parentId());
            assertEquals(1, child.position().y());
            SpaceVector offset = child.position().subtract(socket.position());
            double childHalf = Math.abs(socket.normal().x()) * RocketGeometry.bounds(child).size().x() * 0.5
                    + Math.abs(socket.normal().z()) * RocketGeometry.bounds(child).size().z() * 0.5;
            assertEquals(childHalf, offset.dot(socket.normal()), 1.0e-12);
            assertEquals(childHalf, offset.length(), 1.0e-12);
        }
        assertEquals(new SpaceVector(0, 0, -1), sockets.get(2).normal());
        RocketBlueprint pair = RocketPlacement.attach(catalog, before, 10, "astraengine:rcs",
                RocketPlacement.Attachment.RADIAL, 2);
        assertEquals(0, pair.parts().get(1).position().subtract(root.position()).distance(
                pair.parts().get(2).position().subtract(root.position()).multiply(-1)), 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> RocketPlacement.attach(catalog, before, 10,
                "astraengine:rcs", RocketPlacement.Attachment.TOP, 2));
    }

    @Test
    void axialAttachmentsAndSubtreeRemovalPreserveUnrelatedStableIds() {
        RocketBlueprint starter = RocketBuiltins.starter(catalog);
        RocketBlueprint expanded = RocketPlacement.attach(catalog, starter, 0, "astraengine:docking_port",
                RocketPlacement.Attachment.TOP, 1);
        RocketPart docking = expanded.parts().getLast();
        assertEquals(RocketGeometry.bounds(starter.requirePart(0)).max().y(),
                RocketGeometry.bounds(docking).min().y(), 1.0e-12);
        RocketBlueprint removed = RocketPlacement.removeSubtree(expanded, 1);
        assertEquals(List.of(0, docking.id()), removed.parts().stream().map(RocketPart::id).toList());
        assertEquals(docking, removed.requirePart(docking.id()));
        assertTrue(RocketPlacement.removeSubtree(expanded, 0).parts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RocketPlacement.removeSubtree(expanded, 99));
        RocketBlueprint empty = new RocketBlueprint("Empty", List.of());
        RocketBlueprint created = RocketPlacement.attach(catalog, empty, -1, "astraengine:structure",
                RocketPlacement.Attachment.TOP, 1);
        assertEquals(SpaceVector.ZERO, created.requirePart(0).position());
    }

    @Test
    void boundsAndPartCountAreEnforcedAtomically() {
        RocketPart tall = new RocketPart(0, -1, "astraengine:structure", SpaceVector.ZERO,
                new SpaceVector(16, 24, 16), 0, catalog.defaultValues("astraengine:structure"));
        RocketBlueprint full = new RocketBlueprint("Full envelope", List.of(tall));
        assertThrows(IllegalArgumentException.class, () -> RocketPlacement.attach(catalog, full, 0,
                "astraengine:structure", RocketPlacement.Attachment.TOP, 1));
        assertEquals(1, full.parts().size());
        List<RocketPart> parts = new ArrayList<>();
        for (int id = 0; id < 32; id++) {
            parts.add(new RocketPart(id, id == 0 ? -1 : 0, "astraengine:structure", SpaceVector.ZERO,
                    new SpaceVector(1, 1, 1), 0, catalog.defaultValues("astraengine:structure")));
        }
        RocketBlueprint maximum = new RocketBlueprint("Maximum", parts);
        assertThrows(IllegalArgumentException.class, () -> RocketPlacement.attach(catalog, maximum, 0,
                "astraengine:structure", RocketPlacement.Attachment.RADIAL, 1));
        parts.add(new RocketPart(32, 0, "astraengine:structure", SpaceVector.ZERO,
                new SpaceVector(1, 1, 1), 0, catalog.defaultValues("astraengine:structure")));
        assertThrows(IllegalArgumentException.class, () -> new RocketBlueprint("Too many", parts));
        assertEquals(32, maximum.parts().size());
        assertThrows(IllegalArgumentException.class, () -> new RocketPart(0, -1, "astraengine:structure",
                new SpaceVector(33, 0, 0), new SpaceVector(1, 1, 1), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RocketPart(0, -1, "astraengine:structure",
                SpaceVector.ZERO, new SpaceVector(1, 0, 1), 0, Map.of()));
    }

    @Test
    void frustumSlicesCoverActualSurfaceAtEveryYawWithoutOneHugeAssemblyBox() {
        RocketPart original = RocketBuiltins.starter(catalog).requirePart(0);
        RocketVisual visual = catalog.requireDefinition(original.definitionId()).visual();
        for (int yaw = 0; yaw < 4; yaw++) {
            RocketPart part = new RocketPart(0, -1, original.definitionId(), new SpaceVector(2, 3, -1),
                    new SpaceVector(2, 3, 4), yaw, original.moduleValues());
            List<RocketBounds> slices = RocketGeometry.collisionBoxes(catalog, part);
            assertEquals(3, slices.size());
            assertTrue(slices.get(2).size().x() < slices.get(0).size().x());
            RocketBounds bounds = RocketGeometry.bounds(part);
            assertEquals(yaw % 2 == 0 ? 2 : 4, bounds.size().x());
            for (int sample = 0; sample <= 60; sample++) {
                double t = sample / 60.0;
                double ratio = visual.bottomRadiusRatio()
                        + (visual.topRadiusRatio() - visual.bottomRadiusRatio()) * t;
                SpaceVector surface = new SpaceVector(part.position().x() + bounds.size().x() * ratio * 0.5,
                        bounds.min().y() + part.size().y() * t, part.position().z());
                assertTrue(slices.stream().anyMatch(box -> contains(box, surface)), "Missing frustum surface sample");
            }
        }
    }

    @Test
    void engineMixtureUsesPropellantFlowWeightedImpulseAndRefusesSingularActiveInputs() {
        RocketBlueprint blueprint = RocketBuiltins.starter(catalog);
        RocketPart first = blueprint.requirePart(2)
                .withValue(RocketStandardModules.THRUST_ENGINE, "thrust_newtons", RocketValue.number(100_000))
                .withValue(RocketStandardModules.THRUST_ENGINE, "specific_impulse_seconds", RocketValue.number(200));
        RocketPart second = new RocketPart(3, 1, first.definitionId(), new SpaceVector(3, 0, 0), first.size(), 0,
                first.moduleValues())
                .withValue(RocketStandardModules.THRUST_ENGINE, "thrust_newtons", RocketValue.number(300_000))
                .withValue(RocketStandardModules.THRUST_ENGINE, "specific_impulse_seconds", RocketValue.number(400));
        blueprint = new RocketBlueprint("Dual engines", List.of(blueprint.requirePart(0),
                blueprint.requirePart(1), first, second));
        RocketStats stats = RocketStats.calculate(catalog, blueprint);
        assertEquals(320, stats.effectiveSpecificImpulseSeconds(), 1.0e-10);
        assertNotEquals(350, stats.effectiveSpecificImpulseSeconds());
        assertEquals(400_000, stats.thrustNewtons());
        assertEquals(2050, stats.dryMassKg());
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(first.withValue(
                RocketStandardModules.THRUST_ENGINE, "specific_impulse_seconds", RocketValue.number(0))));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(first.withValue(
                RocketStandardModules.THRUST_ENGINE, "specific_impulse_seconds",
                RocketValue.number(Double.MIN_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> RocketValue.number(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RocketValue.number(Double.POSITIVE_INFINITY));
    }

    @Test
    void standardCapabilitiesCannotBeSpoofedWithDifferentUnitsOrKinds() {
        RocketModuleDefinition malformed = new RocketModuleDefinition(RocketStandardModules.MASS, "Mass", List.of(
                RocketParameterDefinition.number("dry_mass_kg", "Dry mass", "t", 0.01, 1.0e7, 1),
                RocketParameterDefinition.number("fuel_kg", "Fuel", "kg", 0, 1.0e7, 0)));
        RocketPartDefinition reference = catalog.requireDefinition("astraengine:structure");
        RocketPartDefinition bad = new RocketPartDefinition("consumer:bad", "Bad", "consumer:parts",
                reference.visual(), reference.defaultSize(), List.of(malformed));
        assertThrows(IllegalArgumentException.class, () -> new RocketCatalog(List.of(bad)));
        assertThrows(IllegalArgumentException.class, () -> new RocketCatalog(List.of(reference, reference)));
        assertThrows(IllegalArgumentException.class, () -> new RocketValue(RocketValue.Kind.BOOLEAN, 1, true, ""));
        assertThrows(IllegalArgumentException.class, () -> RocketParameterDefinition.choice("mode", "Mode",
                List.of("same", "same"), "same"));
    }

    @Test
    void subnormalActiveEnginesAreRejectedAtRegistrationAndInstanceBoundaries() {
        RocketPart engine = RocketBuiltins.starter(catalog).requirePart(2);
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(engine.withValue(
                RocketStandardModules.THRUST_ENGINE, "thrust_newtons", RocketValue.number(Double.MIN_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> RocketStandardModules.thrustEngine(Double.MIN_VALUE, 320));
        assertThrows(IllegalArgumentException.class, () -> RocketStandardModules.thrustEngine(1, Double.MIN_VALUE));

        RocketModuleDefinition directSchema = new RocketModuleDefinition(RocketStandardModules.THRUST_ENGINE,
                "Invalid defaults", List.of(
                RocketParameterDefinition.number("thrust_newtons", "Thrust", "N", 0, 1.0e9, 1),
                RocketParameterDefinition.number("specific_impulse_seconds", "Specific impulse", "s",
                        0, 1.0e6, Double.MIN_VALUE)));
        RocketPartDefinition original = catalog.requireDefinition(engine.definitionId());
        RocketPartDefinition invalid = new RocketPartDefinition("consumer:singular_engine", "Singular engine",
                "consumer:propulsion", original.visual(), original.defaultSize(), List.of(directSchema));
        assertThrows(IllegalArgumentException.class, () -> new RocketCatalog(List.of(invalid)));

        RocketPart inactive = engine.withValue(RocketStandardModules.THRUST_ENGINE, "thrust_newtons",
                RocketValue.number(0)).withValue(RocketStandardModules.THRUST_ENGINE, "specific_impulse_seconds",
                RocketValue.number(Double.MIN_VALUE));
        RocketStats stats = RocketStats.calculate(catalog, RocketBuiltins.starter(catalog).withPart(inactive));
        assertEquals(0, stats.thrustNewtons());
        assertEquals(0, stats.effectiveSpecificImpulseSeconds());
        assertEquals(0, stats.burnTimeSeconds());
    }

    @Test
    void maximumPartAssembliesProduceFiniteEstimatesAtEveryStandardNumericBoundary() {
        RocketPart original = RocketBuiltins.starter(catalog).requirePart(2);
        for (double thrust : new double[]{1.0e-6, 1.0e9}) {
            for (double impulse : new double[]{1.0e-3, 1.0e6}) {
                for (double dryMass : new double[]{0.01, 1.0e7}) {
                    for (double fuel : new double[]{0, 1.0e7}) {
                        RocketPart configured = original.withValue(RocketStandardModules.THRUST_ENGINE,
                                        "thrust_newtons", RocketValue.number(thrust))
                                .withValue(RocketStandardModules.THRUST_ENGINE,
                                        "specific_impulse_seconds", RocketValue.number(impulse))
                                .withValue(RocketStandardModules.MASS, "dry_mass_kg", RocketValue.number(dryMass))
                                .withValue(RocketStandardModules.MASS, "fuel_kg", RocketValue.number(fuel))
                                .withValue(RocketStandardModules.POWER, "power_kw", RocketValue.number(-1.0e9))
                                .withValue(RocketStandardModules.POWER, "energy_kwh", RocketValue.number(1.0e12))
                                .withValue(RocketStandardModules.THERMAL, "heat_kw", RocketValue.number(1.0e9))
                                .withValue(RocketStandardModules.THERMAL, "cooling_kw", RocketValue.number(1.0e9));
                        List<RocketPart> parts = new ArrayList<>();
                        for (int id = 0; id < RocketModelLimits.MAX_PARTS; id++) {
                            parts.add(new RocketPart(id, id == 0 ? -1 : 0, configured.definitionId(),
                                    SpaceVector.ZERO, configured.size(), 0, configured.moduleValues()));
                        }
                        RocketStats stats = RocketStats.calculate(catalog, new RocketBlueprint("Limits", parts));
                        assertTrue(Double.isFinite(stats.deltaVMetersPerSecond()));
                        assertTrue(Double.isFinite(stats.thrustToWeightRatio()));
                        assertTrue(Double.isFinite(stats.burnTimeSeconds()));
                        assertEquals(impulse, stats.effectiveSpecificImpulseSeconds(), impulse * 1.0e-12);
                        assertEquals(32 * fuel, stats.fuelMassKg());
                        assertEquals(32.0e12, stats.energyKWh());
                        assertEquals(-32.0e9, stats.powerKilowatts());
                    }
                }
            }
        }
    }

    private static RocketPart replaceValues(RocketPart part, Map<String, Map<String, RocketValue>> values) {
        return new RocketPart(part.id(), part.parentId(), part.definitionId(), part.position(), part.size(),
                part.yawQuarterTurns(), values);
    }

    private static RocketPart withParent(RocketPart part, int parentId) {
        return new RocketPart(part.id(), parentId, part.definitionId(), part.position(), part.size(),
                part.yawQuarterTurns(), part.moduleValues());
    }

    private static boolean contains(RocketBounds bounds, SpaceVector point) {
        double epsilon = 1.0e-12;
        return point.x() >= bounds.min().x() - epsilon && point.x() <= bounds.max().x() + epsilon
                && point.y() >= bounds.min().y() - epsilon && point.y() <= bounds.max().y() + epsilon
                && point.z() >= bounds.min().z() - epsilon && point.z() <= bounds.max().z() + epsilon;
    }
}
