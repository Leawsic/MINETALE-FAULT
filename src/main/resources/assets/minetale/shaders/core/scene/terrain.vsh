#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;
in vec4 SceneTangent;
uniform sampler2D Sampler2;
out vec3 cameraRelativePosition;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 surfaceNormal;
out vec4 surfaceTangent;

void main() {
    vec3 pos=Position+ModelOffset;
    gl_Position=ProjMat*ModelViewMat*vec4(pos,1);
    // 大三角形上插值标量雾距离会失真；改为传位置、片元级重算。
    cameraRelativePosition=pos;
    vertexColor=Color*texture(Sampler2,clamp(UV2/256.0+.5/16.0,vec2(.5/16.0),vec2(15.5/16.0)));
    texCoord0=UV0;
    surfaceNormal=Normal;
    surfaceTangent=SceneTangent;
}
