# Scene and GLSL editor

Press **F7** in a world or run `/astra-editor` to open the editor over the live
world. **Esc / Done** returns control to the camera; pressing F7 again opens the
same draft. The game continues running, including system events.

This is a local scene-authoring tool. Shapes and lights are visible to this
client; server blocks, collisions, extraction, and system evolution are unchanged.

## Objects and properties

1. On the left, select a type with `Sphere / Box / Ring / Disk / Point / Spot /
   Directional`, then press `Add`. The object appears six blocks in front of the
   camera. Select objects by name in the list.
2. On the right, switch between `Position`, `Rotation`, `Scale`, `Material`, and
   `Light`. Enter values and press `Apply`: every field on the page is validated
   before the scene changes. Invalid values preserve the working scene.
3. `Visible` hides the selected object; `Copy`, `Delete`, `Undo`, and `Redo` operate
   on the draft. History stores up to 64 changes; a new edit clears redo history.
4. `Preview` toggles all draft objects and lights. `Torch`, `Sky`, and `Quality`
   control the existing lighting engine. `Sky` cycles through
   auto → space → planet → off; quality cycles through balanced → high → low.

Position uses blocks in the current world. Rotation uses degrees around X/Y/Z,
composed as `Rz * Ry * Rx`. A spot light initially points along local `+Z`;
a directional light's vector points toward the source. Scale defines half-extents:
a sphere can become an ellipsoid, and a box can become a rectangular prism.
Disks and rings lie in the local XZ plane; rotate X by 90° to face them from the
Z direction. `Ring hole` is a fraction of the outer radius, from 0 to 0.95.

RGB values range from 0 to 1. `Glow` boosts a shape's color before lighting and
bloom; it is not yet a separate emissive material. Use a separate light source to
illuminate nearby blocks. Lights support intensity from 0–16, range from 0.1–256
blocks, and inner/outer cone angles (the outer angle must exceed the inner angle
and stay below 90°). Fields unrelated to an object's type do not affect its image.

A draft supports **16 shapes and 16 lights**. Lights share the engine's selection
budget: 4/8/16 are selected per frame, including the sun, flashlight, and lights
from other mods. Hidden objects still count toward document limits. Shapes are
opaque and respect block depth and each other's depth; their depth feeds the
existing lighting pass.

Under ordinary `auto`, an active scene enables lighting without replacing the
vanilla sky. `Sky: off` disables AstraEngine shapes and lighting. The GLSL effect
has its own Enable/Disable button.

## Presets

At the bottom, enter a name using `a-z`, `0-9`, `_`, and `-`, without an extension.
`Save` writes the entire scene, replacing a preset with the same name; `Load`
validates and applies the file. `Browse` cycles through discovered names.
Presets use version 1 JSON in the game directory:

```text
config/astraengine/scenes/<name>.json
```

In development runs, this is `run/config/astraengine/scenes/`. Writes are atomic;
input and reads are limited to 1 MiB. Read or validation failures preserve the
current scene. Disk operations run in the background. Files are tied to a
dimension ID: the preview is hidden in another dimension, and loading a preset
for a different dimension is rejected. The draft becomes visible again on return.
`New scene` creates an empty draft for the current dimension. This change can be
undone within the same system.

Disconnecting clears the unsaved draft and its history. Presets never load
automatically: after joining, open the editor and press `Load`. Matching dimension
IDs across different saves do not identify the same world; select presets explicitly.

## Shader text editor

The **GLSL** button opens a multiline editor. `Example` inserts sample code;
`Apply` compiles it and immediately enables the effect. The driver's log appears
on the right; compilation errors or an incompatible interface preserve the last
working program. `Enable / Disable` compares the result with the original frame.
`Back` returns to shapes. Standard selection, copy, and paste are supported.
`Ctrl+Enter` applies the code, `Ctrl+S` saves the text, and `Tab` inserts four spaces.

The initial interface is a **GLSL 150 fragment post-effect**, running after world
rendering and bloom, before the hand and UI. The engine supplies the vertex stage.

| Name | Type / meaning |
| --- | --- |
| `texCoord` | `in vec2`, UV 0–1 with a bottom-left origin |
| `clipPosition` | `in vec2`, screen coordinates −1…1 |
| `SceneColor` | `uniform sampler2D`, copy of the color before the effect |
| `SceneDepth` | `uniform sampler2D`, nonlinear OpenGL depth 0–1 |
| `Time` | `uniform float`, seconds since the last Apply |
| `ScreenSize` | `uniform vec2`, framebuffer size in pixels |
| `InverseViewProjection` | `uniform mat4`, reconstructs camera-relative position |
| `ViewProjection` | `uniform mat4`, transforms back into clip space |
| `fragColor` | `out vec4`, final color |

Declare only the inputs you need; built-in uniform types must match.
Arbitrary additional active uniforms and arrays of built-in uniforms are rejected:
this interface has no bindings for user-defined textures or parameters.
A simple effect:

```glsl
#version 150
uniform sampler2D SceneColor;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 source = texture(SceneColor, texCoord);
    float gray = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(mix(source.rgb, vec3(gray), 0.65), source.a);
}
```

`Save / Load` use `.fsh` text files in `config/astraengine/shaders/`, limited to
64 KiB of UTF-8. Loading text does not compile it. After `F3+T`, the GPU program
is released while the text remains; press Apply again. Disconnecting discards
unsaved text. The effect applies only in the dimension where Apply was pressed
and is hidden when entering another dimension.

## Current limits

The tool uses native Minecraft Screen/widgets in an inspector layout, without a
Dear ImGui dependency. If the GUI scale is too large, it prompts you to reduce it;
the draft survives resize.

Arbitrary meshes, a material graph, a server-build editor, scene synchronization
between players, and full HDR lighting are not implemented yet. Shadows remain
screen-space. Shader code runs on the local GPU; the editor does not limit
algorithm cost. Shader-pack compatibility and complete interaction between shapes
and transparency require separate verification.

For details of the existing lighting system, see [RENDERING.md](RENDERING.md).
