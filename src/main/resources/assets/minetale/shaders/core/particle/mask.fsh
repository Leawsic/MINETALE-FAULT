#version 330

uniform sampler2D Sampler0;
uniform sampler2D SceneDepthSampler;

#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec2 texCoord0;
flat in float particleAlphaCutout;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef PER_FACE_LIGHTING
    color *= gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    color *= vertexColor;
#endif
    if (color.a < particleAlphaCutout) {
        discard;
    }

    ivec2 sceneSize = textureSize(SceneDepthSampler, 0);
    ivec2 scenePixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), sceneSize - ivec2(1));
    float sceneDepth = texelFetch(SceneDepthSampler, scenePixel, 0).r;
    if (gl_FragCoord.z > sceneDepth) {
        discard;
    }
    // Alpha 只决定 Cutout 覆盖；保留像素的 RGB 亮度不再被透明混合二次衰减。
    fragColor = vec4(color.rgb, 1.0);
}
