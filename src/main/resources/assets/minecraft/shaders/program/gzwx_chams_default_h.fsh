#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 ScreenSize;
uniform int LineWidth;
uniform int GlowThickness;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / ScreenSize;
    int radius = max(LineWidth, GlowThickness);
    float invSpan = 1.0 / float(GlowThickness + 1);
    float maxA = 0.0;
    float gAcc = 0.0;
    float gW   = 0.0;
    for (int x = -radius; x <= radius; x++) {
        float a = texture(DiffuseSampler, texCoord + texel * vec2(float(x), 0.0)).a;
        if (abs(x) <= LineWidth) maxA = max(maxA, a);
        if (abs(x) <= GlowThickness) {
            float t = 1.0 - float(x) * invSpan * float(x) * invSpan;
            float w = t * t;
            gAcc += w * a;
            gW   += w;
        }
    }
    fragColor = vec4(maxA, gAcc / gW, 0.0, 1.0);
}
