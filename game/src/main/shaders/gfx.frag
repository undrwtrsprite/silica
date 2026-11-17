#version 450
layout(constant_id = 0) const bool SPEC_ALPHA = true;
layout(constant_id = 1) const int SPEC_ALPHA_OP = -1;
layout(constant_id = 2) const int SPEC_FOG = -1;
layout(constant_id = 3) const int SPEC_TEX = -1;

layout(location = 0) in vec2 vUV;
layout(location = 1) in mediump vec4 vColor;
layout(location = 2) in highp float vFogDist;
layout(location = 3) flat in vec2 vTileBase;

layout(set = 0, binding = 0) uniform Params {
    mat4 mvp;
    mat4 mv;
    vec4 color;
    vec4 normal;
    vec4 fogColor;
    vec4 fogParams;
    vec4 alpha;
    vec4 flags;
    vec4 light0;
    vec4 light0diff;
    vec4 light1;
    vec4 light1diff;
    vec4 ambient;
} u;
layout(set = 1, binding = 0) uniform sampler2D tex;

layout(location = 0) out mediump vec4 outColor;

void main() {
    mediump vec4 c = vColor;
    bool hasTex = SPEC_TEX < 0 ? (u.flags.x > 0.5) : (SPEC_TEX != 0);
    if (hasTex) {
        if (vTileBase.x >= 0.0) {
            highp vec2 texSz = vec2(textureSize(tex, 0));
            highp vec2 dux = dFdx(vUV * (1.0 / 16.0));
            highp vec2 duy = dFdy(vUV * (1.0 / 16.0));
            highp float maxTilePx = max(1.0, max(texSz.x, texSz.y) / 16.0);
            highp float rho = max(length(dux * texSz), length(duy * texSz));
            highp float maxRho = maxTilePx * 0.9;
            highp float gScale = rho > maxRho ? (maxRho / rho) : 1.0;
            highp float pad = min(0.49, (0.5 * clamp(rho, 1.0, maxRho)) / maxTilePx);
            highp vec2 tileUV = vTileBase + clamp(fract(vUV), pad, 1.0 - pad) * (1.0 / 16.0);
            c *= textureGrad(tex, tileUV, dux * gScale, duy * gScale);
        } else {
            c *= texture(tex, vUV);
        }
    }

    if (SPEC_ALPHA && (SPEC_ALPHA_OP >= 0 || u.alpha.z > 0.5)) {
        int op = SPEC_ALPHA_OP >= 0 ? SPEC_ALPHA_OP : int(u.alpha.x + 0.5);
        mediump float a = c.a, r = u.alpha.y;
        bool pass = true;
        if (op == 0) pass = false;
        else if (op == 1) pass = a < r;
        else if (op == 2) pass = a == r;
        else if (op == 3) pass = a <= r;
        else if (op == 4) pass = a > r;
        else if (op == 5) pass = a != r;
        else if (op == 6) pass = a >= r;
        if (!pass) discard;
    }

    int fm = SPEC_FOG < 0 ? int(u.fogParams.w + 0.5) : SPEC_FOG;
    if (fm != 0) {
        mediump float f = 1.0;
        if (fm == 1) f = clamp((u.fogParams.y - vFogDist) / (u.fogParams.y - u.fogParams.x), 0.0, 1.0);
        else if (fm == 2) f = clamp(exp(-u.fogParams.z * vFogDist), 0.0, 1.0);
        else {
            highp float d = u.fogParams.z * vFogDist;
            f = clamp(exp(-(d * d)), 0.0, 1.0);
        }
        mediump vec3 fogCol = u.fogColor.rgb;
        c.rgb = mix(fogCol, c.rgb, f);
    }
    outColor = c;
}
