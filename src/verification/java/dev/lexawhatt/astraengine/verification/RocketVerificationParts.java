package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.rocket.AstraRocketEditor;
import dev.lexawhatt.astraengine.api.rocket.RegisterRocketPartsEvent;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketGeometryKind;
import dev.lexawhatt.astraengine.rocket.RocketMaterialStyle;
import dev.lexawhatt.astraengine.rocket.RocketModuleDefinition;
import dev.lexawhatt.astraengine.rocket.RocketParameterDefinition;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import dev.lexawhatt.astraengine.rocket.RocketVisual;
import java.util.List;
import java.util.stream.IntStream;

/** Verification-only consumer proves that the editor accepts an independently registered module schema. */
public final class RocketVerificationParts {
    public static final String PART_ID = "verification:wormhole_core";
    public static final String BOUNDARY_ID = "verification:" + "b".repeat(51);
    public static final String MODULE_ID = "verification:wormhole";
    public static final String RANGE = "range_ly";
    public static final String STABILIZED = "stabilized";
    public static final String MODE = "transit_mode";
    private RocketVerificationParts() {}

    public static void register(RegisterRocketPartsEvent event) {
        event.register(new RocketPartDefinition(PART_ID, "Verification Wormhole Core", "verification:experimental",
                new RocketVisual(RocketGeometryKind.BOX, 1, 1, new SpaceVector(0.32, 0.78, 1.0),
                        RocketMaterialStyle.PANEL), new SpaceVector(1, 1, 1), List.of(new RocketModuleDefinition(
                        MODULE_ID, "Verification Wormhole", List.of(
                        RocketParameterDefinition.number(RANGE, "Range", "ly", 0, 1000, 12),
                        RocketParameterDefinition.flag(STABILIZED, "Stabilized", false),
                        RocketParameterDefinition.choice(MODE, "Transit mode", List.of("warp", "wormhole"), "warp"))))));
        event.register(new RocketPartDefinition(BOUNDARY_ID, "Verification Wire Boundary", "verification:experimental",
                new RocketVisual(RocketGeometryKind.BOX, 1, 1, new SpaceVector(0.5, 0.5, 0.5),
                        RocketMaterialStyle.PLAIN), new SpaceVector(0.25, 0.25, 0.25), List.of(
                        new RocketModuleDefinition("verification:boundary", "Wire boundary", IntStream.range(0, 32)
                                .mapToObj(index -> RocketParameterDefinition.number("value_" + index,
                                        "Value " + index, "", -1.0e12, 1.0e12, index)).toList()))));
    }

    public static RocketBlueprint blueprint() {
        return new RocketBlueprint("External Wormhole", List.of(new RocketPart(0, -1, PART_ID, SpaceVector.ZERO,
                new SpaceVector(1, 1, 1), 0, AstraRocketEditor.catalog().defaultValues(PART_ID))));
    }
}
