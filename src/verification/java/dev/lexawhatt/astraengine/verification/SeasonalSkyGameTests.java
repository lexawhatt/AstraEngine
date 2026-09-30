package dev.lexawhatt.astraengine.verification;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.network.SkyProfilePayload;
import dev.lexawhatt.astraengine.server.SkyState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises saved seasonal settings, bounded profile transport and actual dedicated-server command ownership. */
@PrefixGameTestTemplate(false)
public final class SeasonalSkyGameTests {
    private SeasonalSkyGameTests() {
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void skySettingsPersistAndRejectMalformedData(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        SkyState detached = SkyState.decode(SkyState.get(server).save(new CompoundTag(), server.registryAccess()));
        PlanetarySkyProfile profile = new PlanetarySkyProfile(365, -52.5, 27.5, 0.18, 351.75, 0.38, 3.5);
        long initialRevision = detached.revision();
        helper.assertTrue(detached.configure(profile) && detached.revision() == initialRevision + 1,
                "Changed settings did not advance exactly one revision");
        helper.assertTrue(!detached.configure(profile) && detached.revision() == initialRevision + 1,
                "Equal settings unexpectedly dirtied the revision");
        CompoundTag saved = detached.save(new CompoundTag(), server.registryAccess());
        SkyState restored = SkyState.decode(saved);
        helper.assertTrue(restored.profile().equals(profile) && restored.revision() == detached.revision(),
                "Sky profile or exact revision changed through persistence");
        helper.assertTrue(restored.save(new CompoundTag(), server.registryAccess()).equals(saved),
                "Sky settings did not round trip all persisted fields exactly");
        for (String key : saved.getAllKeys()) {
            CompoundTag missing = saved.copy();
            missing.remove(key);
            helper.assertTrue(rejectsSave(missing), "Missing saved sky field was silently defaulted: " + key);
            CompoundTag wrongType = saved.copy();
            wrongType.putString(key, "invalid");
            helper.assertTrue(rejectsSave(wrongType), "Saved sky field accepted the wrong NBT type: " + key);
        }
        CompoundTag unknownVersion = saved.copy();
        unknownVersion.putInt("version", 999);
        helper.assertTrue(rejectsSave(unknownVersion), "Unknown seasonal sky format was accepted");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 91}) {
            CompoundTag badLatitude = saved.copy();
            badLatitude.putDouble("latitude_degrees", invalid);
            helper.assertTrue(rejectsSave(badLatitude), "Malformed persisted latitude was accepted: " + invalid);
        }
        CompoundTag negativeRevision = saved.copy();
        negativeRevision.putLong("revision", -1);
        helper.assertTrue(rejectsSave(negativeRevision), "Negative sky revision was accepted");
        CompoundTag maximumRevision = saved.copy();
        maximumRevision.putLong("revision", Long.MAX_VALUE);
        SkyState exhausted = SkyState.decode(maximumRevision);
        boolean rejected = false;
        try { exhausted.configure(profile.withLightPollution(0)); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected && exhausted.profile().equals(profile) && exhausted.revision() == Long.MAX_VALUE,
                "Exhausted revision partially mutated valid settings");
        boolean nullRejected = false;
        try { restored.configure(null); }
        catch (IllegalArgumentException expected) { nullRejected = true; }
        helper.assertTrue(nullRejected && restored.profile().equals(profile), "Null update changed valid settings");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void boundedSkyPayloadAndOperatorCommands(GameTestHelper helper) throws CommandSyntaxException {
        var server = helper.getLevel().getServer();
        PlanetarySkyProfile profile = PlanetarySkyProfile.EARTH.withLatitudeDegrees(67).withLightPollution(0.32);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            SkyProfilePayload payload = new SkyProfilePayload(123, profile);
            SkyProfilePayload.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() == 60, "Sky payload lost its constant bounded 60-byte representation");
            helper.assertTrue(SkyProfilePayload.CODEC.decode(buffer).equals(payload) && !buffer.isReadable(),
                    "Sky profile wire roundtrip changed data or left trailing bytes");
            buffer.clear();
            SkyProfilePayload.CODEC.encode(buffer, payload);
            buffer.setLong(0, -1);
            helper.assertTrue(rejectsPayload(buffer), "Negative received sky revision was accepted");
            buffer.clear();
            SkyProfilePayload.CODEC.encode(buffer, payload);
            buffer.setDouble(12, Double.NaN);
            helper.assertTrue(rejectsPayload(buffer), "Non-finite sky wire latitude was accepted");
            buffer.clear();
            SkyProfilePayload.CODEC.encode(buffer, payload);
            buffer.setInt(8, Integer.MAX_VALUE);
            helper.assertTrue(rejectsPayload(buffer), "Unbounded sky wire year length was accepted");
        } finally {
            buffer.release();
        }
        var dispatcher = server.getCommands().getDispatcher();
        var command = dispatcher.getRoot().getChild("astra").getChild("season");
        helper.assertTrue(command != null, "Seasonal command subtree was not registered");
        helper.assertTrue(!command.canUse(server.createCommandSourceStack().withPermission(1)),
                "Non-operator can change world season settings");
        var operator = server.createCommandSourceStack().withPermission(2);
        helper.assertTrue(command.canUse(operator), "Operator cannot configure the seasonal sky");
        PlanetarySkyProfile original = AstraSky.profile(server);
        long time = server.overworld().getDayTime();
        try {
            for (String season : new String[]{"spring", "summer", "autumn", "winter"}) {
                helper.assertTrue(dispatcher.execute("astra season set " + season, operator) == 1,
                        "Season command did not execute: " + season);
                double target = switch (season) { case "spring" -> 0; case "summer" -> 0.25;
                    case "autumn" -> 0.5; default -> 0.75; };
                double difference = Math.abs(AstraSky.snapshot(server).seasonPhase() - target);
                helper.assertTrue(Math.min(difference, 1 - difference) < 1.0e-10,
                        "Season command did not select the requested orbital longitude");
                helper.assertTrue(server.overworld().getDayTime() == time, "Season command changed host time");
            }
            for (String valid : new String[]{"year 365", "latitude 52", "tilt 23.44", "pollution 0.3", "sun-size 3.5"}) {
                helper.assertTrue(dispatcher.execute("astra season " + valid, operator) == 1,
                        "Valid season setting failed: " + valid);
            }
            PlanetarySkyProfile beforeInvalid = AstraSky.profile(server);
            long revision = SkyState.get(server).revision();
            for (String invalid : new String[]{"year 0", "year 1000001", "latitude 91", "tilt -1", "pollution 2", "sun-size 0"}) {
                boolean rejected = false;
                try { dispatcher.execute("astra season " + invalid, operator); }
                catch (CommandSyntaxException expected) { rejected = true; }
                helper.assertTrue(rejected && AstraSky.profile(server).equals(beforeInvalid)
                                && SkyState.get(server).revision() == revision,
                        "Rejected season command changed valid state: " + invalid);
            }
            boolean threadRejected = CompletableFuture.supplyAsync(() -> {
                try { AstraSky.profile(server); return false; }
                catch (IllegalStateException expected) { return true; }
            }).join();
            helper.assertTrue(threadRejected, "Public sky API allowed asynchronous server-state access");
            helper.assertTrue(server.overworld().getDayTime() == time, "Sky configuration introduced a second time owner");
        } finally {
            AstraSky.configure(server, original);
        }
        helper.succeed();
    }

    private static boolean rejectsSave(CompoundTag tag) {
        try { SkyState.decode(tag); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }

    private static boolean rejectsPayload(RegistryFriendlyByteBuf buffer) {
        try { SkyProfilePayload.CODEC.decode(buffer); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
}
