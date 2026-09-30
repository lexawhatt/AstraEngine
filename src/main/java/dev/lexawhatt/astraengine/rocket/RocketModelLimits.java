package dev.lexawhatt.astraengine.rocket;

/** Bounded serialization and authoring limits shared by immutable rocket descriptors. */
public final class RocketModelLimits {
    public static final int MAX_PARTS = 32;
    public static final int MAX_DEFINITIONS = 256;
    public static final int MAX_MODULES = 8;
    public static final int MAX_PARAMETERS = 32;
    public static final int MAX_CHOICES = 16;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_PART_ID = 1_000_000;
    public static final double MAX_WIDTH_METERS = 16;
    public static final double MAX_HEIGHT_METERS = 24;
    public static final double MAX_CENTER_METERS = 32;
    public static final double MIN_SIZE_METERS = 0.05;
    public static final double MAX_PARAMETER_MAGNITUDE = 1.0e12;

    private RocketModelLimits() {
    }

    static String requireId(String value, String subject) {
        if (value == null || value.length() > MAX_ID_LENGTH
                || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(subject + " must be a namespaced ID of at most 64 characters");
        }
        return value;
    }

    static String requireText(String value, String subject, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(subject + " must be nonblank text of at most " + maximumLength);
        }
        return value;
    }
}
