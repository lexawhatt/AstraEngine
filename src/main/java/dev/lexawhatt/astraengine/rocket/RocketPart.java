package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One immutable part: center and full size are local meters, +Y is up, and positive yaw maps +X toward -Z. */
public record RocketPart(int id, int parentId, String definitionId, SpaceVector position, SpaceVector size,
                         int yawQuarterTurns, Map<String, Map<String, RocketValue>> moduleValues) {
    public RocketPart {
        if (id < 0 || id > RocketModelLimits.MAX_PART_ID || parentId < -1
                || parentId > RocketModelLimits.MAX_PART_ID || parentId == id) {
            throw new IllegalArgumentException("Rocket part IDs must be bounded, with -1 reserved for the root parent");
        }
        RocketModelLimits.requireId(definitionId, "Rocket part definition ID");
        if (position == null || Math.abs(position.x()) > RocketModelLimits.MAX_CENTER_METERS
                || Math.abs(position.y()) > RocketModelLimits.MAX_CENTER_METERS
                || Math.abs(position.z()) > RocketModelLimits.MAX_CENTER_METERS
                || yawQuarterTurns < 0 || yawQuarterTurns > 3) {
            throw new IllegalArgumentException("Rocket part requires a bounded local center and quarter-turn yaw 0..3");
        }
        validateSize(size);
        if (moduleValues == null || moduleValues.size() > RocketModelLimits.MAX_MODULES) {
            throw new IllegalArgumentException("Rocket part requires a bounded module value map");
        }
        Map<String, Map<String, RocketValue>> copiedModules = new LinkedHashMap<>();
        int parameterCount = 0;
        for (Map.Entry<String, Map<String, RocketValue>> entry : moduleValues.entrySet()) {
            RocketModelLimits.requireId(entry.getKey(), "Rocket module value ID");
            Map<String, RocketValue> values = entry.getValue();
            if (values == null || values.size() > RocketModelLimits.MAX_PARAMETERS) {
                throw new IllegalArgumentException("Rocket module values must be bounded and non-null");
            }
            Map<String, RocketValue> copiedValues = new LinkedHashMap<>();
            for (Map.Entry<String, RocketValue> value : values.entrySet()) {
                if (value.getKey() == null || value.getKey().length() > 32
                        || !value.getKey().matches("[a-z][a-z0-9_]*") || value.getValue() == null) {
                    throw new IllegalArgumentException("Rocket module parameter keys and values must be valid");
                }
                copiedValues.put(value.getKey(), value.getValue());
            }
            parameterCount += values.size();
            copiedModules.put(entry.getKey(), Collections.unmodifiableMap(copiedValues));
        }
        if (parameterCount > RocketModelLimits.MAX_PARAMETERS) {
            throw new IllegalArgumentException("Rocket parts support at most 32 total parameter values");
        }
        moduleValues = Collections.unmodifiableMap(copiedModules);
    }

    /** Returns a new part with an existing field replaced; the catalog still validates its kind and range. */
    public RocketPart withValue(String moduleId, String parameterKey, RocketValue value) {
        Map<String, RocketValue> oldValues = moduleValues.get(moduleId);
        if (oldValues == null || !oldValues.containsKey(parameterKey) || value == null) {
            throw new IllegalArgumentException("Cannot replace an unknown or null rocket parameter");
        }
        Map<String, Map<String, RocketValue>> next = new LinkedHashMap<>(moduleValues);
        Map<String, RocketValue> values = new LinkedHashMap<>(oldValues);
        values.put(parameterKey, value);
        next.put(moduleId, values);
        return new RocketPart(id, parentId, definitionId, position, size, yawQuarterTurns, next);
    }

    static void validateSize(SpaceVector size) {
        if (size == null || size.x() < RocketModelLimits.MIN_SIZE_METERS
                || size.y() < RocketModelLimits.MIN_SIZE_METERS || size.z() < RocketModelLimits.MIN_SIZE_METERS
                || size.x() > RocketModelLimits.MAX_WIDTH_METERS || size.z() > RocketModelLimits.MAX_WIDTH_METERS
                || size.y() > RocketModelLimits.MAX_HEIGHT_METERS) {
            throw new IllegalArgumentException("Rocket full size must fit positive 16 x 24 x 16 meter bounds");
        }
    }
}
