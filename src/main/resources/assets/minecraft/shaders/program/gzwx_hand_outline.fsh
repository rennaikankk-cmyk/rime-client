#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D OrigSampler;
uniform vec2 ScreenSize;
uniform float FillAlpha;
uniform float OutlineAlpha;
uniform int LineWidth;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 orig = texture(OrigSampler, texCoord);
    if (orig.a > 0.0) {
        fragColor = FillAlpha > 0.0 ? vec4(orig.rgb, FillAlpha) : vec4(0.0);
        return;
    }
    vec2 texel = 1.0 / ScreenSize;
    float maxA = 0.0;
    vec3 col = vec3(0.0);
    for (int y = -LineWidth; y <= LineWidth; y++) {
        vec4 s = texture(DiffuseSampler, texCoord + texel * vec2(0.0, float(y)));
        if (s.a > maxA) {
            maxA = s.a;
            col = s.rgb;
        }
    }
    fragColor = maxA > 0.0 ? vec4(col, OutlineAlpha) : vec4(0.0);
}
