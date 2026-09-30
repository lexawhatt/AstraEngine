package dev.lexawhatt.astraengine.verification;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lexawhatt.astraengine.compat.construction.ArchivedAssemblyEntity;
import dev.lexawhatt.astraengine.compat.construction.ArchivedConstruction;
import dev.lexawhatt.astraengine.compat.construction.ArchivedEditorBlockEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native compatibility loading retains old construction data without any editor or part definitions. */
@PrefixGameTestTemplate(false)
public final class ArchivedConstructionGameTests {
    private static final UUID OLD_ASSEMBLY = UUID.fromString("a989e88b-3602-4b27-bba3-15d510a75dc6");
    private static final List<String> BLOCK_FIELDS =
            List.of("rocket_editor_version", "revision", "blueprint", "assembly");
    private static final List<String> ENTITY_FIELDS =
            List.of("rocket_assembly_version", "host_position", "blueprint");

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void archivedBlockRetainsOriginalAndUnknownNestedSchema(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
        var state = ArchivedConstruction.EDITOR.get().defaultBlockState();
        level.setBlockAndUpdate(position, state);
        var archive = (ArchivedEditorBlockEntity) level.getBlockEntity(position);
        helper.assertTrue(archive != null, "Historical block ID did not create its compatibility record");
        CompoundTag original = originalBlockTag();
        archive.loadCustomOnly(original, level.registryAccess());
        assertFields(helper, original, archive.saveCustomOnly(level.registryAccess()), BLOCK_FIELDS);

        CompoundTag extended = originalBlockTag();
        extended.getCompound("blueprint").put("future_payload", unknownPayload());
        CompoundTag expected = extended.copy();
        archive.loadCustomOnly(extended, level.registryAccess());
        extended.getCompound("blueprint").remove("parts");
        CompoundTag saved = archive.saveWithFullMetadata(level.registryAccess());
        assertFields(helper, expected, saved, BLOCK_FIELDS);
        helper.assertTrue(saved.getString("id").equals("astraengine:rocket_editor")
                        && saved.getInt("x") == position.getX() && saved.getInt("y") == position.getY()
                        && saved.getInt("z") == position.getZ(),
                "Archiving replaced native block identity or position metadata");
        BlockEntity restored = BlockEntity.loadStatic(position, state, saved, level.registryAccess());
        helper.assertTrue(restored instanceof ArchivedEditorBlockEntity,
                "Native historical block-entity ID failed compatibility loading");
        saved.getCompound("blueprint").remove("future_payload");
        assertFields(helper, expected, restored.saveCustomOnly(level.registryAccess()), BLOCK_FIELDS);
        assertFields(helper, expected, archive.saveCustomOnly(level.registryAccess()), BLOCK_FIELDS);
        helper.assertTrue(state.getDestroySpeed(level, position) < 0
                        && state.getBlock().getExplosionResistance() >= 3_600_000,
                "Archived data is vulnerable to accidental survival breaking or explosions");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void archiveDoesNotCoerceTypesOrInventFields(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
        var archive = new ArchivedEditorBlockEntity(position, ArchivedConstruction.EDITOR.get().defaultBlockState());
        CompoundTag unusual = parse("""
                {rocket_editor_version:"future",revision:[B;3b,-8b],
                 blueprint:{unrecognized:[{nested:[L;9007199254740993L,-4L]}]},
                 assembly:{retained:"unparsed"}}
                """);
        archive.loadCustomOnly(unusual, level.registryAccess());
        assertFields(helper, unusual, archive.saveCustomOnly(level.registryAccess()), BLOCK_FIELDS);
        archive.loadCustomOnly(new CompoundTag(), level.registryAccess());
        CompoundTag empty = archive.saveCustomOnly(level.registryAccess());
        for (String field : BLOCK_FIELDS) {
            helper.assertTrue(!empty.contains(field), "Archive invented a missing field: " + field);
        }
        var assembly = new ArchivedAssemblyEntity(ArchivedConstruction.ASSEMBLY.get(), level);
        CompoundTag entityTag = originalEntityTag(Vec3.atCenterOf(position), position);
        entityTag.putString("rocket_assembly_version", "unknown-version");
        entityTag.put("host_position", unknownPayload());
        entityTag.putByteArray("blueprint", new byte[] {0, -1, 127});
        assembly.load(entityTag);
        assertFields(helper, entityTag, assembly.saveWithoutId(new CompoundTag()), ENTITY_FIELDS);
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 80)
    public static void archivedAssemblyRetainsIdentityPoseAndMissingHostRecord(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos missingHost = helper.absolutePos(new BlockPos(1, 3, 1));
        level.setBlockAndUpdate(missingHost, Blocks.AIR.defaultBlockState());
        Vec3 position = Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 3, 3))).add(0.125, 0.25, -0.125);
        CompoundTag original = originalEntityTag(position, missingHost);
        original.getCompound("blueprint").put("future_payload", unknownPayload());
        CompoundTag expected = original.copy();
        Entity loaded = EntityType.create(original, level).orElseThrow();
        helper.assertTrue(loaded instanceof ArchivedAssemblyEntity,
                "Native historical entity ID failed compatibility loading");
        original.getCompound("blueprint").remove("parts");
        helper.assertTrue(level.addFreshEntity(loaded), "Cannot insert compatibility fixture in its native level");
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(!loaded.isRemoved() && level.getEntity(OLD_ASSEMBLY) == loaded,
                        "Archived assembly was deleted or changed UUID when its historical host was absent");
                helper.assertTrue(loaded.tickCount > 0, "Compatibility fixture never received native entity ticks");
                helper.assertTrue(loaded.position().equals(position)
                                && loaded.getYRot() == 37.5f && loaded.getXRot() == -12.25f,
                        "Inert compatibility record changed native position or rotation");
                helper.assertTrue(!loaded.isPickable() && !loaded.isPushable() && !loaded.canBeCollidedWith(),
                        "Archived record retained gameplay interaction or collision");
                CompoundTag saved = new CompoundTag();
                helper.assertTrue(loaded.save(saved), "Native save refused archived assembly");
                assertFields(helper, expected, saved, ENTITY_FIELDS);
                helper.assertTrue(saved.getString("id").equals("astraengine:rocket_assembly")
                                && saved.getUUID("UUID").equals(OLD_ASSEMBLY)
                                && saved.getList("Pos", Tag.TAG_DOUBLE).equals(expected.getList("Pos", Tag.TAG_DOUBLE))
                                && saved.getList("Rotation", Tag.TAG_FLOAT)
                                        .equals(expected.getList("Rotation", Tag.TAG_FLOAT)),
                        "Opaque payload overwrote native entity identity or pose metadata");
                Entity reloaded = EntityType.create(saved, level).orElseThrow();
                saved.getCompound("blueprint").remove("future_payload");
                assertFields(helper, expected, reloaded.saveWithoutId(new CompoundTag()), ENTITY_FIELDS);
                assertFields(helper, expected, loaded.saveWithoutId(new CompoundTag()), ENTITY_FIELDS);
                helper.assertTrue(reloaded.getUUID().equals(OLD_ASSEMBLY) && reloaded.position().equals(position),
                        "Native entity roundtrip changed identity or position");
                helper.succeed();
            } finally {
                loaded.discard();
            }
        });
    }

    private static CompoundTag originalBlockTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("rocket_editor_version", 1);
        tag.putLong("revision", 2);
        tag.putUUID("assembly", OLD_ASSEMBLY);
        tag.put("blueprint", parse(ORIGINAL_BLUEPRINT));
        return tag;
    }

    private static CompoundTag originalEntityTag(Vec3 position, BlockPos host) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "astraengine:rocket_assembly");
        tag.putUUID("UUID", OLD_ASSEMBLY);
        tag.putInt("rocket_assembly_version", 1);
        tag.putLong("host_position", host.asLong());
        tag.put("blueprint", parse(ORIGINAL_BLUEPRINT));
        tag.putBoolean("NoGravity", true);
        tag.put("Pos", doubles(position.x, position.y, position.z));
        tag.put("Motion", doubles(0.125, -0.25, 0.375));
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(37.5f));
        rotation.add(FloatTag.valueOf(-12.25f));
        tag.put("Rotation", rotation);
        return tag;
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double value : values) {
            list.add(DoubleTag.valueOf(value));
        }
        return list;
    }

    private static CompoundTag unknownPayload() {
        return parse("""
                {schema:"unavailable:warp-v999",nested:{bytes:[B;-128b,0b,127b],
                 integers:[I;-2147483648,0,2147483647],longs:[L;9007199254740993L,-9007199254740993L],
                 records:[{id:"missing:module",payload:{precise:0.12345678901234567d,empty:[]}}]}}
                """);
    }

    private static void assertFields(GameTestHelper helper, CompoundTag expected, CompoundTag actual,
            List<String> fields) {
        for (String field : fields) {
            helper.assertTrue(expected.get(field).equals(actual.get(field)),
                    "Opaque construction field changed type or content: " + field);
        }
    }

    private static CompoundTag parse(String snbt) {
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Invalid retained construction fixture", exception);
        }
    }

    // Exact blueprint from the successful September 29 native fixture checkpoint, including its
    // external verification:wormhole_core. The compatibility layer has no definitions for these IDs.
    private static final String ORIGINAL_BLUEPRINT = """
            {name:"Verified modular explorer",parts:[{definition:"astraengine:command_pod",id:0,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:800.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:control",values:[{flag:1b,key:"command",kind:"BOOLEAN"},
            {flag:0b,key:"attitude",kind:"BOOLEAN"}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:-1.0d},
            {key:"energy_kwh",kind:"NUMBER",number:2.0d}]},
            {id:"astraengine:thermal",values:[{key:"heat_kw",kind:"NUMBER",number:0.3d},
            {key:"cooling_kw",kind:"NUMBER",number:0.0d}]}],
            parent:-1,position_x:0.0d,position_y:3.0d,position_z:0.0d,size_x:2.0d,size_y:2.0d,size_z:2.0d,yaw:0},
            {definition:"astraengine:fuel_tank",id:1,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:250.0d},
            {key:"fuel_kg",kind:"NUMBER",number:2000.0d}]}],
            parent:0,position_x:0.0d,position_y:0.0d,position_z:0.0d,size_x:2.0d,size_y:4.0d,size_z:2.0d,yaw:0},
            {definition:"astraengine:engine",id:2,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:500.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:thrust_engine",values:[{key:"thrust_newtons",kind:"NUMBER",number:120000.0d},
            {key:"specific_impulse_seconds",kind:"NUMBER",number:320.0d}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:-5.0d},
            {key:"energy_kwh",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:thermal",values:[{key:"heat_kw",kind:"NUMBER",number:120.0d},
            {key:"cooling_kw",kind:"NUMBER",number:0.0d}]}],
            parent:1,position_x:0.0d,position_y:-2.75d,position_z:0.0d,size_x:2.0d,size_y:1.5d,size_z:2.0d,yaw:0},
            {definition:"astraengine:solar_panel",id:3,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:35.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:12.0d},
            {key:"energy_kwh",kind:"NUMBER",number:0.0d}]}],
            parent:1,position_x:2.5d,position_y:0.0d,position_z:0.0d,size_x:3.0d,size_y:1.4d,size_z:0.12d,yaw:0},
            {definition:"astraengine:solar_panel",id:4,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:35.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:12.0d},
            {key:"energy_kwh",kind:"NUMBER",number:0.0d}]}],
            parent:1,position_x:0.0d,position_y:0.0d,position_z:-2.5d,size_x:3.0d,size_y:1.4d,size_z:0.12d,yaw:1},
            {definition:"astraengine:solar_panel",id:5,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:35.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:12.0d},
            {key:"energy_kwh",kind:"NUMBER",number:0.0d}]}],
            parent:1,position_x:-2.5d,position_y:0.0d,position_z:0.0d,size_x:3.0d,size_y:1.4d,size_z:0.12d,yaw:2},
            {definition:"astraengine:solar_panel",id:6,modules:[{id:"astraengine:mass",values:[{key:"dry_mass_kg",kind:"NUMBER",number:35.0d},
            {key:"fuel_kg",kind:"NUMBER",number:0.0d}]},
            {id:"astraengine:power",values:[{key:"power_kw",kind:"NUMBER",number:12.0d},
            {key:"energy_kwh",kind:"NUMBER",number:0.0d}]}],
            parent:1,position_x:0.0d,position_y:0.0d,position_z:2.5d,size_x:3.0d,size_y:1.4d,size_z:0.12d,yaw:3},
            {definition:"verification:wormhole_core",id:7,modules:[{id:"verification:wormhole",values:[{flag:1b,key:"stabilized",kind:"BOOLEAN"},
            {choice:"wormhole",key:"transit_mode",kind:"CHOICE"},
            {key:"range_ly",kind:"NUMBER",number:123.5d}]}],
            parent:0,position_x:0.0d,position_y:4.5d,position_z:0.0d,size_x:1.5d,size_y:1.0d,size_z:1.0d,yaw:1}],version:1}
            """;
}
