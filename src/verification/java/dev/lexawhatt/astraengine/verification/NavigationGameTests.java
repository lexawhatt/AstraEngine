package dev.lexawhatt.astraengine.verification;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Dynamic;
import dev.lexawhatt.astraengine.cosmos.NavigationPolicy;
import dev.lexawhatt.astraengine.network.NavigationPolicyPayload;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.NavigationRules;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated host command permissions, bounded rule parsing, persistence and policy transport. */
@PrefixGameTestTemplate(false)
public final class NavigationGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void hostRulesPersistWithoutGrantingExploration(GameTestHelper helper) throws CommandSyntaxException {
        var server = helper.getLevel().getServer();
        var rules = server.getGameRules();
        var original = NavigationRules.policy(server);
        var dispatcher = server.getCommands().getDispatcher();
        var operator = server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
        var catalog = ExplorationCatalog.get(server);
        var before = catalog.save(new CompoundTag(), server.registryAccess());
        try {
            helper.assertTrue(!dispatcher.getRoot().getChild("gamerule").canUse(operator.withPermission(1)),
                    "Navigation cheats must retain host operator permission");
            dispatcher.execute("gamerule astraFreeNavigation true", operator);
            dispatcher.execute("gamerule astraTravelSeconds 1", operator);
            var expected = new NavigationPolicy(true, 1);
            helper.assertTrue(NavigationRules.policy(server).equals(expected), "Commands did not update server policy");
            helper.assertTrue(catalog.save(new CompoundTag(), server.registryAccess()).equals(before),
                    "Enabling a cheat fabricated discoveries or visits");
            var restored = new GameRules(new Dynamic<>(NbtOps.INSTANCE, rules.createTag()));
            helper.assertTrue(restored.getBoolean(NavigationRules.FREE_NAVIGATION)
                            && restored.getInt(NavigationRules.TRAVEL_SECONDS) == 1, "Rules did not survive host NBT roundtrip");
            for (int invalid : new int[] {-1, 3601, Integer.MAX_VALUE}) {
                boolean rejected = false;
                try { dispatcher.execute("gamerule astraTravelSeconds " + invalid, operator); }
                catch (CommandSyntaxException expectedFailure) { rejected = true; }
                helper.assertTrue(rejected && NavigationRules.policy(server).equals(expected),
                        "Invalid duration changed the last valid rule");
            }
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            try {
                for (int seconds : new int[] {0, 1, 3600}) {
                    var packet = new NavigationPolicyPayload(new NavigationPolicy(true, seconds));
                    NavigationPolicyPayload.CODEC.encode(buffer, packet);
                    helper.assertTrue(NavigationPolicyPayload.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(),
                            "Navigation policy changed on wire");
                    buffer.clear();
                }
                buffer.writeBoolean(true); buffer.writeVarInt(-1);
                boolean rejected = false;
                try { NavigationPolicyPayload.CODEC.decode(buffer); }
                catch (IllegalArgumentException expectedFailure) { rejected = true; }
                helper.assertTrue(rejected, "Malformed policy payload accepted");
            } finally { buffer.release(); }
        } finally {
            rules.getRule(NavigationRules.FREE_NAVIGATION).set(original.freeNavigation(), server);
            rules.getRule(NavigationRules.TRAVEL_SECONDS).set(original.travelSeconds(), server);
        }
        helper.succeed();
    }
}
