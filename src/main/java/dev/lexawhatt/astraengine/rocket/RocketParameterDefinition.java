package dev.lexawhatt.astraengine.rocket;

import java.util.HashSet;
import java.util.List;

/** Immutable editor schema. Numeric bounds are inclusive; booleans and choices use zero numeric bounds. */
public record RocketParameterDefinition(String key, String displayName, String unit, RocketValue.Kind kind,
                                        double minimum, double maximum, List<String> choices,
                                        RocketValue defaultValue) {
    public RocketParameterDefinition {
        if (key == null || key.length() > 32 || !key.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Rocket parameter key must be a lowercase token of at most 32 characters");
        }
        RocketModelLimits.requireText(displayName, "Parameter display name", 64);
        if (unit == null || unit.length() > 24 || unit.chars().anyMatch(Character::isISOControl)
                || kind == null || choices == null || choices.stream().anyMatch(value -> value == null)
                || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum
                || Math.abs(minimum) > RocketModelLimits.MAX_PARAMETER_MAGNITUDE
                || Math.abs(maximum) > RocketModelLimits.MAX_PARAMETER_MAGNITUDE) {
            throw new IllegalArgumentException("Invalid rocket parameter units, kind, choices, or numeric bounds");
        }
        choices = List.copyOf(choices);
        if (choices.size() > RocketModelLimits.MAX_CHOICES || new HashSet<>(choices).size() != choices.size()) {
            throw new IllegalArgumentException("Rocket parameter choices must be distinct and bounded");
        }
        for (String choice : choices) {
            RocketModelLimits.requireText(choice, "Parameter choice", 64);
        }
        if (kind == RocketValue.Kind.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException("A choice parameter requires at least one choice");
        }
        if (kind != RocketValue.Kind.CHOICE && !choices.isEmpty()) {
            throw new IllegalArgumentException("Only choice parameters can declare choices");
        }
        if (kind != RocketValue.Kind.NUMBER && (minimum != 0 || maximum != 0)) {
            throw new IllegalArgumentException("Only numeric parameters can declare numeric bounds");
        }
        validateValue(kind, minimum, maximum, choices, defaultValue, key);
    }

    /** Creates a numeric schema with explicit units and inclusive finite bounds. */
    public static RocketParameterDefinition number(String key, String name, String unit,
                                                    double minimum, double maximum, double defaultValue) {
        return new RocketParameterDefinition(key, name, unit, RocketValue.Kind.NUMBER,
                minimum, maximum, List.of(), RocketValue.number(defaultValue));
    }

    /** Creates a boolean schema, rendered as a toggle by a generic editor. */
    public static RocketParameterDefinition flag(String key, String name, boolean defaultValue) {
        return new RocketParameterDefinition(key, name, "", RocketValue.Kind.BOOLEAN,
                0, 0, List.of(), RocketValue.flag(defaultValue));
    }

    /** Creates a choice schema; tokens retain their exact spelling through persistence. */
    public static RocketParameterDefinition choice(String key, String name, List<String> choices, String defaultValue) {
        return new RocketParameterDefinition(key, name, "", RocketValue.Kind.CHOICE,
                0, 0, choices, RocketValue.choice(defaultValue));
    }

    /** Rejects null, a different primitive kind, or a value outside this schema. */
    public void validate(RocketValue value) {
        validateValue(kind, minimum, maximum, choices, value, key);
    }

    private static void validateValue(RocketValue.Kind kind, double minimum, double maximum, List<String> choices,
                                      RocketValue value, String key) {
        if (value == null || value.kind() != kind) {
            throw new IllegalArgumentException("Rocket parameter " + key + " has the wrong value kind");
        }
        if (kind == RocketValue.Kind.NUMBER && (value.number() < minimum || value.number() > maximum)) {
            throw new IllegalArgumentException("Rocket parameter " + key + " is outside its numeric bounds");
        }
        if (kind == RocketValue.Kind.CHOICE && !choices.contains(value.choice())) {
            throw new IllegalArgumentException("Rocket parameter " + key + " is not an accepted choice");
        }
    }
}
