#ifndef MINETALE_ENVIRONMENT_LIGHTING_GLSL
#define MINETALE_ENVIRONMENT_LIGHTING_GLSL

// Rendered OBJ 与 BattleFrame 共用的稀疏环境 Field、代理箱投影和风格化染色。
uniform sampler2D EnvironmentFieldSampler;

layout(std140) uniform EnvironmentFieldUniform {
    vec4 EnvironmentCaptureBasisRight;
    vec4 EnvironmentCaptureBasisUp;
    vec4 EnvironmentCaptureBasisForward;
    vec4 EnvironmentFieldControl;
};

layout(std140) uniform EnvironmentLightUniform {
    vec4 EnvironmentLightControl0;
    vec4 EnvironmentLightControl1;
    vec4 EnvironmentProxyCenter;
    vec4 EnvironmentProxyHalfExtent;
};

const vec3 MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
const float MINETALE_ENVIRONMENT_PROXY_EPSILON = 1.0e-5;

bool minetaleEnvironmentFinite(float value) {
    return !isnan(value) && !isinf(value);
}

bool minetaleEnvironmentFinite(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

vec3 minetaleEnvironmentLookupDirection(vec3 worldPosition, vec3 worldNormal) {
    float normalLengthSquared = dot(worldNormal, worldNormal);
    if (!minetaleEnvironmentFinite(worldPosition)
            || !minetaleEnvironmentFinite(worldNormal)
            || !minetaleEnvironmentFinite(normalLengthSquared)
            || normalLengthSquared <= MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        return vec3(0.0, 1.0, 0.0);
    }

    vec3 normal = worldNormal * inversesqrt(normalLengthSquared);
    vec3 center = EnvironmentProxyCenter.xyz;
    vec3 halfExtent = EnvironmentProxyHalfExtent.xyz;
    vec3 minimum = center - halfExtent;
    vec3 maximum = center + halfExtent;
    bool proxyValid = minetaleEnvironmentFinite(center)
            && minetaleEnvironmentFinite(halfExtent)
            && all(greaterThan(halfExtent, vec3(MINETALE_ENVIRONMENT_PROXY_EPSILON)));
    bool pointInside = all(greaterThanEqual(
            worldPosition,
            minimum - vec3(MINETALE_ENVIRONMENT_PROXY_EPSILON)
    )) && all(lessThanEqual(
            worldPosition,
            maximum + vec3(MINETALE_ENVIRONMENT_PROXY_EPSILON)
    ));
    if (!proxyValid || !pointInside) {
        return normal;
    }

    // 零方向分量按正无穷处理，避免 sign(0) 使平行 slab 错误得到 t=0。
    vec3 directionSign = vec3(
            normal.x >= 0.0 ? 1.0 : -1.0,
            normal.y >= 0.0 ? 1.0 : -1.0,
            normal.z >= 0.0 ? 1.0 : -1.0
    );
    vec3 inverseDirection = directionSign
            / max(abs(normal), vec3(MINETALE_ENVIRONMENT_PROXY_EPSILON));
    vec3 t0 = (minimum - worldPosition) * inverseDirection;
    vec3 t1 = (maximum - worldPosition) * inverseDirection;
    float tExit = min(
            max(t0.x, t1.x),
            min(max(t0.y, t1.y), max(t0.z, t1.z))
    );
    if (!minetaleEnvironmentFinite(tExit)) {
        return normal;
    }

    vec3 hit = worldPosition + normal * max(tExit, 0.0);
    vec3 projected = hit - center;
    float projectedLengthSquared = dot(projected, projected);
    if (!minetaleEnvironmentFinite(projected)
            || !minetaleEnvironmentFinite(projectedLengthSquared)
            || projectedLengthSquared <= MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        return normal;
    }

    vec3 projectedDirection = projected * inversesqrt(projectedLengthSquared);
    float spatialVariation = clamp(EnvironmentLightControl1.y, 0.0, 1.0);
    vec3 mixedDirection = mix(normal, projectedDirection, spatialVariation);
    float mixedLengthSquared = dot(mixedDirection, mixedDirection);
    if (!minetaleEnvironmentFinite(mixedDirection)
            || !minetaleEnvironmentFinite(mixedLengthSquared)
            || mixedLengthSquared <= MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        return normal;
    }
    return mixedDirection * inversesqrt(mixedLengthSquared);
}

vec3 minetaleEnvironmentLocalDirection(vec3 worldDirection) {
    vec3 localDirection = vec3(
            dot(worldDirection, EnvironmentCaptureBasisRight.xyz),
            dot(worldDirection, EnvironmentCaptureBasisUp.xyz),
            dot(worldDirection, EnvironmentCaptureBasisForward.xyz)
    );
    float lengthSquared = dot(localDirection, localDirection);
    if (!minetaleEnvironmentFinite(localDirection)
            || !minetaleEnvironmentFinite(lengthSquared)
            || lengthSquared <= MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        return vec3(0.0, 1.0, 0.0);
    }
    return localDirection * inversesqrt(lengthSquared);
}

vec3 minetaleEvaluateEnvironmentLight(vec3 worldPosition, vec3 worldNormal) {
    if (EnvironmentLightControl0.x < 0.5 || EnvironmentFieldControl.x < 0.5) {
        return vec3(0.0);
    }

    vec3 lookupDirection = minetaleEnvironmentLookupDirection(worldPosition, worldNormal);
    vec3 localLookupDirection = minetaleEnvironmentLocalDirection(lookupDirection);
    float widthScale = max(EnvironmentLightControl0.z, MINETALE_ENVIRONMENT_PROXY_EPSILON);
    float widthScaleSquared = widthScale * widthScale;
    vec3 environment = texelFetch(
            EnvironmentFieldSampler,
            ivec2(MINETALE_ENVIRONMENT_GLOBAL_TEXEL, 0),
            0
    ).rgb
            * EnvironmentLightControl0.w;

    for (int index = 0; index < MINETALE_ENVIRONMENT_LOBE_COUNT; ++index) {
        vec4 lobe = texelFetch(
                EnvironmentFieldSampler,
                ivec2(MINETALE_ENVIRONMENT_LOBE_COLOR_BASE_TEXEL + index, 0),
                0
        );
        vec3 lobeDirection = minetaleDecodeEnvironmentDirection(texelFetch(
                EnvironmentFieldSampler,
                ivec2(MINETALE_ENVIRONMENT_LOBE_DIRECTION_BASE_TEXEL + index, 0),
                0
        ).rgb);
        float lambda = mix(
                EnvironmentFieldControl.y,
                EnvironmentFieldControl.z,
                clamp(lobe.a, 0.0, 1.0)
        ) / widthScaleSquared;
        float weight = exp2(
                lambda
                * 1.4426950408889634
                * (
                    clamp(
                            dot(localLookupDirection, lobeDirection),
                            -1.0,
                            1.0
                    )
                    - 1.0
                )
        );
        environment += lobe.rgb * weight;
    }

    float environmentLuma = max(
            dot(environment, MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS),
            0.0
    );
    return max(mix(
            vec3(environmentLuma),
            environment,
            clamp(EnvironmentLightControl1.x, 0.0, 1.0)
    ), vec3(0.0));
}

bool minetaleResolveEnvironmentTint(
        vec3 worldPosition,
        vec3 worldNormal,
        out vec3 tint,
        out float response
) {
    vec3 environment = minetaleEvaluateEnvironmentLight(worldPosition, worldNormal);
    float environmentLuma = dot(environment, MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS);
    if (!minetaleEnvironmentFinite(environment)
            || !minetaleEnvironmentFinite(environmentLuma)
            || environmentLuma <= MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        tint = vec3(1.0);
        response = 0.0;
        return false;
    }

    tint = environment / environmentLuma;
    // 窄色谱增益先截断再归一化，保持单位亮度且避免通道发散。
    tint = clamp(tint, vec3(0.0), vec3(4.0));
    tint /= max(
            dot(tint, MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS),
            MINETALE_ENVIRONMENT_PROXY_EPSILON
    );
    response = 1.0 - exp2(-environmentLuma);
    return true;
}

vec3 minetaleDyePreservingLuma(vec3 shadedColor, vec3 tint) {
    vec3 dyedColor = shadedColor * tint;
    float shadedLuma = max(
            dot(shadedColor, MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS),
            0.0
    );
    float dyedLuma = dot(dyedColor, MINETALE_ENVIRONMENT_LIGHT_LUMA_WEIGHTS);
    if (shadedLuma > MINETALE_ENVIRONMENT_PROXY_EPSILON
            && dyedLuma > MINETALE_ENVIRONMENT_PROXY_EPSILON) {
        dyedColor *= shadedLuma / dyedLuma;
    }
    return dyedColor;
}

// OBJ 先以单位亮度环境色染 ADS，再加入能量补偿，使背光面保留环境色特征。
vec3 minetaleApplyEnvironmentTint(
        vec3 shadedColor,
        vec3 worldPosition,
        vec3 worldNormal
) {
    vec3 tint;
    float response;
    if (!minetaleResolveEnvironmentTint(
            worldPosition,
            worldNormal,
            tint,
            response)) {
        return shadedColor;
    }

    float strength = clamp(EnvironmentLightControl0.y, 0.0, 1.0);
    float dyeAmount = strength * response;
    vec3 dyedColor = minetaleDyePreservingLuma(shadedColor, tint);
    vec3 tintedColor = mix(shadedColor, dyedColor, dyeAmount);
    vec3 energyCompensation = tint * strength * response;
    return tintedColor + energyCompensation;
}

// BattleFrame 只做保亮染色，不加能量补偿，以防 Clamp 后各通道同时饱和成白色。
vec3 minetaleApplyEnvironmentDyeOnly(
        vec3 shadedColor,
        vec3 worldPosition,
        vec3 worldNormal
) {
    vec3 tint;
    float response;
    if (!minetaleResolveEnvironmentTint(
            worldPosition,
            worldNormal,
            tint,
            response)) {
        return shadedColor;
    }

    float strength = clamp(EnvironmentLightControl0.y, 0.0, 1.0);
    vec3 dyedColor = minetaleDyePreservingLuma(shadedColor, tint);
    return mix(shadedColor, dyedColor, strength);
}

#endif
