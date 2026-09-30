package dev.lexawhatt.astraengine.verification;

import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Registers tests only in the isolated verification runs; excluded from the published jar. */
@Mod("astraengine_verify")
public final class VerificationMod {
    public VerificationMod(IEventBus bus) {
        bus.addListener((RegisterGameTestsEvent event) -> {
            event.register(EngineGameTests.class);
            event.register(CelestialApiGameTests.class);
            event.register(GalacticGameTests.class);
            event.register(AtlasGameTests.class);
            event.register(SeasonalSkyGameTests.class);
            event.register(ArchivedConstructionGameTests.class);
            if (Boolean.getBoolean("neoforge.gameTestServer")) {
                try {
                    GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File("gametest-results.xml")));
                } catch (ParserConfigurationException exception) {
                    throw new IllegalStateException("Could not create verification report", exception);
                }
            }
        });
    }
}
