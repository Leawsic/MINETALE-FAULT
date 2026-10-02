#version 330

layout(std140) uniform MagicBeam {
    mat4 ViewProjection;
    mat4 InverseViewProjection;
    vec4 OriginRadius;
    vec4 AxisLength;
    vec4 Viewport;
    vec4 Muzzle;
    vec4 Occlusion;
    vec4 Distortion;
};
out vec3 localPosition;
out vec3 surfaceNormal;
flat out int surfaceKind;

// 固定拓扑由顶点编号生成：32 边圆柱及端盖，然后是 16×8 炮口球。
// Java 的 BEAM_VERTICES 必须与此匹配
const float TAU = 6.28318530718;
const ivec2 CORNERS[6] = ivec2[6](ivec2(0,0), ivec2(1,0), ivec2(1,1),
        ivec2(0,0), ivec2(1,1), ivec2(0,1));

void main() {
    vec3 axis = AxisLength.xyz;
    vec3 right = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0,1,0) : vec3(1,0,0)));
    vec3 up = cross(axis, right);
    float radius = OriginRadius.w;
    float muzzleRadius = Muzzle.x;
    int id = gl_VertexID;
    surfaceKind = 0;
    if (id < 32 * 6) {
        ivec2 corner = CORNERS[id % 6];
        float angle = float(id / 6 + corner.x) * TAU / 32.0;
        surfaceNormal = right * cos(angle) + up * sin(angle);
        localPosition = surfaceNormal * radius + axis * (float(corner.y) * AxisLength.w);
    } else if (id < 32 * 12) {
        int capVertex = id - 32 * 6;
        int cap = capVertex / (32 * 3);
        int vertex = capVertex % 3;
        float angle = float((capVertex % (32 * 3)) / 3 + max(0, vertex - 1)) * TAU / 32.0;
        vec3 radial = (right * cos(angle) + up * sin(angle)) * (vertex == 0 ? 0.0 : radius);
        localPosition = radial + axis * (float(cap) * AxisLength.w);
        surfaceNormal = axis * (cap == 0 ? -1.0 : 1.0);
        surfaceKind = 1;
    } else {
        int sphereVertex = id - 32 * 12;
        ivec2 corner = CORNERS[sphereVertex % 6];
        int quad = sphereVertex / 6;
        float longitude = float(quad % 16 + corner.x) * TAU / 16.0;
        float latitude = float(quad / 16 + corner.y) * TAU / 16.0;
        surfaceNormal = (right * cos(longitude) + up * sin(longitude)) * sin(latitude) + axis * cos(latitude);
        localPosition = surfaceNormal * muzzleRadius + axis * Muzzle.y;
        surfaceKind = 2;
    }
    gl_Position = ViewProjection * vec4(localPosition + OriginRadius.xyz, 1.0);
}
