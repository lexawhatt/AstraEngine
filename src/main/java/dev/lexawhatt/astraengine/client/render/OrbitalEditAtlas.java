package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.surface.orbit.OrbitalSummaryClient;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalHash;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Render-owned 4.3 MiB sparse material/height/emission atlas; connection-owned CPU observations survive resource reload. */
public final class OrbitalEditAtlas implements AutoCloseable {
    private static final int ROWS = 33;
    private OrbitalSummaryClient source;
    private int texture;
    private long revision = -1;
    private String system = "";
    private List<String> bodies = List.of();
    private int count;
    private int probes;

    /** Binds one connection owner; never takes ownership of its lifetime or authoritative state. */
    public void setSource(OrbitalSummaryClient source) {
        if (source == null) { throw new IllegalArgumentException("Orbital connection owner is required"); }
        this.source = source; revision = -1;
    }

    /** Body order must exactly match the current celestial uniform arrays. Render thread only. */
    public void bind(ShaderInstance shader, String systemId, List<String> bodyIds) {
        RenderSystem.assertOnRenderThread();
        if (source != null && (revision != source.revision() || !system.equals(systemId) || !bodies.equals(bodyIds))) {
            upload(systemId, bodyIds);
            revision = source.revision(); system = systemId; bodies = List.copyOf(bodyIds);
        }
        shader.safeGetUniform("OrbitalEditsReady").set(texture == 0 || count == 0 ? 0 : 1);
        shader.safeGetUniform("OrbitalEditsProbes").set(Math.max(1, probes));
        shader.setSampler("OrbitalEdits", texture);
    }

    private void upload(String systemId, List<String> bodyIds) {
        var merged = new HashMap<Key, List<OrbitalPatch.Cell>>();
        for (var patch : source.patches()) {
            if (!patch.surface().systemId().equals(systemId)) { continue; }
            int body = bodyIds.indexOf(patch.surface().bodyId()); if (body < 0 || body >= 12) { continue; }
            int marker = OrbitalHash.marker(body, patch.surface().face().ordinal(), patch.level());
            var key = new Key(marker, patch.x(), patch.z());
            var cells = merged.computeIfAbsent(key, ignored -> new ArrayList<>(java.util.Collections.nCopies(16, OrbitalPatch.Cell.EMPTY)));
            for (int i = 0; i < 16; i++) {
                var old = cells.get(i); var value = patch.cells().get(i);
                // Altitude bands are separate canonical worlds but only the highest observed top is visible from orbit.
                if (value.coverage() > 0 && (old.coverage() == 0 || value.altitudeMeters() > old.altitudeMeters())) {
                    cells.set(i, value);
                }
            }
        }
        int[] occupied = new int[OrbitalHash.SLOTS];
        FloatBuffer data = MemoryUtil.memCallocFloat(OrbitalHash.SLOTS * ROWS * 4);
        probes = 0;
        try {
            for (var entry : merged.entrySet()) {
                var key = entry.getKey(); int initial = OrbitalHash.slot(key.x, key.z, key.marker), slot = initial;
                int probe = 0;
                while (occupied[slot] != 0 && probe < OrbitalHash.MAX_PROBES) {
                    probe++; slot = (initial + probe) & (OrbitalHash.SLOTS - 1);
                }
                if (probe == OrbitalHash.MAX_PROBES) { throw new IllegalStateException("Orbital atlas collision budget exhausted"); }
                probes = Math.max(probes, probe + 1); occupied[slot] = key.marker;
                put(data, slot, 0, key.x, key.z, key.marker, 0);
                for (int i = 0; i < 16; i++) {
                    var cell = entry.getValue().get(i); int rgb = cell.rgb();
                    put(data, slot, i * 2 + 1, ((rgb >>> 16) & 255) / 255f, ((rgb >>> 8) & 255) / 255f,
                            (rgb & 255) / 255f, cell.altitudeMeters());
                    put(data, slot, i * 2 + 2, cell.emission(), cell.coverage(), 0, 0);
                }
            }
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                if (texture == 0) { texture = TextureUtil.generateTextureId(); }
                RenderSystem.bindTexture(texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, OrbitalHash.SLOTS, ROWS, 0,
                        GL11.GL_RGBA, GL11.GL_FLOAT, data);
            } finally { RenderSystem.bindTexture(previous); RenderSystem.activeTexture(active); }
            count = merged.size();
        } finally { MemoryUtil.memFree(data); }
    }

    private static void put(FloatBuffer data, int slot, int row, float x, float y, float z, float w) {
        int offset = (row * OrbitalHash.SLOTS + slot) * 4;
        data.put(offset, x); data.put(offset + 1, y); data.put(offset + 2, z); data.put(offset + 3, w);
    }

    /** Releases only owned GPU storage. Calling again is harmless; the CPU owner is intentionally retained. */
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (texture != 0) { TextureUtil.releaseTextureId(texture); texture = 0; }
        revision = -1; count = 0; probes = 0; system = ""; bodies = List.of();
    }
    private record Key(int marker, int x, int z) { }
}
