package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import dev.lexawhatt.astraengine.rocket.RocketValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Strict version-one storage and bounded wire encoding of namespaced rocket instances and typed module values. */
public final class RocketBlueprintCodec {
    private RocketBlueprintCodec() {}

    /** Encodes a catalog-valid immutable draft; the caller owns the returned mutable tag. */
    public static CompoundTag encode(RocketBlueprint blueprint) {
        RocketWorkshop.catalog().validate(blueprint);
        CompoundTag root = new CompoundTag(); root.putInt("version", 1); root.putString("name", blueprint.name());
        ListTag parts = new ListTag();
        for (RocketPart part : blueprint.parts()) {
            CompoundTag tag = new CompoundTag(); tag.putInt("id", part.id()); tag.putInt("parent", part.parentId());
            tag.putString("definition", part.definitionId()); tag.putInt("yaw", part.yawQuarterTurns());
            vector(tag, "position", part.position()); vector(tag, "size", part.size());
            ListTag modules = new ListTag();
            part.moduleValues().forEach((moduleId, values) -> {
                CompoundTag module = new CompoundTag(); module.putString("id", moduleId); ListTag fields = new ListTag();
                values.forEach((key, value) -> {
                    CompoundTag field = new CompoundTag(); field.putString("key", key); field.putString("kind", value.kind().name());
                    switch (value.kind()) {
                        case NUMBER -> field.putDouble("number", value.number());
                        case BOOLEAN -> field.putBoolean("flag", value.flag());
                        case CHOICE -> field.putString("choice", value.choice());
                    }
                    fields.add(field);
                });
                module.put("values", fields); modules.add(module);
            });
            tag.put("modules", modules); parts.add(tag);
        }
        root.put("parts", parts); return root;
    }

    /** Rejects unknown definitions, invalid schemas, graph/geometry and malformed exact NBT types atomically. */
    public static RocketBlueprint decode(CompoundTag root) {
        if (root == null) { throw new IllegalArgumentException("A rocket blueprint tag is required"); }
        require(root, Tag.TAG_INT, "version"); require(root, Tag.TAG_STRING, "name");
        if (root.getInt("version") != 1) { throw new IllegalArgumentException("Unsupported rocket blueprint version"); }
        ListTag tags = compounds(root, "parts", 32); List<RocketPart> parts = new ArrayList<>(tags.size());
        for (Tag value : tags) {
            CompoundTag tag = (CompoundTag) value;
            require(tag, Tag.TAG_INT, "id", "parent", "yaw"); require(tag, Tag.TAG_STRING, "definition");
            Map<String, Map<String, RocketValue>> modules = new LinkedHashMap<>(); int count = 0;
            for (Tag moduleTag : compounds(tag, "modules", 8)) {
                CompoundTag module = (CompoundTag) moduleTag; require(module, Tag.TAG_STRING, "id");
                Map<String, RocketValue> fields = new LinkedHashMap<>();
                for (Tag fieldTag : compounds(module, "values", 32)) {
                    if (++count > 32) { throw new IllegalArgumentException("Rocket module parameter budget exceeded"); }
                    CompoundTag field = (CompoundTag) fieldTag; require(field, Tag.TAG_STRING, "key", "kind");
                    RocketValue.Kind kind = RocketValue.Kind.valueOf(field.getString("kind"));
                    RocketValue parameter = switch (kind) {
                        case NUMBER -> { require(field, Tag.TAG_DOUBLE, "number"); yield RocketValue.number(field.getDouble("number")); }
                        case BOOLEAN -> { require(field, Tag.TAG_BYTE, "flag"); if (field.getByte("flag") < 0 || field.getByte("flag") > 1) { throw new IllegalArgumentException("Noncanonical boolean rocket value"); } yield RocketValue.flag(field.getBoolean("flag")); }
                        case CHOICE -> { require(field, Tag.TAG_STRING, "choice"); yield RocketValue.choice(field.getString("choice")); }
                    };
                    if (fields.put(field.getString("key"), parameter) != null) { throw new IllegalArgumentException("Duplicate rocket parameter"); }
                }
                if (modules.put(module.getString("id"), fields) != null) { throw new IllegalArgumentException("Duplicate rocket module"); }
            }
            parts.add(new RocketPart(tag.getInt("id"), tag.getInt("parent"), tag.getString("definition"), vector(tag, "position"),
                    vector(tag, "size"), tag.getInt("yaw"), modules));
        }
        RocketBlueprint blueprint = new RocketBlueprint(root.getString("name"), parts);
        RocketWorkshop.catalog().validate(blueprint); return blueprint;
    }

    /**
     * Writes schema-ordered compact values. A 32-byte definition fingerprint rejects mismatched installations.
     * Choice indices and omitted repeated field names keep the maximum 32-part draft below 16 KiB.
     */
    public static void write(RegistryFriendlyByteBuf buffer, RocketBlueprint blueprint) {
        RocketWorkshop.catalog().validate(blueprint);
        buffer.writeVarInt(1);
        buffer.writeUtf(blueprint.name(), 64);
        buffer.writeVarInt(blueprint.parts().size());
        for (RocketPart part : blueprint.parts()) {
            RocketPartDefinition definition = RocketWorkshop.catalog().requireDefinition(part.definitionId());
            buffer.writeVarInt(part.id());
            buffer.writeVarInt(part.parentId());
            buffer.writeUtf(part.definitionId(), 64);
            buffer.writeBytes(fingerprint(definition));
            vector(buffer, part.position());
            vector(buffer, part.size());
            buffer.writeVarInt(part.yawQuarterTurns());
            for (var module : definition.modules()) {
                for (var parameter : module.parameters()) {
                    RocketValue value = part.moduleValues().get(module.id()).get(parameter.key());
                    switch (parameter.kind()) {
                        case NUMBER -> buffer.writeDouble(value.number());
                        case BOOLEAN -> buffer.writeBoolean(value.flag());
                        case CHOICE -> buffer.writeVarInt(parameter.choices().indexOf(value.choice()));
                    }
                }
            }
        }
    }

    /** Bounded identifiers and fingerprints resolve a fixed schema before any variable data is allocated. */
    public static RocketBlueprint read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readVarInt() != 1) {
            throw new IllegalArgumentException("Unsupported rocket wire version");
        }
        String name = buffer.readUtf(64);
        int count = count(buffer, 32);
        List<RocketPart> parts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int id = buffer.readVarInt(), parent = buffer.readVarInt();
            String definitionId = buffer.readUtf(64);
            RocketPartDefinition definition = RocketWorkshop.catalog().requireDefinition(definitionId);
            byte[] receivedFingerprint = new byte[32];
            buffer.readBytes(receivedFingerprint);
            if (!Arrays.equals(receivedFingerprint, fingerprint(definition))) {
                throw new IllegalArgumentException("Mismatched rocket definition: " + definitionId);
            }
            SpaceVector position = vector(buffer), size = vector(buffer);
            int yaw = buffer.readVarInt();
            Map<String, Map<String, RocketValue>> modules = new LinkedHashMap<>();
            for (var module : definition.modules()) {
                Map<String, RocketValue> values = new LinkedHashMap<>();
                for (var parameter : module.parameters()) {
                    RocketValue value = switch (parameter.kind()) {
                        case NUMBER -> RocketValue.number(buffer.readDouble());
                        case BOOLEAN -> {
                            byte flag = buffer.readByte();
                            if (flag < 0 || flag > 1) {
                                throw new IllegalArgumentException("Noncanonical rocket wire boolean");
                            }
                            yield RocketValue.flag(flag == 1);
                        }
                        case CHOICE -> {
                            int choice = count(buffer, parameter.choices().size() - 1);
                            yield RocketValue.choice(parameter.choices().get(choice));
                        }
                    };
                    values.put(parameter.key(), value);
                }
                modules.put(module.id(), values);
            }
            parts.add(new RocketPart(id, parent, definitionId, position, size, yaw, modules));
        }
        RocketBlueprint blueprint = new RocketBlueprint(name, parts);
        RocketWorkshop.catalog().validate(blueprint);
        return blueprint;
    }

    private static byte[] fingerprint(RocketPartDefinition definition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestField(digest, definition.id());
            digestField(digest, definition.displayName());
            digestField(digest, definition.category());
            digestField(digest, definition.visual().toString());
            digestField(digest, definition.defaultSize().toString());
            digestField(digest, Integer.toString(definition.modules().size()));
            for (var module : definition.modules()) {
                digestField(digest, module.id());
                digestField(digest, module.displayName());
                digestField(digest, Integer.toString(module.parameters().size()));
                for (var parameter : module.parameters()) {
                    digestField(digest, parameter.key());
                    digestField(digest, parameter.displayName());
                    digestField(digest, parameter.unit());
                    digestField(digest, parameter.kind().name());
                    digestField(digest, Double.toHexString(parameter.minimum()));
                    digestField(digest, Double.toHexString(parameter.maximum()));
                    digestField(digest, Integer.toString(parameter.choices().size()));
                    for (String choice : parameter.choices()) { digestField(digest, choice); }
                    RocketValue value = parameter.defaultValue();
                    digestField(digest, Double.toHexString(value.number()));
                    digestField(digest, Boolean.toString(value.flag()));
                    digestField(digest, value.choice());
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime lacks mandatory SHA-256", exception);
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static int count(RegistryFriendlyByteBuf buffer, int maximum) { int value = buffer.readVarInt(); if (value < 0 || value > maximum) { throw new IllegalArgumentException("Rocket packet count exceeds bounds"); } return value; }
    private static ListTag compounds(CompoundTag root, String key, int maximum) { require(root, Tag.TAG_LIST, key); ListTag result = root.getList(key, Tag.TAG_COMPOUND); if (result.size() > maximum || result.size() != ((ListTag) root.get(key)).size()) { throw new IllegalArgumentException("Invalid rocket list " + key); } return result; }
    private static void vector(CompoundTag tag, String key, SpaceVector value) { tag.putDouble(key + "_x", value.x()); tag.putDouble(key + "_y", value.y()); tag.putDouble(key + "_z", value.z()); }
    private static SpaceVector vector(CompoundTag tag, String key) { require(tag, Tag.TAG_DOUBLE, key + "_x", key + "_y", key + "_z"); return new SpaceVector(tag.getDouble(key + "_x"), tag.getDouble(key + "_y"), tag.getDouble(key + "_z")); }
    private static void vector(RegistryFriendlyByteBuf buffer, SpaceVector value) { buffer.writeDouble(value.x()); buffer.writeDouble(value.y()); buffer.writeDouble(value.z()); }
    private static SpaceVector vector(RegistryFriendlyByteBuf buffer) { return new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()); }
    private static void require(CompoundTag tag, int type, String... keys) { for (String key : keys) { if (!tag.contains(key, type)) { throw new IllegalArgumentException("Missing or invalid rocket field: " + key); } } }
}
