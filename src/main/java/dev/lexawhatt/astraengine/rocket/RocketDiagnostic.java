package dev.lexawhatt.astraengine.rocket;

/** Basic-estimator hints; these never decide whether a consumer's custom propulsion can operate. */
public enum RocketDiagnostic {
    EMPTY(true),
    MISSING_COMMAND_POD(true),
    MISSING_ENGINE(true),
    NO_FUEL(true),
    UNKNOWN_MASS(true),
    LOW_TWR(false),
    POWER_DEFICIT(false),
    NET_HEATING(false),
    NO_ATTITUDE_CONTROL(false),
    UNMODELED_MODULES(false);

    private final boolean blocksEstimate;

    RocketDiagnostic(boolean blocksEstimate) {
        this.blocksEstimate = blocksEstimate;
    }

    /** Whether an ideal shared-propellant readiness estimate is incomplete. This is not deployment authority. */
    public boolean blocksEstimate() {
        return blocksEstimate;
    }
}
