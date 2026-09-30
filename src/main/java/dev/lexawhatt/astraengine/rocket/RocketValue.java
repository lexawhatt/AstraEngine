package dev.lexawhatt.astraengine.rocket;

/** A serializable typed parameter value; inactive fields must use their canonical empty values. */
public record RocketValue(Kind kind, double number, boolean flag, String choice) {
    /** These are storage primitives, not a closed list of consumer behavior or propulsion types. */
    public enum Kind {
        NUMBER,
        BOOLEAN,
        CHOICE
    }

    public RocketValue {
        if (kind == null || choice == null || !Double.isFinite(number)
                || Math.abs(number) > RocketModelLimits.MAX_PARAMETER_MAGNITUDE) {
            throw new IllegalArgumentException("Rocket value requires a kind, bounded finite number, and choice text");
        }
        switch (kind) {
            case NUMBER -> {
                if (flag || !choice.isEmpty()) {
                    throw new IllegalArgumentException("A numeric rocket value cannot contain flag or choice data");
                }
            }
            case BOOLEAN -> {
                if (number != 0 || !choice.isEmpty()) {
                    throw new IllegalArgumentException("A boolean rocket value cannot contain number or choice data");
                }
            }
            case CHOICE -> {
                if (number != 0 || flag) {
                    throw new IllegalArgumentException("A choice rocket value cannot contain number or flag data");
                }
                RocketModelLimits.requireText(choice, "Rocket choice", 64);
            }
        }
    }

    /** Creates a finite number; its schema supplies the units and narrower accepted range. */
    public static RocketValue number(double value) {
        return new RocketValue(Kind.NUMBER, value, false, "");
    }

    /** Creates a boolean value. */
    public static RocketValue flag(boolean value) {
        return new RocketValue(Kind.BOOLEAN, 0, value, "");
    }

    /** Creates a choice token; its schema must contain the same token. */
    public static RocketValue choice(String value) {
        return new RocketValue(Kind.CHOICE, 0, false, value);
    }
}
