package dev.lexawhatt.astraengine.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering is per relocation epoch; rejected packets must not consume sequence or rate-limit slots. */
class FlightInputWindowTest {
    @Test
    void staleArrivalInputCannotOverwriteNewOrientationOrConsumeItsTick() {
        FlightInputWindow window = new FlightInputWindow();
        window.relocate(8);
        assertTrue(window.accept(100, 8, 20));
        window.relocate(9);
        assertEquals(9, window.navigationEpoch());
        assertTrue(window.expired(21));
        assertFalse(window.accept(101, 8, 21));
        assertTrue(window.accept(0, 9, 21));
        assertFalse(window.accept(102, 8, 22));
        assertTrue(window.accept(1, 9, 22));
    }

    @Test
    void duplicateOrSameTickInputIsRejectedAndAbandonedInputExpires() {
        FlightInputWindow window = new FlightInputWindow();
        window.relocate(1);
        assertFalse(window.accept(-1, 1, 30));
        assertTrue(window.accept(5, 1, 30));
        assertFalse(window.accept(6, 1, 30));
        assertFalse(window.accept(5, 1, 31));
        assertTrue(window.accept(6, 1, 31));
        assertFalse(window.expired(41));
        assertTrue(window.expired(42));
        assertThrows(IllegalArgumentException.class, () -> window.relocate(-1));
    }
}
