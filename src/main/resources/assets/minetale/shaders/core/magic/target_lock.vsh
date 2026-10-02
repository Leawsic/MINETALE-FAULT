#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minetale:particle/uniform.glsl>

#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec2 texCoord0;
flat out float particleAlphaCutout;

void main() {
    int id = gl_VertexID / PARTICLE_VERTICES_PER_PARTICLE;
    int corner = gl_VertexID % PARTICLE_VERTICES_PER_PARTICLE;
    vec2 corners[6] = vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
    vec2 quad = corners[corner];
    float ring = float(id / 128);
    float angle = float(id % 128) * 6.2831853 / 128.0 + ParticleFrameData[0].w * mix(1.2,-1.2,ring);
    float pulse = 0.85 + 0.15 * sin(ParticleFrameData[0].w * 3.0);
    vec3 position = ParticleFrameData[0].xyz
        + (ParticleFrameData[2].xyz * cos(angle) + ParticleFrameData[3].xyz * sin(angle)) * ParticleFrameData[1].w * pulse
        + ParticleFrameData[1].xyz * (ring - 0.5) * ParticleFrameData[2].w * 0.8;
    position += (ParticleFrameData[4].xyz * quad.x + ParticleFrameData[5].xyz * quad.y) * ParticleFrameData[5].w;
    gl_Position = ProjMat * ModelViewMat * vec4(position,1.0);
    vec4 color = vec4(0.65,0.88,1.0,0.9);
#ifdef PER_FACE_LIGHTING
    vertexPerFaceColorBack = color;
    vertexPerFaceColorFront = color;
#else
    vertexColor = color;
#endif
    texCoord0 = quad * vec2(0.5,-0.5) + 0.5;
    particleAlphaCutout = ParticleFrameData[6].x;
}
