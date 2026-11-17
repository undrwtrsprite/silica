#version 450
layout(constant_id = 2) const int SPEC_FOG = -1;
layout(constant_id = 4) const int SPEC_LIGHT = -1;
layout(constant_id = 5) const int SPEC_RADIAL = -1;
// Fixed-function emulation vertex stage. One packed vertex format (32 bytes): pos, uv, rgba8, normal8.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;    // R8G8B8A8_UNORM
layout(location = 3) in vec4 inNormal;   // R8G8B8A8_SNORM

layout(set = 0, binding = 0) uniform Params {
    mat4 mvp;
    mat4 mv;
    vec4 color;       // current glColor (used when no color array)
    vec4 normal;      // current glNormal (used when no normal array)
    vec4 fogColor;
    vec4 fogParams;   // start, end, density, mode (0 off, 1 linear, 2 exp, 3 exp2)
    vec4 alpha;       // compare op (VkCompareOp), ref, enabled, fog distance mode (0 planar |z|, 1 radial; NV_fog_distance)
    vec4 flags;       // hasTexture, hasColorArray, lighting, hasNormalArray
    vec4 light0;      // eye-space direction
    vec4 light0diff;
    vec4 light1;
    vec4 light1diff;
    vec4 ambient;     // glLightModel ambient
} u;

layout(location = 0) out vec2 vUV;
layout(location = 1) out mediump vec4 vColor;
layout(location = 2) out highp float vFogDist;
layout(location = 3) flat out vec2 vTileBase;

void main() {
    vec4 pos4 = vec4(inPos, 1.0);
    vec4 p = u.mvp * pos4;
    // Reversed depth (1 at the near plane, 0 far away): float depth precision then stays even over the whole range. A perspective matrix
    // arrives with z already = near (infinite-far form, built on the CPU); orthographic ones (the GUI) get GL's [-w, w] flipped to [w, 0].
    // Y is flipped by the negative-height viewport.
    bool persp = u.normal.w > 0.5;   // set by the renderer from the projection matrix (inferring it from the combined matrix breaks for scaled models such as the hand)
    gl_Position = vec4(p.xy, persp ? p.z : (p.w - p.z) * 0.5, p.w);
    vUV = inUV;
    if (u.ambient.a > 0.5) {
        vec2 tileIdx = clamp(floor(inNormal.xy * (127.0 / 8.0) + 0.5), 0.0, 15.0);
        vTileBase = tileIdx * (1.0 / 16.0);
    } else {
        vTileBase = vec2(-1.0);
    }

    mediump vec4 c = u.flags.y > 0.5 ? inColor : u.color;
    bool doLight = SPEC_LIGHT < 0 ? (u.flags.z > 0.5) : (SPEC_LIGHT != 0);
    if (doLight) {
        vec3 n = u.flags.w > 0.5 ? inNormal.xyz : u.normal.xyz;
        n = normalize(mat3(u.mv) * n);
        mediump vec3 lit = u.ambient.rgb
                 + max(dot(n, u.light0.xyz), 0.0) * u.light0diff.rgb
                 + max(dot(n, u.light1.xyz), 0.0) * u.light1diff.rgb;
        c.rgb *= clamp(lit, 0.0, 1.0);   // GL_COLOR_MATERIAL ambient+diffuse
    }
    vColor = c;
    int fm = SPEC_FOG < 0 ? int(u.fogParams.w + 0.5) : SPEC_FOG;
    if (fm != 0) {
        vec3 eye = (u.mv * pos4).xyz;
        bool radial = SPEC_RADIAL < 0 ? (u.alpha.w > 0.5) : (SPEC_RADIAL != 0);
        vFogDist = radial ? length(eye) : abs(eye.z);
    } else {
        vFogDist = 0.0;
    }
}
