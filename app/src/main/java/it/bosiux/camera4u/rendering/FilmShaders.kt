package it.bosiux.camera4u.rendering

/**
 * GLSL ES 3.0 port of the film pipeline:
 *   1. grade      exposure + white balance → film LUT → tone & color
 *   2. bright     quarter-res highlight extraction, then a separable Gaussian `blur`
 *   3. composite  glow, halation, vignette and grain, with a crop transform
 *
 * Image-space uv has (0,0) at the top-left of the image. Offscreen targets store
 * the top row at memory row 0, so a read-back needs no flip; only the on-screen
 * composite flips y.
 */
internal object FilmShaders {

    /** Single full-screen triangle, no vertex attributes. */
    const val VERTEX = """#version 300 es
uniform bool uFlipY;
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = uFlipY ? vec2(p.x, 1.0 - p.y) : p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    private const val COMMON = """
precision highp float;
precision highp int;

const vec3 kLuma = vec3(0.2126, 0.7152, 0.0722);

float luma(vec3 c) { return dot(c, kLuma); }
"""

    /** Grade pass. `external` selects the camera's OES texture over a plain 2D texture. */
    fun grade(external: Boolean): String = """#version 300 es
${if (external) "#extension GL_OES_EGL_image_external_essl3 : require" else ""}
$COMMON
precision highp sampler3D;

uniform ${if (external) "samplerExternalOES" else "sampler2D"} uSource;
uniform highp sampler3D uLut;
// Maps upright image uv to source texture coordinates (rotation, mirroring, crop).
uniform mat4 uUvTransform;
uniform float uExposure, uContrast, uHighlights, uShadows, uSaturation, uTemperature, uTint, uFade;
uniform float uLutIntensity, uLutSize;
uniform vec3 uLutDomainMin, uLutDomainMax;

in vec2 vUv;
out vec4 fragColor;

vec3 srgbToLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}

vec3 linearToSrgb(vec3 c) {
    c = max(c, 0.0);
    return mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}

void main() {
    vec2 st = (uUvTransform * vec4(vUv, 0.0, 1.0)).xy;
    vec3 c = texture(uSource, st).rgb;

    // Exposure and white balance happen in linear light, like adjusting the scene
    // before it hits the film.
    vec3 lin = srgbToLinear(c) * exp2(uExposure);
    lin *= vec3(1.0 + 0.22 * uTemperature, 1.0 - 0.12 * uTint, 1.0 - 0.22 * uTemperature);
    c = clamp(linearToSrgb(lin), 0.0, 1.0);

    // Film LUT (trilinear sampling of the 3D texture, texel-center corrected).
    if (uLutIntensity > 0.0) {
        vec3 normalized = clamp((c - uLutDomainMin) / max(uLutDomainMax - uLutDomainMin, 1e-5), 0.0, 1.0);
        float n = uLutSize;
        vec3 coord = normalized * ((n - 1.0) / n) + 0.5 / n;
        vec3 filmic = texture(uLut, coord).rgb;
        c = mix(c, filmic, uLutIntensity);
    }

    // Highlights / shadows: luminance-weighted lift or pull.
    float l = luma(c);
    float shadowMask = 1.0 - smoothstep(0.0, 0.55, l);
    float highlightMask = smoothstep(0.45, 1.0, l);
    c += uShadows * 0.28 * shadowMask;
    c += uHighlights * 0.28 * highlightMask;

    // Contrast around mid-grey, softened for negative values.
    float k = uContrast >= 0.0 ? 1.0 + uContrast * 0.9 : 1.0 + uContrast * 0.6;
    c = (c - 0.5) * k + 0.5;

    // Saturation.
    c = mix(vec3(luma(c)), c, 1.0 + uSaturation);

    // Fade: lifted blacks and slightly muted whites, like faded prints.
    c = mix(c, c * 0.86 + 0.1, uFade);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /** Bloom extraction at quarter resolution. */
    const val BRIGHT = """#version 300 es
$COMMON
uniform sampler2D uSource;
uniform vec2 uTexel; // one output texel in uv
in vec2 vUv;
out vec4 fragColor;

void main() {
    // 4 bilinear taps ≈ box filter over the 4×4 source block.
    vec3 c = 0.25 * (texture(uSource, vUv + uTexel * vec2(-0.25, -0.25)).rgb +
                     texture(uSource, vUv + uTexel * vec2( 0.25, -0.25)).rgb +
                     texture(uSource, vUv + uTexel * vec2(-0.25,  0.25)).rgb +
                     texture(uSource, vUv + uTexel * vec2( 0.25,  0.25)).rgb);
    float weight = smoothstep(0.55, 0.95, luma(c));
    fragColor = vec4(c * weight, 1.0);
}
"""

    /** One direction of a separable Gaussian, using bilinear taps to halve the sample count. */
    const val BLUR = """#version 300 es
$COMMON
uniform sampler2D uSource;
uniform vec2 uStep;   // one texel along the blur direction
uniform float uSigma;
uniform int uRadius;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 sum = texture(uSource, vUv).rgb;
    float total = 1.0;
    float k = -0.5 / (uSigma * uSigma);
    for (int i = 1; i <= uRadius; i += 2) {
        float a = float(i);
        float wa = exp(a * a * k);
        float wb = exp((a + 1.0) * (a + 1.0) * k);
        float w = wa + wb;
        vec2 offset = uStep * ((a * wa + (a + 1.0) * wb) / w);
        sum += (texture(uSource, vUv + offset).rgb + texture(uSource, vUv - offset).rgb) * w;
        total += 2.0 * w;
    }
    fragColor = vec4(sum / total, 1.0);
}
"""

    const val COMPOSITE = """#version 300 es
$COMMON
uniform sampler2D uGraded;
uniform sampler2D uBloom;
uniform float uGrain, uGrainScale, uGlow, uHalation, uVignette, uAspect;
uniform uint uSeed;
uniform vec2 uUvScale, uUvOffset;
in vec2 vUv;
out vec4 fragColor;

// PCG3D integer hash (Jarzynski & Olano 2020): no visible lattice patterns,
// unlike fract/sin hashes.
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

// Three roughly Gaussian (unit variance) random values for a grid cell.
// The sum of 3 uniforms has std 0.5, hence the ×2.
vec3 gaussian3(ivec2 cell, uint seed, uint salt) {
    uvec2 uc = uvec2(cell);
    vec3 a = vec3(pcg3d(uvec3(uc, seed ^ salt))) * (1.0 / 4294967295.0);
    vec3 b = vec3(pcg3d(uvec3(uc, seed ^ (salt + 0x9E3779B9u)))) * (1.0 / 4294967295.0);
    vec3 c = vec3(pcg3d(uvec3(uc, seed ^ (salt + 0x7F4A7C15u)))) * (1.0 / 4294967295.0);
    return (a + b + c - 1.5) * 2.0;
}

// Smoothly interpolated Gaussian value noise: soft, clumpy grains rather than hard pixels.
// Returns luminance grain in .x and two chroma offsets in .yz.
vec3 grainNoise(vec2 p, uint seed, uint salt) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 w = f * f * (3.0 - 2.0 * f);
    ivec2 c = ivec2(i);
    vec3 n00 = gaussian3(c, seed, salt);
    vec3 n10 = gaussian3(c + ivec2(1, 0), seed, salt);
    vec3 n01 = gaussian3(c + ivec2(0, 1), seed, salt);
    vec3 n11 = gaussian3(c + ivec2(1, 1), seed, salt);
    // Interpolation shrinks the variance; ×1.6 restores it to roughly unit.
    return mix(mix(n00, n10, w.x), mix(n01, n11, w.x), w.y) * 1.6;
}

void main() {
    vec2 uv = uUvOffset + vUv * uUvScale;

    vec3 c = texture(uGraded, uv).rgb;
    vec3 b = texture(uBloom, uv).rgb;

    // Glow: soft screen-blended bloom.
    c = 1.0 - (1.0 - c) * (1.0 - clamp(b * uGlow * 1.6, 0.0, 1.0));

    // Halation: red-orange light scattered back through the film base.
    c += vec3(1.0, 0.28, 0.1) * luma(b) * uHalation * 1.4;

    // Vignette, measured in image space so preview and photo match.
    vec2 centered = (uv - 0.5) * 2.0;
    float r = length(centered) * 0.7071;
    c *= 1.0 - uVignette * 0.85 * smoothstep(0.35, 1.05, r);

    // Film grain. Two rotated octaves so the pattern never aligns with the
    // pixel grid: fine grains plus larger clumps. Re-seeded every frame.
    if (uGrain > 0.0) {
        uint seed = uSeed * 747796405u + 2891336453u;
        vec2 gp = uv * vec2(uAspect, 1.0) * uGrainScale;
        const mat2 rotA = mat2(vec2(0.8660, 0.5), vec2(-0.5, 0.8660));    // 30°
        const mat2 rotB = mat2(vec2(0.3420, -0.9397), vec2(0.9397, 0.3420)); // -70°
        vec3 fine = grainNoise(rotA * gp, seed, 0x68E31DA4u);
        vec3 clump = grainNoise(rotB * gp * 0.45 + 17.0, seed, 0xB5297A4Du);
        vec3 g = fine * 0.75 + clump * 0.45;

        // Soft-light style response per channel: full strength in the midtones,
        // fading to zero at pure black and white. Grain never clips, so it can't
        // dirty or darken highlights, and the average brightness is unchanged.
        vec3 response = pow(clamp(4.0 * c * (1.0 - c), 0.0, 1.0), vec3(0.75));

        // Colour dye clouds only where there's colour, so B&W films stay neutral.
        float l = luma(c);
        float chroma = clamp(length(c - l) * 4.0, 0.0, 1.0);
        vec3 dye = vec3(g.y, -0.5 * (g.y + g.z), g.z) * 0.25 * chroma;
        c += (g.x + dye) * uGrain * 0.11 * response;
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
