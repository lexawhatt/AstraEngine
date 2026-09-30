package dev.lexawhatt.astraengine.client.compat;

import net.irisshaders.iris.api.v0.IrisApi;

/** Optional public API linkage, reached only after RenderCompatibility confirms that Iris is loaded. */
final class IrisBridge {
    private IrisBridge() {
    }

    static RenderCompatibility.Probe probe() {
        IrisApi api = IrisApi.getInstance();
        return new RenderCompatibility.Probe(RenderCompatibility.IrisState.AVAILABLE,
                api.getMinorApiRevision(), api.isShaderPackInUse(), api.isRenderingShadowPass(), "");
    }
}
