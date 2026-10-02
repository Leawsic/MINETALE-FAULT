#version 430
#moj_import <minetale:scene_instances.glsl>
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
    vec3 position=Position, normal=Normal;
    vec4 tangent=SceneTangent;
    minetale_sceneTransform(position, normal, tangent);
    vec3 pos=position+ModelOffset;
    gl_Position=ProjMat*ModelViewMat*vec4(pos,1);
    cameraRelativePosition=pos;
    vertexColor=Color*texture(Sampler2,clamp(UV2/256.0+.5/16.0,vec2(.5/16.0),vec2(15.5/16.0)));
    texCoord0=UV0;
    surfaceNormal=normal;
    surfaceTangent=tangent;
}
