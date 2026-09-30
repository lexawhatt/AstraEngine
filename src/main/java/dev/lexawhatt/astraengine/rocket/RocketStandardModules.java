package dev.lexawhatt.astraengine.rocket;

import java.util.List;
import java.util.Map;

/** Optional standard data capabilities for basic estimates; arbitrary namespaced consumer modules remain valid. */
public final class RocketStandardModules {
    public static final String MASS = "astraengine:mass";
    public static final String THRUST_ENGINE = "astraengine:thrust_engine";
    public static final String POWER = "astraengine:power";
    public static final String THERMAL = "astraengine:thermal";
    public static final String CONTROL = "astraengine:control";

    private RocketStandardModules() {
    }

    /** Standard dry mass and shared usable propellant in kilograms. */
    public static RocketModuleDefinition mass(double dryMassKg, double fuelKg) {
        return new RocketModuleDefinition(MASS, "Mass and propellant", List.of(
                RocketParameterDefinition.number("dry_mass_kg", "Dry mass", "kg", 0.01, 1.0e7, dryMassKg),
                RocketParameterDefinition.number("fuel_kg", "Propellant", "kg", 0, 1.0e7, fuelKg)));
    }

    /**
     * Ideal thrust/Isp capability, without an implemented propulsion simulation. Active engines require at least
     * 1e-6 N thrust and 0.001 s impulse so every valid assembly has finite estimates; zero thrust is inactive.
     */
    public static RocketModuleDefinition thrustEngine(double thrustNewtons, double specificImpulseSeconds) {
        RocketModuleDefinition module = new RocketModuleDefinition(THRUST_ENGINE, "Ideal thrust engine", List.of(
                RocketParameterDefinition.number("thrust_newtons", "Thrust", "N", 0, 1.0e9, thrustNewtons),
                RocketParameterDefinition.number("specific_impulse_seconds", "Specific impulse", "s",
                        0, 1.0e6, specificImpulseSeconds)));
        if (thrustNewtons > 0 && (thrustNewtons < 1.0e-6 || specificImpulseSeconds < 1.0e-3)) {
            throw new IllegalArgumentException("Active ideal engines require thrust >= 1e-6 N and impulse >= 0.001 s");
        }
        return module;
    }

    /** Signed power in kilowatts: positive production, negative demand; stored energy in kilowatt-hours. */
    public static RocketModuleDefinition power(double powerKilowatts, double energyKWh) {
        return new RocketModuleDefinition(POWER, "Electrical", List.of(
                RocketParameterDefinition.number("power_kw", "Net power", "kW", -1.0e9, 1.0e9, powerKilowatts),
                RocketParameterDefinition.number("energy_kwh", "Stored energy", "kWh", 0, 1.0e12, energyKWh)));
    }

    /** Heat generation and cooling capacity in kilowatts. */
    public static RocketModuleDefinition thermal(double heatKilowatts, double coolingKilowatts) {
        return new RocketModuleDefinition(THERMAL, "Thermal", List.of(
                RocketParameterDefinition.number("heat_kw", "Heat generation", "kW", 0, 1.0e9, heatKilowatts),
                RocketParameterDefinition.number("cooling_kw", "Cooling", "kW", 0, 1.0e9, coolingKilowatts)));
    }

    /** Standard command and attitude capabilities for readiness hints, independent of a part identity. */
    public static RocketModuleDefinition control(boolean command, boolean attitude) {
        return new RocketModuleDefinition(CONTROL, "Control", List.of(
                RocketParameterDefinition.flag("command", "Command authority", command),
                RocketParameterDefinition.flag("attitude", "Attitude control", attitude)));
    }

    /** Whether the basic estimator understands this module; custom modules remain valid when false. */
    public static boolean modeled(String moduleId) {
        return MASS.equals(moduleId) || THRUST_ENGINE.equals(moduleId) || POWER.equals(moduleId)
                || THERMAL.equals(moduleId) || CONTROL.equals(moduleId);
    }

    static void validate(RocketModuleDefinition module) {
        RocketModuleDefinition expected = switch (module.id()) {
            case MASS -> mass(1, 0);
            case THRUST_ENGINE -> thrustEngine(0, 0);
            case POWER -> power(0, 0);
            case THERMAL -> thermal(0, 0);
            case CONTROL -> control(false, false);
            default -> null;
        };
        if (expected == null) {
            return;
        }
        if (module.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("Standard rocket module has an incompatible schema: " + module.id());
        }
        for (RocketParameterDefinition required : expected.parameters()) {
            RocketParameterDefinition actual = module.parameters().stream()
                    .filter(parameter -> parameter.key().equals(required.key())).findFirst().orElse(null);
            if (actual == null || actual.kind() != required.kind() || !actual.unit().equals(required.unit())
                    || actual.minimum() != required.minimum() || actual.maximum() != required.maximum()
                    || !actual.choices().equals(required.choices())) {
                throw new IllegalArgumentException("Standard rocket module has an incompatible field: "
                        + module.id() + "/" + required.key());
            }
        }
        if (module.id().equals(THRUST_ENGINE)) {
            Map<String, RocketValue> defaults = module.parameters().stream().collect(
                    java.util.stream.Collectors.toMap(RocketParameterDefinition::key,
                            RocketParameterDefinition::defaultValue));
            validateValues(module.id(), defaults);
        }
    }

    static void validateValues(String moduleId, Map<String, RocketValue> values) {
        if (THRUST_ENGINE.equals(moduleId)) {
            double thrust = values.get("thrust_newtons").number();
            double impulse = values.get("specific_impulse_seconds").number();
            if (thrust > 0 && (thrust < 1.0e-6 || impulse < 1.0e-3)) {
                throw new IllegalArgumentException(
                        "Active ideal engines require thrust >= 1e-6 N and impulse >= 0.001 s");
            }
        }
    }
}
