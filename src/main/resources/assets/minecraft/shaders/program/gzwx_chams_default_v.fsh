#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D OrigSampler;
uniform sampler2D ColorSampler;
uniform vec2 ScreenSize;
uniform float GlowIntensity;
uniform float FillTint;
uniform float FillAlpha;
uniform int GlowThickness;
uniform int LineWidth;

in vec2 texCoord;
out vec4 fragColor;

vec3 propagatedColor(vec2 texel) {
    int radius = max(LineWidth, GlowThickness);
    vec3 cAcc = vec3(0.0);
    float wAcc = 0.0;
    for (int y = -radius; y <= radius; y++) {
        vec4 s = texture(ColorSampler, texCoord + texel * vec2(0.0, float(y)));
        cAcc += s.rgb;
        wAcc += s.a;
    }
    return wAcc > 0.0001 ? cAcc / wAcc : vec3(1.0);
}

void main() {
    vec2 texel = 1.0 / ScreenSize;
    vec4 center = texture(OrigSampler, texCoord);

    if (center.a > 0.0) {
        fragColor = vec4(center.rgb * FillTint, center.a * FillAlpha);
        return;
    }

    vec3 col = propagatedColor(texel);

    if (LineWidth > 0) {
        float maxA = 0.0;
        for (int y = -LineWidth; y <= LineWidth; y++) {
            maxA = max(maxA, texture(DiffuseSampler, texCoord + texel * vec2(0.0, float(y))).r);
        }
        if (maxA > 0.0) {
            fragColor = vec4(col, maxA);
            return;
        }
    }

    if (GlowThickness > 0) {
        float invSpan = 1.0 / float(GlowThickness + 1);
        float acc = 0.0;
        float wSum = 0.0;
        for (int y = -GlowThickness; y <= GlowThickness; y++) {
            float t = 1.0 - float(y) * invSpan * float(y) * invSpan;
            float w = t * t;
            acc  += w * texture(DiffuseSampler, texCoord + texel * vec2(0.0, float(y))).g;
            wSum += w;
        }
        float coverage = acc / wSum;
        float glow = clamp(pow(GlowIntensity * coverage, 0.72) * 1.35, 0.0, 1.0);
        if (glow > 0.0) {
            fragColor = vec4(col, glow);
            return;
        }
    }

    fragColor = vec4(0.0);
}
