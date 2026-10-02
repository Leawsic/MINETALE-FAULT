#version 330

uniform sampler2D Geometry;
uniform sampler2D Bones;
layout(std140) uniform ModelBake { ivec4 Counts; };
out vec4 fragColor;

uint bits(sampler2D source, ivec2 pixel) {
    uvec4 b = uvec4(round(texelFetch(source, pixel, 0) * 255.0));
    return b.r | b.g << 8u | b.b << 16u | b.a << 24u;
}
float geometry(int vertex, int component) {
    int index = vertex * 12 + component;
    return uintBitsToFloat(bits(Geometry, ivec2(index % 1008, index / 1008)));
}
float bone(int row, int col) { return uintBitsToFloat(bits(Bones, ivec2(col, row))); }
vec4 bone4(int row, int col) { return vec4(bone(row, col), bone(row, col+1), bone(row, col+2), bone(row, col+3)); }
vec3 bone3(int row, int col) { return vec3(bone(row, col), bone(row, col+1), bone(row, col+2)); }
vec3 geometry3(int vertex, int col) { return vec3(geometry(vertex,col), geometry(vertex,col+1), geometry(vertex,col+2)); }
vec4 packWord(uint value) { return vec4(uvec4(value, value >> 8u, value >> 16u, value >> 24u) & 255u) / 255.0; }

void main() {
    int word = int(gl_FragCoord.y) * 1008 + int(gl_FragCoord.x);
    int vertex = word / 9;
    int attr = word % 9;
    bool eyes = vertex >= Counts.x * Counts.z;
    int local = eyes ? vertex - Counts.x * Counts.z : vertex;
    int perInstance = eyes ? Counts.y : Counts.x;
    int instance = local / perInstance;
    int modelVertex = local % perInstance + (eyes ? Counts.x : 0);
    if (instance >= Counts.z) { fragColor = vec4(0); return; }
    int row = instance * Counts.w + int(geometry(modelVertex, 8));
    uint value;
    if (attr < 3) {
        mat4 pose = mat4(bone4(row,0), bone4(row,4), bone4(row,8), bone4(row,12));
        vec4 position = pose * vec4(geometry3(modelVertex, 0), 1);
        value = floatBitsToUint(position[attr]);
    } else if (attr == 3) {
        value = bits(Bones, ivec2(25,row));
    } else if (attr < 6) {
        value = floatBitsToUint(geometry(modelVertex, attr + 2));
    } else if (attr == 6) {
        value = bits(Bones, ivec2(27,row));
    } else if (attr == 7) {
        value = bits(Bones, ivec2(26,row));
    } else {
        mat3 pose = mat3(bone3(row,16), bone3(row,19), bone3(row,22));
        vec3 normal = pose * geometry3(modelVertex,3);
        // GeckoLib 对零厚度立方体在最终变换后校正法线，不能提前烘焙这个判断。
        normal = mix(normal, abs(normal), geometry3(modelVertex,9));
        // 与 BufferBuilder.normalIntValue 一致向零截断
        uvec3 b = uvec3(ivec3(clamp(normal, -1, 1) * 127.0)) & 255u;
        value = b.x | b.y << 8u | b.z << 16u;
    }
    fragColor = packWord(value);
}
