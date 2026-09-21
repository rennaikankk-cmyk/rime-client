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
    float norm = 1.0 / float(2 * radius + 1);
    vec3 cAcc = vec3(0.0);
    float wAcc = 0.0;
    for (int x = -radius; x <= radius; x++) {
        vec4 s = texture(DiffuseSampler, texCoord + texel * vec2(float(x), 0.0));
        cAcc += s.a * s.rgb;
        wAcc += s.a;
    }
    fragColor = vec4(cAcc * norm, wAcc * norm);
}
