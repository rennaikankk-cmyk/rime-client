#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D OrigSampler;
uniform sampler2D GlowSampler;
uniform vec2 ScreenSize;
uniform float FillAlpha;
uniform float OutlineAlpha;
uniform float GlowIntensity;
uniform int LineWidth;
uniform int GlowRadius;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 orig = texture(OrigSampler, texCoord);
    if (orig.a > 0.0) {
        fragColor = FillAlpha > 0.0 ? vec4(orig.rgb, FillAlpha) : vec4(0.0);
        return;
    }
    vec2 texel = 1.0 / ScreenSize;

    if (LineWidth > 0) {
        float maxA = 0.0;
        vec3 col = vec3(0.0);
        for (int y = -LineWidth; y <= LineWidth; y++) {
            vec4 s = texture(DiffuseSampler, texCoord + texel * vec2(0.0, float(y)));
            if (s.a > maxA) {
                maxA = s.a;
                col = s.rgb;
            }
        }
        if (maxA > 0.0) {
            fragColor = vec4(col, OutlineAlpha);
            return;
        }
    }

    if (GlowRadius > 0) {
        float invSpan = 1.0 / float(GlowRadius + 1);
        float acc = 0.0;
        float wSum = 0.0;
        float maxG = 0.0;
        vec3 col = vec3(0.0);
        for (int y = -GlowRadius; y <= GlowRadius; y++) {
            vec4 s = texture(GlowSampler, texCoord + texel * vec2(0.0, float(y)));
            float t = 1.0 - float(y) * invSpan * float(y) * invSpan;
            float w = t * t;
            acc  += w * s.a;
            wSum += w;
            if (s.a > maxG) {
                maxG = s.a;
                col = s.rgb;
            }
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
