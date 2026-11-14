#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 2) in float vFogDist;

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

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = vColor;
    if (u.flags.x > 0.5) c *= texture(tex, vUV);

    if (u.alpha.z > 0.5) {
        int op = int(u.alpha.x + 0.5);
        float a = c.a, r = u.alpha.y;
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

    int fm = int(u.fogParams.w + 0.5);
    if (fm != 0) {
        float f = 1.0;
        if (fm == 1) f = (u.fogParams.y - vFogDist) / (u.fogParams.y - u.fogParams.x);
        else if (fm == 2) f = exp(-u.fogParams.z * vFogDist);
        else f = exp(-pow(u.fogParams.z * vFogDist, 2.0));
        c.rgb = mix(u.fogColor.rgb, c.rgb, clamp(f, 0.0, 1.0));
    }
    outColor = c;
}
