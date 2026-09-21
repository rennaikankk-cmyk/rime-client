#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 ScreenSize;
uniform int LineWidth;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / ScreenSize;
    float maxA = 0.0;
    vec3 col = vec3(0.0);
    for (int x = -LineWidth; x <= LineWidth; x++) {
        vec4 s = texture(DiffuseSampler, texCoord + texel * vec2(float(x), 0.0));
        if (s.a > maxA) {
            maxA = s.a;
            col = s.rgb;
        }
    }
    fragColor = vec4(col, maxA);
}
