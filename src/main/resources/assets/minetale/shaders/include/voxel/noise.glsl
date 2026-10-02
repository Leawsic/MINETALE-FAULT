#ifndef MINETALE_NOISE_GLSL
#define MINETALE_NOISE_GLSL

// 默认采用已确认的候选 B；发光依赖始终完整，设为 0 可对照全部频层。
#ifndef NOISE_REDUCED_DETAIL
#define NOISE_REDUCED_DETAIL 1
#endif

// Lookup3：Bob Jenkins 的 public-domain hashword 算法，固定 initval=13。
// float 位模式参与 White Noise；不能改成整数取整或另一种 hash，否则板片与电路布局改变。
uint mtRotate(uint v, uint n) { return (v << n) | (v >> (32u - n)); }
uint mtHashFinish(uvec3 q) {
    q.z = (q.z ^ q.y) - mtRotate(q.y, 14u);
    q.x = (q.x ^ q.z) - mtRotate(q.z, 11u);
    q.y = (q.y ^ q.x) - mtRotate(q.x, 25u);
    q.z = (q.z ^ q.y) - mtRotate(q.y, 16u);
    q.x = (q.x ^ q.z) - mtRotate(q.z, 4u);
    q.y = (q.y ^ q.x) - mtRotate(q.x, 14u);
    return (q.z ^ q.y) - mtRotate(q.y, 24u);
}
uint mtHash(uvec2 p) { return mtHashFinish(uvec3(p, 0u) + uvec3(0xdeadbeefu + 21u)); }
uint mtHash(uvec3 p) { return mtHashFinish(p + uvec3(0xdeadbeefu + 25u)); }
uint mtHash(uvec4 p) {
    uvec3 q = p.xyz + uvec3(0xdeadbeefu + 29u);
    q.x = (q.x - q.z) ^ mtRotate(q.z, 4u); q.z += q.y;
    q.y = (q.y - q.x) ^ mtRotate(q.x, 6u); q.x += q.z;
    q.z = (q.z - q.y) ^ mtRotate(q.y, 8u); q.y += q.x;
    q.x = (q.x - q.z) ^ mtRotate(q.z, 16u); q.z += q.y;
    q.y = (q.y - q.x) ^ mtRotate(q.x, 19u); q.x += q.z;
    q.z = (q.z - q.y) ^ mtRotate(q.y, 4u); q.y += q.x;
    q.x += p.w;
    return mtHashFinish(q);
}
float mtWhite2(vec2 p) { return float(mtHash(floatBitsToUint(p))) / 4294967295.0; }
float mtWhite3(vec3 p) { return float(mtHash(floatBitsToUint(p))) / 4294967295.0; }
float mtWhite4(vec4 p) { return float(mtHash(floatBitsToUint(p))) / 4294967295.0; }
vec3 mtWhite2Color(vec2 p) {
    return vec3(mtWhite2(p), mtWhite3(vec3(p, 1.0)), mtWhite3(vec3(p, 2.0)));
}
vec3 mtWhite3Color(vec3 p) {
    return vec3(mtWhite3(p), mtWhite4(vec4(p, 1.0)), mtWhite4(vec4(p, 2.0)));
}

float mtGradient(uint h, vec2 d) {
    h &= 7u;
    vec2 g = h < 4u ? vec2(d.x, 2.0 * d.y) : vec2(d.y, 2.0 * d.x);
    return ((h & 1u) == 0u ? g.x : -g.x) + ((h & 2u) == 0u ? g.y : -g.y);
}
float mtGradient(uint h, vec3 d) {
    h &= 15u;
    float a = h < 8u ? d.x : d.y;
    float b = h < 4u ? d.y : (h == 12u || h == 14u ? d.x : d.z);
    return ((h & 1u) == 0u ? a : -a) + ((h & 2u) == 0u ? b : -b);
}
float mtPerlin(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 d = fract(p), w = d * d * d * (d * (6.0 * d - 15.0) + 10.0);
    float value = 0.0;
    for (int y = 0; y != 2; ++y) for (int x = 0; x != 2; ++x) {
        ivec2 corner = ivec2(x, y);
        vec2 weight = mix(1.0 - w, w, bvec2(x != 0, y != 0));
        value += mtGradient(mtHash(uvec2(cell + corner)), d - vec2(corner)) * weight.x * weight.y;
    }
    return value * 0.6616;
}
float mtPerlin(vec3 p) {
    ivec3 cell = ivec3(floor(p));
    vec3 d = fract(p), w = d * d * d * (d * (6.0 * d - 15.0) + 10.0);
    float value = 0.0;
    for (int z = 0; z != 2; ++z) for (int y = 0; y != 2; ++y) for (int x = 0; x != 2; ++x) {
        ivec3 corner = ivec3(x, y, z);
        vec3 weight = mix(1.0 - w, w, bvec3(x != 0, y != 0, z != 0));
        value += mtGradient(mtHash(uvec3(cell + corner)), d - vec3(corner)) * weight.x * weight.y * weight.z;
    }
    return value * 0.982;
}

// 当前图只使用整数 Detail=0/1/2、无 Distortion 的归一化 fBM。
float mtFbm(vec2 p, float detail, float roughness, float lacunarity, bool reduced) {
    float total = 0.0, weight = 1.0, weights = 0.0, frequency = 1.0;
    for (int octave = 0; octave <= int(detail); ++octave) {
        if (!reduced || octave < max(1, int(detail)))
        total += mtPerlin(p * frequency) * weight;
        weights += weight;
        frequency *= lacunarity;
        weight *= roughness;
    }
    return 0.5 + 0.5 * total / weights;
}
float mtFbm(vec3 p, float detail, float roughness, float lacunarity, bool reduced) {
    float total = 0.0, weight = 1.0, weights = 0.0, frequency = 1.0;
    for (int octave = 0; octave <= int(detail); ++octave) {
        if (!reduced || octave < max(1, int(detail)))
        total += mtPerlin(p * frequency) * weight;
        weights += weight;
        frequency *= lacunarity;
        weight *= roughness;
    }
    return 0.5 + 0.5 * total / weights;
}
float mtNoise2Exact(vec2 p, float detail, float roughness, float lacunarity) {
    return mtFbm(p, detail, roughness, lacunarity, false);
}
float mtNoise3Exact(vec3 p, float detail, float roughness, float lacunarity) {
    return mtFbm(p, detail, roughness, lacunarity, false);
}
float mtNoise2(vec2 p, float detail, float roughness, float lacunarity) {
    return mtFbm(p, detail, roughness, lacunarity, NOISE_REDUCED_DETAIL != 0);
}
float mtNoise3(vec3 p, float detail, float roughness, float lacunarity) {
    return mtFbm(p, detail, roughness, lacunarity, NOISE_REDUCED_DETAIL != 0);
}
vec3 mtFbmColor(vec3 p, float detail, float roughness, float lacunarity, bool reduced) {
    vec3 offsetA = 100.0 + 100.0 * vec3(mtWhite2(vec2(3.0, 0.0)), mtWhite2(vec2(3.0, 1.0)), mtWhite2(vec2(3.0, 2.0)));
    vec3 offsetB = 100.0 + 100.0 * vec3(mtWhite2(vec2(4.0, 0.0)), mtWhite2(vec2(4.0, 1.0)), mtWhite2(vec2(4.0, 2.0)));
    return vec3(mtFbm(p, detail, roughness, lacunarity, reduced),
                mtFbm(p + offsetA, detail, roughness, lacunarity, reduced),
                mtFbm(p + offsetB, detail, roughness, lacunarity, reduced));
}
vec3 mtNoise3ColorExact(vec3 p, float detail, float roughness, float lacunarity) {
    return mtFbmColor(p, detail, roughness, lacunarity, false);
}
vec3 mtNoise3Color(vec3 p, float detail, float roughness, float lacunarity) {
    return mtFbmColor(p, detail, roughness, lacunarity, NOISE_REDUCED_DETAIL != 0);
}

// Blender 当前 Voronoi 使用有符号 PCG3D；右移的符号扩展属于随机场定义。
vec3 mtCellRandom(ivec3 cell) {
    uvec3 p = uvec3(cell) * 1664525u + 1013904223u;
    p.x += p.y * p.z; p.y += p.z * p.x; p.z += p.x * p.y;
    p ^= uvec3(ivec3(p) >> 16);
    p.x += p.y * p.z; p.y += p.z * p.x; p.z += p.x * p.y;
    return vec3(p & 0x7fffffffu) / 2147483647.0;
}
vec3 mtVoronoiPosition(vec3 p, float scale, float randomness) {
    p *= scale;
    ivec3 cell = ivec3(floor(p));
    vec3 local = fract(p), nearest = vec3(0.0);
    float closest = 3.402823466e38;
    for (int z = -1; z <= 1; ++z) for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
        ivec3 neighbor = ivec3(x, y, z);
        vec3 point = vec3(neighbor) + mtCellRandom(cell + neighbor) * randomness;
        vec3 delta = abs(point - local);
        float distance = max(max(delta.x, delta.y), delta.z);
        if (distance < closest) { closest = distance; nearest = point; }
    }
    return scale == 0.0 ? vec3(0.0) : (nearest + vec3(cell)) / scale;
}

#endif
