package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.network.EarthWeatherPayload;
import dev.lexawhatt.astraengine.server.SkyService;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual source-Earth snapshot and wire validation, independent of the recipient's staging level. */
@PrefixGameTestTemplate(false)
public final class EarthWeatherGameTests {
    private EarthWeatherGameTests() { }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void sourceEarthWeatherAndCalendarHaveBoundedValidatedTransport(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var source = server.overworld();
        float rain = source.getRainLevel(1), thunder = source.getThunderLevel(1);
        long day = source.getDayTime();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            source.setRainLevel(.7f); source.setThunderLevel(.3f); source.setDayTime(341_137);
            var payload = SkyService.weather(server);
            helper.assertTrue(payload.gameTime() == source.getGameTime() && payload.dayTime() == 341_137
                    && Math.abs(payload.rain() - .7f) < 1e-6 && payload.thunder() > 0,
                    "Weather source was not the actual Earth world");
            EarthWeatherPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() == 32, "Weather payload lost its bounded 32-byte layout");
            helper.assertTrue(EarthWeatherPayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                    "Weather/calendar snapshot changed on the wire");
            for (long calendar : new long[] {Long.MIN_VALUE, -24000, 0, 24_000L * 365, Long.MAX_VALUE}) {
                buffer.clear();
                var exact = new EarthWeatherPayload(Long.MAX_VALUE, calendar, .999999999999, .25f, .125f);
                EarthWeatherPayload.CODEC.encode(buffer, exact);
                helper.assertTrue(EarthWeatherPayload.CODEC.decode(buffer).equals(exact),
                        "Signed calendar or long host clock lost precision during transport");
            }
            for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f}) {
                buffer.clear(); EarthWeatherPayload.CODEC.encode(buffer, payload); buffer.setFloat(24, invalid);
                boolean rejected = false;
                try { EarthWeatherPayload.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected, "Malformed weather replaced valid presentation state");
            }
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.01, 1}) {
                buffer.clear(); EarthWeatherPayload.CODEC.encode(buffer, payload); buffer.setDouble(16, invalid);
                boolean rejected = false;
                try { EarthWeatherPayload.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected, "Malformed seasonal phase was accepted");
            }
            source.setRainLevel(.1f); source.setDayTime(day + 24000 * 90L);
            var changed = SkyService.weather(server);
            helper.assertTrue(changed.rain() < payload.rain() && changed.seasonPhase() != payload.seasonPhase(),
                    "A changed source weather/calendar was frozen to the previous snapshot");
        } finally {
            source.setRainLevel(rain); source.setThunderLevel(thunder); source.setDayTime(day); buffer.release();
        }
        helper.succeed();
    }
}
