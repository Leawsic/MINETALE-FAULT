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

const float PI = 3.14159265359;
const float TAU = 6.28318530718;

float hash11(float value) {
    value = fract(value * 0.1031);
    value *= value + 33.33;
    value *= value + value;
    return fract(value);
}

vec2 quadCorner(int index) {
    if (index == 0 || index == 3) {
        return vec2(-1.0, -1.0);
    }
    if (index == 1) {
        return vec2(1.0, -1.0);
    }
    if (index == 2 || index == 4) {
        return vec2(1.0, 1.0);
    }
    return vec2(-1.0, 1.0);
}

vec2 quadUv(int index) {
    return quadCorner(index) * vec2(0.5, -0.5) + 0.5;
}

float lifeFade(float age) {
    return smoothstep(0.0, 0.065, age)
            * (1.0 - smoothstep(0.78, 1.0, age));
}

void main() {
    int particleIndex = gl_VertexID / PARTICLE_VERTICES_PER_PARTICLE;
    int cornerIndex = gl_VertexID
            - particleIndex * PARTICLE_VERTICES_PER_PARTICLE;
    float id = float(particleIndex);
    float seed = hash11(id + 0.37);
    float shapeSeed = hash11(id + 17.71);
    float motionSeed = hash11(id + 73.19);
    float family = hash11(id + 191.53);
    float densitySeed = hash11(id + 431.89);
    float verticalSeed = hash11(id + 613.27);

    vec3 anchor = ParticleFrameData[0].xyz;
    float time = ParticleFrameData[0].w;
    vec3 normal = ParticleFrameData[1].xyz;
    float radius = ParticleFrameData[1].w;
    vec3 radialRight = ParticleFrameData[2].xyz;
    float effectHeight = ParticleFrameData[2].w;
    vec3 radialForward = ParticleFrameData[3].xyz;
    float intensity = clamp(ParticleFrameData[3].w, 0.0, 1.0);
    vec3 billboardRight = ParticleFrameData[4].xyz;
    float visibility = clamp(ParticleFrameData[4].w, 0.0, 1.0);
    vec3 billboardUp = ParticleFrameData[5].xyz;
    float particleSize = ParticleFrameData[5].w;

    float halo = max(1.25, radius * 0.16);
    float direction = motionSeed < 0.5 ? -1.0 : 1.0;
    float angle;
    float radial;
    float height;
    float heightNormalized;
    float fade;
    float sizeScale = 1.0;
    float colorSeed = shapeSeed;

    if (family < 0.14) {
        float flow = fract(verticalSeed + time * mix(0.006, 0.012, motionSeed));
        heightNormalized = pow(flow, 1.55);
        float taper = pow(1.0 - heightNormalized, 0.72);
        float ribbon = floor(hash11(id + 239.0) * 5.0);
        float crossSection = shapeSeed - 0.5;
        float ribbonWidth = halo * mix(0.06, 0.58, taper);
        angle = TAU * (
                ribbon / 5.0
                + heightNormalized * mix(2.4, 3.4, motionSeed)
        ) + time * direction * 0.12
                + crossSection * mix(0.06, 0.32, taper);
        radial = radius * mix(0.22, 1.02, taper)
                + crossSection * ribbonWidth
                + sin(heightNormalized * TAU * 6.0 + seed * TAU - time * 0.18)
                * ribbonWidth * mix(0.05, 0.18, intensity);
        height = heightNormalized * effectHeight
                + sin(angle * 2.0 + shapeSeed * TAU) * 0.10 * taper;
        fade = smoothstep(0.0, 0.025, flow)
                * (1.0 - smoothstep(0.94, 1.0, flow));
        sizeScale = mix(0.42, 1.48, taper);
        colorSeed = mix(0.28, 0.82, heightNormalized);
    } else if (family < 0.54) {
        float climb = fract(verticalSeed + time * mix(0.008, 0.018, motionSeed));
        heightNormalized = pow(climb, 2.35);
        float bottomComplexity = pow(1.0 - heightNormalized, 1.45);
        float lane = floor(hash11(id + 271.0) * 11.0);
        angle = TAU * (lane / 11.0 + seed * 0.075)
                + heightNormalized * TAU * (2.0 + bottomComplexity * 4.0)
                + time * direction * 0.20 * mix(0.82, 1.18, motionSeed);
        float wave = sin(angle * 3.0 - time * 0.42 + shapeSeed * TAU);
        radial = radius * mix(0.38, 1.0, bottomComplexity)
                + (shapeSeed - 0.5) * halo * mix(0.28, 0.92, bottomComplexity)
                + wave * halo * bottomComplexity * mix(0.05, 0.18, intensity);
        height = heightNormalized * effectHeight
                + sin(angle * 2.0 + seed * TAU) * 0.12 * bottomComplexity;
        fade = smoothstep(0.0, 0.035, climb)
                * (1.0 - smoothstep(0.90, 1.0, climb));
        sizeScale = mix(0.72, 1.20, bottomComplexity);
    } else if (family < 0.80) {
        float age = fract(seed + time * mix(0.035, 0.075, motionSeed));
        float travel = pow(age, mix(0.60, 0.82, shapeSeed));
        float arm = floor(hash11(id + 313.0) * 7.0);
        angle = TAU * (arm / 7.0 + shapeSeed * 0.05)
                + direction * travel * TAU * mix(2.8, 4.2, shapeSeed)
                - time * 0.13;
        radial = radius * mix(0.08, 1.015, travel)
                + sin(angle * 2.0 + time * 0.31)
                * halo * mix(0.03, 0.09, intensity);
        height = effectHeight * (0.012 + pow(verticalSeed, 2.4) * 0.13)
                + sin(age * PI) * mix(0.10, 0.46, intensity)
                + sin(angle * 4.0) * 0.055;
        heightNormalized = height / effectHeight;
        fade = lifeFade(age);
        sizeScale = mix(0.82, 1.18, travel);
    } else if (family < 0.94) {
        float age = fract(seed + time * mix(0.022, 0.052, motionSeed));
        heightNormalized = pow(age, 1.72);
        angle = seed * TAU
                + heightNormalized * TAU * mix(1.4, 2.6, shapeSeed)
                + time * direction * 0.11;
        radial = radius * mix(0.34, 1.0, pow(1.0 - heightNormalized, 0.72))
                + sin(age * TAU * 3.0 + shapeSeed * TAU) * halo * 0.18;
        height = heightNormalized * effectHeight;
        fade = lifeFade(age);
        sizeScale = mix(0.72, 1.08, 1.0 - heightNormalized);
        colorSeed = 0.52 + shapeSeed * 0.48;
    } else {
        float age = fract(seed + time * mix(0.045, 0.095, motionSeed));
        float segment = floor(age * 9.0);
        float angularJitter = hash11(id + segment * 37.17) - 0.5;
        float radialJitter = hash11(id + segment * 61.43) - 0.5;
        heightNormalized = pow(age, 1.38) * mix(0.42, 1.0, verticalSeed);
        angle = seed * TAU
                + heightNormalized * TAU * 1.6
                + time * direction * 0.14
                + angularJitter * mix(0.10, 0.40, intensity);
        radial = radius * mix(0.46, 1.0, 1.0 - heightNormalized)
                + radialJitter * halo * 1.45;
        height = heightNormalized * effectHeight
                + abs(angularJitter) * intensity * 0.28;
        fade = lifeFade(age);
        sizeScale = mix(0.62, 0.92, shapeSeed);
        colorSeed = 1.0;
    }

    float verticalPadding = particleSize * 4.0;
    height = clamp(height, verticalPadding, effectHeight - verticalPadding);
    heightNormalized = height / effectHeight;
    float bottomComplexity = pow(1.0 - heightNormalized, 1.45);
    float heightDensity = mix(0.24, 0.92, bottomComplexity);
    if (family < 0.14) {
        heightDensity = max(heightDensity, mix(0.38, 0.96, bottomComplexity));
    }
    float densityThreshold = clamp(heightDensity, 0.0, 0.98);
    float presence = 1.0 - smoothstep(
            densityThreshold - 0.045,
            densityThreshold + 0.045,
            densitySeed
    );
    vec3 center = anchor
            + radialRight * (cos(angle) * radial)
            + radialForward * (sin(angle) * radial)
            + normal * height;

    vec2 corner = quadCorner(cornerIndex);
    float spin = angle * 0.28 + shapeSeed * TAU + time * direction * 0.12;
    corner = mat2(cos(spin), -sin(spin), sin(spin), cos(spin)) * corner;
    float size = particleSize
            * mix(0.68, 1.68, hash11(id + 587.0))
            * sizeScale
            * mix(0.62, 1.0, fade);
    vec3 position = center
            + billboardRight * (corner.x * size)
            + billboardUp * (corner.y * size);

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    vec3 rose = vec3(1.0, 0.025, 0.26);
    vec3 violet = vec3(0.62, 0.08, 1.0);
    vec3 pearl = vec3(1.0, 0.62, 0.86);
    vec3 electric = vec3(0.24, 0.78, 1.0);
    vec3 color = mix(rose, violet, colorSeed);
    color = mix(color, pearl, bottomComplexity * (0.16 + 0.22 * shapeSeed));
    if (family >= 0.94) {
        color = mix(color, electric, 0.72);
    }
    color = clamp(color * 1.50, vec3(0.0), vec3(1.0));
    vec4 particleColor = vec4(color, fade * presence * visibility);
#ifdef PER_FACE_LIGHTING
    vertexPerFaceColorBack = particleColor;
    vertexPerFaceColorFront = particleColor;
#else
    vertexColor = particleColor;
#endif
    texCoord0 = quadUv(cornerIndex);
    particleAlphaCutout = ParticleFrameData[6].x;
}
