package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.PrimitiveCodec;
import java.math.BigDecimal;

/** Saved terrain identities must reject lossy numeric narrowing before validation. */
final class TerrainCodecs {
    private TerrainCodecs() {}

    // The usual integer codecs narrow Number values. Saved identities must reject fractional or overflowing
    // values before that narrowing, including a seed whose exact long value cannot be represented by a double.
    static final Codec<Long> EXACT_LONG = new PrimitiveCodec<>() {
        @Override
        public <T> DataResult<Long> read(DynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).flatMap(number -> {
                try {
                    return DataResult.success(new BigDecimal(number.toString()).longValueExact());
                } catch (ArithmeticException | NumberFormatException exception) {
                    return DataResult.error(() -> "Terrain identity requires an exact signed integer");
                }
            });
        }

        @Override
        public <T> T write(DynamicOps<T> ops, Long value) { return ops.createLong(value); }
    };
    static final Codec<Integer> EXACT_INT = EXACT_LONG.comapFlatMap(value ->
            value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    ? DataResult.success(value.intValue())
                    : DataResult.error(() -> "Terrain identity integer exceeds its supported range"), Integer::longValue);
}
