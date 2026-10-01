package dev.lexawhatt.astraengine.client.sky;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CloudNoiseFieldTest {
    @Test
    void fieldRepeatsContinuouslyAndCopiesCannotMutateIt() {
        var field = new CloudNoiseField();
        var random = new Random(58);
        double minimum = 1, maximum = 0;
        for (int i = 0; i < 4000; i++) {
            double x = random.nextDouble() * 256 - 128;
            double y = random.nextDouble() * 256 - 128;
            double z = random.nextDouble() * 256 - 128;
            double value = field.sample(x, y, z);
            assertTrue(value >= 0 && value <= 1);
            minimum = Math.min(value, minimum); maximum = Math.max(value, maximum);
            assertEquals(value, field.sample(x + 64, y, z), 2e-12);
            assertEquals(value, field.sample(x, y - 64, z), 2e-12);
            assertEquals(value, field.sample(x, y, z + 128), 2e-12);
        }
        assertTrue(minimum < .15 && maximum > .85);
        assertEquals(field.sample(-1e-8, 11.3, 63.8), field.sample(1e-8, 11.3, 63.8), 1e-12);
        double before = field.sample(12.3, 15.2, 53.6);
        byte[] copy = field.copyAtlas();
        assertEquals(557568, copy.length);
        java.util.Arrays.fill(copy, (byte) 0);
        assertEquals(before, field.sample(12.3, 15.2, 53.6));
        assertThrows(IllegalArgumentException.class, () -> field.sample(Double.NaN, 0, 0));
    }

    @Test
    void paddedTileBordersAndAdjacentChannelsWrapWithoutSliceBleeding() {
        byte[] data = new CloudNoiseField().copyAtlas();
        for (int z = 0; z < 64; z++) {
            for (int at = 0; at < 64; at++) {
                for (int channel = 0; channel < 2; channel++) {
                    assertEquals(data[index(0, at + 1, z, channel)], data[index(64, at + 1, z, channel)]);
                    assertEquals(data[index(65, at + 1, z, channel)], data[index(1, at + 1, z, channel)]);
                    assertEquals(data[index(at + 1, 0, z, channel)], data[index(at + 1, 64, z, channel)]);
                    assertEquals(data[index(at + 1, 65, z, channel)], data[index(at + 1, 1, z, channel)]);
                }
                for (int x = 0; x < 64; x++) {
                    assertEquals(data[index(x + 1, at + 1, z, 1)], data[index(x + 1, at + 1, (z + 1) % 64, 0)]);
                }
            }
        }
    }

    private static int index(int x, int y, int slice, int channel) {
        return (((slice / 8) * 66 + y) * 528 + (slice % 8) * 66 + x) * 2 + channel;
    }
}
