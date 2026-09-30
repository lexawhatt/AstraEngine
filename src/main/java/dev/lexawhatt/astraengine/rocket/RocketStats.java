package dev.lexawhatt.astraengine.rocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estimates from optional standard modules only. All ideal engines burn simultaneously using one shared fuel pool;
 * no staging, atmosphere, fuel compatibility, actual simulation, or arbitrary consumer propulsion is inferred.
 */
public record RocketStats(double dryMassKg, double fuelMassKg, double wetMassKg, double thrustNewtons,
                          double effectiveSpecificImpulseSeconds, double deltaVMetersPerSecond,
                          double thrustToWeightRatio, double burnTimeSeconds, double powerKilowatts,
                          double heatKilowatts, double coolingKilowatts, double energyKWh,
                          List<RocketDiagnostic> diagnostics) {
    public static final double STANDARD_GRAVITY = 9.80665;

    public RocketStats {
        double[] nonnegative = {dryMassKg, fuelMassKg, wetMassKg, thrustNewtons,
                effectiveSpecificImpulseSeconds, deltaVMetersPerSecond, thrustToWeightRatio, burnTimeSeconds,
                heatKilowatts, coolingKilowatts, energyKWh};
        for (double value : nonnegative) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Rocket estimates must be finite and nonnegative");
            }
        }
        if (!Double.isFinite(powerKilowatts) || diagnostics == null
                || diagnostics.stream().anyMatch(diagnostic -> diagnostic == null)) {
            throw new IllegalArgumentException("Rocket estimates require finite power and non-null diagnostics");
        }
        diagnostics = List.copyOf(diagnostics);
    }

    /** Reads supported standard capabilities and reports unknown modules without interpreting them as engines. */
    public static RocketStats calculate(RocketCatalog catalog, RocketBlueprint blueprint) {
        if (catalog == null) {
            throw new IllegalArgumentException("Rocket estimates require a catalog");
        }
        catalog.validate(blueprint);
        double dry = 0;
        double fuel = 0;
        double thrust = 0;
        double fuelFlow = 0;
        double power = 0;
        double heat = 0;
        double cooling = 0;
        double energy = 0;
        boolean command = false;
        boolean attitude = false;
        boolean unknownMass = false;
        boolean unmodeled = false;
        for (RocketPart part : blueprint.parts()) {
            Map<String, Map<String, RocketValue>> modules = part.moduleValues();
            unknownMass |= !modules.containsKey(RocketStandardModules.MASS);
            for (Map.Entry<String, Map<String, RocketValue>> module : modules.entrySet()) {
                Map<String, RocketValue> values = module.getValue();
                switch (module.getKey()) {
                    case RocketStandardModules.MASS -> {
                        dry += values.get("dry_mass_kg").number();
                        fuel += values.get("fuel_kg").number();
                    }
                    case RocketStandardModules.THRUST_ENGINE -> {
                        double engineThrust = values.get("thrust_newtons").number();
                        double impulse = values.get("specific_impulse_seconds").number();
                        thrust += engineThrust;
                        if (engineThrust > 0) {
                            fuelFlow += engineThrust / (impulse * STANDARD_GRAVITY);
                        }
                    }
                    case RocketStandardModules.POWER -> {
                        power += values.get("power_kw").number();
                        energy += values.get("energy_kwh").number();
                    }
                    case RocketStandardModules.THERMAL -> {
                        heat += values.get("heat_kw").number();
                        cooling += values.get("cooling_kw").number();
                    }
                    case RocketStandardModules.CONTROL -> {
                        command |= values.get("command").flag();
                        attitude |= values.get("attitude").flag();
                    }
                    default -> unmodeled = true;
                }
            }
        }
        double wet = dry + fuel;
        double impulse = fuelFlow > 0 ? thrust / (fuelFlow * STANDARD_GRAVITY) : 0;
        double deltaV = dry > 0 && !unknownMass ? STANDARD_GRAVITY * impulse * Math.log1p(fuel / dry) : 0;
        double twr = wet > 0 && !unknownMass ? thrust / (wet * STANDARD_GRAVITY) : 0;
        double burnSeconds = fuelFlow > 0 ? fuel / fuelFlow : 0;
        List<RocketDiagnostic> diagnostics = new ArrayList<>();
        add(diagnostics, blueprint.parts().isEmpty(), RocketDiagnostic.EMPTY);
        add(diagnostics, !command, RocketDiagnostic.MISSING_COMMAND_POD);
        add(diagnostics, thrust <= 0, RocketDiagnostic.MISSING_ENGINE);
        add(diagnostics, fuel <= 0, RocketDiagnostic.NO_FUEL);
        add(diagnostics, unknownMass, RocketDiagnostic.UNKNOWN_MASS);
        add(diagnostics, thrust > 0 && twr < 1, RocketDiagnostic.LOW_TWR);
        add(diagnostics, power < 0, RocketDiagnostic.POWER_DEFICIT);
        add(diagnostics, heat > cooling, RocketDiagnostic.NET_HEATING);
        add(diagnostics, !attitude, RocketDiagnostic.NO_ATTITUDE_CONTROL);
        add(diagnostics, unmodeled, RocketDiagnostic.UNMODELED_MODULES);
        return new RocketStats(dry, fuel, wet, thrust, impulse, deltaV, twr, burnSeconds,
                power, heat, cooling, energy, diagnostics);
    }

    /** True when the standard ideal-engine estimate has its inputs; custom consumer readiness is separate. */
    public boolean flightReady() {
        return diagnostics.stream().noneMatch(RocketDiagnostic::blocksEstimate);
    }

    private static void add(List<RocketDiagnostic> diagnostics, boolean condition, RocketDiagnostic diagnostic) {
        if (condition) {
            diagnostics.add(diagnostic);
        }
    }
}
