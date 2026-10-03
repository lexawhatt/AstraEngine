package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Calendar;
import java.util.EnumMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** One bake job's vanilla chest models and sprite coordinates; no block entities, inventories or live level. */
final class BoundaryChestModels {
    private final EnumMap<ChestType, ModelPart> models = new EnumMap<>(ChestType.class);
    private final EnumMap<ChestType, TextureAtlasSprite> normal = new EnumMap<>(ChestType.class);
    private final EnumMap<ChestType, TextureAtlasSprite> trapped = new EnumMap<>(ChestType.class);
    private final TextureAtlasSprite ender;

    /** Captures host model layers and atlas sprites on the render thread before handing this job to its worker. */
    BoundaryChestModels(Minecraft game) {
        models.put(ChestType.SINGLE, game.getEntityModels().bakeLayer(ModelLayers.CHEST));
        models.put(ChestType.LEFT, game.getEntityModels().bakeLayer(ModelLayers.DOUBLE_CHEST_LEFT));
        models.put(ChestType.RIGHT, game.getEntityModels().bakeLayer(ModelLayers.DOUBLE_CHEST_RIGHT));
        var date = Calendar.getInstance();
        boolean holiday = date.get(Calendar.MONTH) == Calendar.DECEMBER
                && date.get(Calendar.DAY_OF_MONTH) >= 24 && date.get(Calendar.DAY_OF_MONTH) <= 26;
        for (var type : ChestType.values()) {
            normal.put(type, material(type, false, holiday).sprite());
            trapped.put(type, material(type, true, holiday).sprite());
        }
        ender = Sheets.ENDER_CHEST_LOCATION.sprite();
    }

    static boolean supported(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST);
    }

    void render(BlockState state, boolean open, PoseStack pose, VertexConsumer consumer, int light) {
        var type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        var model = models.get(type);
        var sprite = state.is(Blocks.ENDER_CHEST) ? ender
                : (state.is(Blocks.TRAPPED_CHEST) ? trapped : normal).get(type);
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-state.getValue(ChestBlock.FACING).toYRot()));
        pose.translate(-.5, -.5, -.5);
        float angle = open ? -(float) (Math.PI / 2) : 0;
        model.getChild("lid").xRot = angle;
        model.getChild("lock").xRot = angle;
        model.render(pose, sprite.wrap(consumer), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static Material material(ChestType type, boolean trapped, boolean holiday) {
        if (holiday) {
            return switch (type) {
                case SINGLE -> Sheets.CHEST_XMAS_LOCATION;
                case LEFT -> Sheets.CHEST_XMAS_LOCATION_LEFT;
                case RIGHT -> Sheets.CHEST_XMAS_LOCATION_RIGHT;
            };
        }
        if (trapped) {
            return switch (type) {
                case SINGLE -> Sheets.CHEST_TRAP_LOCATION;
                case LEFT -> Sheets.CHEST_TRAP_LOCATION_LEFT;
                case RIGHT -> Sheets.CHEST_TRAP_LOCATION_RIGHT;
            };
        }
        return switch (type) {
            case SINGLE -> Sheets.CHEST_LOCATION;
            case LEFT -> Sheets.CHEST_LOCATION_LEFT;
            case RIGHT -> Sheets.CHEST_LOCATION_RIGHT;
        };
    }
}
