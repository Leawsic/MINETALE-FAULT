#version 330

uniform sampler2D SceneDepthSampler;
uniform sampler2D ShadowSampler;
uniform sampler2D MinMaxLevel0;
uniform sampler2D MinMaxLevel1;
uniform sampler2D MinMaxLevel2;
uniform sampler2D MinMaxLevel3;
uniform sampler2D MinMaxLevel4;
uniform sampler2D PointShadowAtlas;
uniform sampler2D PointMinMaxLevel0;
uniform sampler2D PointMinMaxLevel1;
uniform sampler2D PointMinMaxLevel2;
uniform sampler2D PointMinMaxLevel3;

layout(std140) uniform LightingUniform {
    mat4 LightingLightViewProjection;
    vec4 LightDirectionAmbient;
    vec4 CameraPositionDiffuse;
    vec4 SpecularShadow;
    vec4 ShadowParams;
    vec4 SoulLightPositionRadius;
    vec4 SoulLightColorEnabled;
};

layout(std140) uniform VolumetricUniform {
    mat4 InverseViewProjection;
    mat4 WorldToVolume;
    mat4 LightViewProjection;
    mat4 VolumeToGrid;
    mat4 PointFaceViewProjection[6];
    vec4 VolumeHalfSizeProjection;
    vec4 VolumeDensityScatterShadow;
    vec4 TraceControl;
    vec4 ShadowMapSizeRoiOrigin;
    vec4 RoiSizeLevelDebug;
    vec4 VisitLimitsFogControl;
    vec4 BaseFogColor;
    vec4 SunScatterColor;
    vec4 ToneMappingControl;
    vec4 ShadowAppearanceControl;
    vec4 GridOcclusionControl;
    vec4 PointPositionRadius;
    vec4 SoulColorGridTransmission;
    vec4 PointAtlasLayout;
    vec4 PointTraceControl;
    vec4 PointVisitDebug;
    vec4 PointOpticsControl;
    vec4 SoulShadowAppearanceControl;
    vec4 SoulToneMappingControl;
    vec4 VolumetricOutputDither;
};

in vec2 texCoord;
out vec4 fragColor;

#moj_import <minetale:battle/dither/quantization.glsl>

const int DENSITY_SEGMENT_COUNT = 8;
const int MAX_HIERARCHY_VISITS = 512;
const int MAX_LEAF_VISITS = 256;
const int POINT_SOURCE_SEGMENT_COUNT = 16;
const int MAX_POINT_SOURCE_BREAKS = POINT_SOURCE_SEGMENT_COUNT + 3;
const int MAX_POINT_FACE_SPLITS = 8;
const int MAX_POINT_HIERARCHY_VISITS = 256;
const int MAX_POINT_TEXEL_VISITS = 128;
const float FAR_DEPTH_EPSILON = 1.0e-7;
const float MIN_POINT_SOURCE_INTEGRAL = 1.0e-7;
float densityPrefix[DENSITY_SEGMENT_COUNT + 1];
float pointSourceBreaks[MAX_POINT_SOURCE_BREAKS];
float pointSourcePrefix[MAX_POINT_SOURCE_BREAKS];
int pointSourceBreakCount;
float pointFaceSplits[MAX_POINT_FACE_SPLITS];
int pointFaceSplitCount;
int pointHierarchyVisits;
int pointRawTexelVisits;
bool pointTraversalOverflow;
float pointShadowedSourceIntegral;
vec3 pointRelativeStart;
vec3 pointRelativeDelta;
vec3 pointWorldStart;
vec3 pointWorldDelta;
vec3 pointLocalStart;
vec3 pointLocalDelta;
vec3 pointInverseVolumeHalfSize;
float pointGlobalEntry;
float pointGlobalSpan;
float pointWorldLength;
float pointGridEntry;
float pointGridExit;
float shadowCutoffEntry;
float shadowCutoffExit;

vec3 unproject(vec2 uv, float depth) {
    vec4 world = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return world.xyz / world.w;
}

bool intersectAxis(float origin, float direction, float halfSize, inout float entry, inout float exit) {
    if (abs(direction) < 1.0e-7) return abs(origin) <= halfSize;
    float inverseDirection = 1.0 / direction;
    float first = (-halfSize - origin) * inverseDirection;
    float second = (halfSize - origin) * inverseDirection;
    entry = max(entry, min(first, second));
    exit = min(exit, max(first, second));
    return exit >= entry;
}

bool intersectBox(vec3 origin, vec3 direction, vec3 halfSize, out float entry, out float exit) {
    entry = -1.0e30;
    exit = 1.0e30;
    return intersectAxis(origin.x, direction.x, halfSize.x, entry, exit)
            && intersectAxis(origin.y, direction.y, halfSize.y, entry, exit)
            && intersectAxis(origin.z, direction.z, halfSize.z, entry, exit)
            && exit >= max(entry, 0.0);
}

float mediumDensity(vec3 localPosition, vec3 inverseHalfSize) {
    vec3 q = abs(localPosition * inverseHalfSize);
    float edge = max(q.x, max(q.y, q.z));
    if (edge >= 1.0) return 0.0;
    return VolumeDensityScatterShadow.x * pow(max(1.0 - edge, 0.0), VolumeDensityScatterShadow.y);
}

float opticalDepthAt(float t) {
    float scaled = clamp(t, 0.0, 1.0) * float(DENSITY_SEGMENT_COUNT);
    int index = min(int(floor(scaled)), DENSITY_SEGMENT_COUNT - 1);
    float fraction = scaled - float(index);
    return mix(densityPrefix[index], densityPrefix[index + 1], fraction);
}

float shadowOpticalDepthAt(float t) {
    float total = opticalDepthAt(t);
    float excludedEnd = min(clamp(t, 0.0, 1.0), shadowCutoffExit);
    if (excludedEnd <= shadowCutoffEntry) return total;
    return total - max(
            opticalDepthAt(excludedEnd) - opticalDepthAt(shadowCutoffEntry),
            0.0);
}

float decodeDepth16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

vec4 fetchNode(int level, ivec2 node) {
    if (level == 0) return texelFetch(MinMaxLevel0, node, 0);
    if (level == 1) return texelFetch(MinMaxLevel1, node, 0);
    if (level == 2) return texelFetch(MinMaxLevel2, node, 0);
    if (level == 3) return texelFetch(MinMaxLevel3, node, 0);
    return texelFetch(MinMaxLevel4, node, 0);
}

float boundaryExit(vec2 uvStart, vec2 inverseUvDelta, float t,
                   vec2 minimumUv, vec2 maximumUv, float limit) {
    float result = limit;
    if (inverseUvDelta.x > 0.0) result = min(result, (maximumUv.x - uvStart.x) * inverseUvDelta.x);
    else if (inverseUvDelta.x < 0.0) result = min(result, (minimumUv.x - uvStart.x) * inverseUvDelta.x);
    if (inverseUvDelta.y > 0.0) result = min(result, (maximumUv.y - uvStart.y) * inverseUvDelta.y);
    else if (inverseUvDelta.y < 0.0) result = min(result, (minimumUv.y - uvStart.y) * inverseUvDelta.y);
    return clamp(result, t, limit);
}

bool clipAxis(float start, float delta, float minimumValue, float maximumValue,
              inout float entry, inout float exit) {
    if (abs(delta) < 1.0e-12) return start >= minimumValue && start <= maximumValue;
    float inverseDelta = 1.0 / delta;
    float first = (minimumValue - start) * inverseDelta;
    float second = (maximumValue - start) * inverseDelta;
    entry = max(entry, min(first, second));
    exit = min(exit, max(first, second));
    return exit >= entry;
}

bool clipAxisWithInverse(float start, float delta, float inverseDelta,
                         float minimumValue, float maximumValue,
                         inout float entry, inout float exit) {
    if (inverseDelta == 0.0) return start >= minimumValue && start <= maximumValue;
    float first = (minimumValue - start) * inverseDelta;
    float second = (maximumValue - start) * inverseDelta;
    entry = max(entry, min(first, second));
    exit = min(exit, max(first, second));
    return exit >= entry;
}

bool clipUvLine(vec2 uvStart, vec2 uvDelta, vec2 minimumUv, vec2 maximumUv,
                out vec2 inverseUvDelta, out float entry, out float exit) {
    inverseUvDelta = vec2(
            abs(uvDelta.x) <= 1.0e-12 ? 0.0 : 1.0 / uvDelta.x,
            abs(uvDelta.y) <= 1.0e-12 ? 0.0 : 1.0 / uvDelta.y);
    entry = 0.0;
    exit = 1.0;
    return clipAxisWithInverse(
            uvStart.x, uvDelta.x, inverseUvDelta.x, minimumUv.x, maximumUv.x, entry, exit)
            && clipAxisWithInverse(
                    uvStart.y, uvDelta.y, inverseUvDelta.y, minimumUv.y, maximumUv.y, entry, exit);
}

bool clipGridRegion(vec3 start, vec3 delta, float minimumZ,
                    out float entry, out float exit) {
    entry = 0.0;
    exit = 1.0;
    return clipAxis(start.x, delta.x, -0.5, 0.5, entry, exit)
            && clipAxis(start.y, delta.y, -0.5, 0.5, entry, exit)
            && clipAxis(start.z, delta.z, minimumZ, 1.0e20, entry, exit);
}

float rawShadowDepth(ivec2 texel, ivec2 shadowSize) {
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, shadowSize))) return 1.0;
    return texelFetch(ShadowSampler, texel, 0).r;
}

float shadowIntervalContribution(float t0, float t1, float shadowDepth,
                                 float depthStart, float depthDelta) {
    if (t1 <= t0 || shadowDepth >= 1.0 - FAR_DEPTH_EPSILON) return 0.0;
    float f0 = depthStart + depthDelta * t0 - shadowDepth - TraceControl.x;
    float f1 = depthStart + depthDelta * t1 - shadowDepth - TraceControl.x;
    bool shadow0 = f0 > 0.0;
    bool shadow1 = f1 > 0.0;
    if (shadow0 == shadow1) {
        return shadow0 ? shadowOpticalDepthAt(t1) - shadowOpticalDepthAt(t0) : 0.0;
    }
    float denominator = f1 - f0;
    float crossing = abs(denominator) < 1.0e-12
            ? (t0 + t1) * 0.5
            : t0 - f0 / denominator * (t1 - t0);
    crossing = clamp(crossing, t0, t1);
    return shadow0
            ? shadowOpticalDepthAt(crossing) - shadowOpticalDepthAt(t0)
            : shadowOpticalDepthAt(t1) - shadowOpticalDepthAt(crossing);
}

bool pointIntersectSphere(
        vec3 origin,
        vec3 direction,
        vec3 center,
        float radius,
        out float entry,
        out float exit
) {
    vec3 offset = origin - center;
    float b = dot(offset, direction);
    float c = dot(offset, offset) - radius * radius;
    float discriminant = b * b - c;
    if (discriminant < 0.0) return false;
    float root = sqrt(max(discriminant, 0.0));
    entry = -b - root;
    exit = -b + root;
    return exit >= entry;
}

float pointSourceIntegralAt(float t) {
    float value = clamp(t, 0.0, 1.0);
    int index = max(pointSourceBreakCount - 2, 0);
    for (int candidate = 0; candidate < MAX_POINT_SOURCE_BREAKS - 1; ++candidate) {
        if (candidate + 1 >= pointSourceBreakCount) break;
        index = candidate;
        if (value <= pointSourceBreaks[candidate + 1]) break;
    }
    float start = pointSourceBreaks[index];
    float end = pointSourceBreaks[index + 1];
    float fraction = (value - start) / max(end - start, 1.0e-8);
    return mix(pointSourcePrefix[index], pointSourcePrefix[index + 1], fraction);
}

void pointInsertSourceBreak(float value) {
    float candidate = clamp(value, 0.0, 1.0);
    for (int index = 0; index < MAX_POINT_SOURCE_BREAKS; ++index) {
        if (index >= pointSourceBreakCount) break;
        if (abs(pointSourceBreaks[index] - candidate) <= 1.0e-7) return;
    }
    if (pointSourceBreakCount >= MAX_POINT_SOURCE_BREAKS) return;
    pointSourceBreaks[pointSourceBreakCount++] = candidate;
}

void pointSortSourceBreaks() {
    for (int i = 1; i < MAX_POINT_SOURCE_BREAKS; ++i) {
        if (i >= pointSourceBreakCount) break;
        float value = pointSourceBreaks[i];
        int j = i - 1;
        for (int shift = 0; shift < MAX_POINT_SOURCE_BREAKS; ++shift) {
            if (j < 0 || pointSourceBreaks[j] <= value) break;
            pointSourceBreaks[j + 1] = pointSourceBreaks[j];
            j--;
        }
        pointSourceBreaks[j + 1] = value;
    }
}

float softenedInverseSquare(float distanceSquared, float coreRadius) {
    float coreSquared = coreRadius * coreRadius;
    return coreSquared / max(distanceSquared + coreSquared, 1.0e-8);
}

float hgPhase(float cosTheta, float g) {
    float gSquared = g * g;
    float denominator = pow(
            max(1.0 + gSquared - 2.0 * g * cosTheta, 1.0e-4),
            1.5);
    return (1.0 - gSquared)
            / max(4.0 * 3.14159265 * denominator, 1.0e-4);
}

float pointLightPathTransmittance(
        vec3 soulWorldPosition,
        vec3 sampleWorldPosition
) {
    vec3 path = sampleWorldPosition - soulWorldPosition;
    float pathLength = length(path);
    if (pathLength <= 1.0e-6) return 1.0;

    const float sampleA = 0.2113248654;
    const float sampleB = 0.7886751346;
    vec3 worldA = soulWorldPosition + path * sampleA;
    vec3 worldB = soulWorldPosition + path * sampleB;
    vec3 localA = (WorldToVolume * vec4(worldA, 1.0)).xyz;
    vec3 localB = (WorldToVolume * vec4(worldB, 1.0)).xyz;
    float densityA = mediumDensity(localA, pointInverseVolumeHalfSize);
    float densityB = mediumDensity(localB, pointInverseVolumeHalfSize);
    float opticalDepth = pathLength * 0.5 * (densityA + densityB);
    return exp(-opticalDepth);
}

float pointSourceAt(float t) {
    float globalT = clamp(pointGlobalEntry + pointGlobalSpan * t, 0.0, 1.0);
    float cameraTransmittance = exp(-opticalDepthAt(globalT));
    vec3 relativePosition = pointRelativeStart + pointRelativeDelta * t;
    vec3 localPosition = pointLocalStart + pointLocalDelta * t;
    vec3 sampleWorldPosition = pointWorldStart + pointWorldDelta * t;
    float extinctionCoefficient = mediumDensity(localPosition, pointInverseVolumeHalfSize);
    float distanceSquared = dot(relativePosition, relativePosition);
    float distanceFalloff = softenedInverseSquare(
            distanceSquared,
            PointOpticsControl.x);
    float normalizedRadius = sqrt(max(distanceSquared, 0.0))
            / max(PointPositionRadius.w, 1.0e-6);
    float supportWindow = 1.0 - smoothstep(
            clamp(1.0 - PointOpticsControl.y, 0.0, 1.0),
            1.0,
            normalizedRadius);

    vec3 toSoulDelta = PointPositionRadius.xyz - sampleWorldPosition;
    vec3 toCameraDelta = CameraPositionDiffuse.xyz - sampleWorldPosition;
    vec3 toSoul = toSoulDelta
            / max(length(toSoulDelta), 1.0e-6);
    vec3 toCamera = toCameraDelta
            / max(length(toCameraDelta), 1.0e-6);
    float phase = hgPhase(
            clamp(dot(toSoul, toCamera), -1.0, 1.0),
            PointOpticsControl.z);
    float lightPathMediumTransmittance = pointLightPathTransmittance(
            PointPositionRadius.xyz,
            sampleWorldPosition);
    return cameraTransmittance
            * lightPathMediumTransmittance
            * extinctionCoefficient
            * distanceFalloff
            * supportWindow
            * phase;
}

float pointGridVisibility(float t) {
    bool occluded = pointGridExit > pointGridEntry
            && t >= pointGridEntry
            && t <= pointGridExit;
    return occluded ? SoulColorGridTransmission.a : 1.0;
}

float integratePointSourceSubinterval(float t0, float t1) {
    if (t1 <= t0) return 0.0;
    float midpoint = (t0 + t1) * 0.5;
    return pointSourceAt(midpoint)
            * clamp(pointGridVisibility(midpoint), 0.0, 1.0)
            * (t1 - t0)
            * pointWorldLength;
}

vec4 pointFetchNode(int level, ivec2 texel) {
    if (level == 0) return texelFetch(PointMinMaxLevel0, texel, 0);
    if (level == 1) return texelFetch(PointMinMaxLevel1, texel, 0);
    if (level == 2) return texelFetch(PointMinMaxLevel2, texel, 0);
    return texelFetch(PointMinMaxLevel3, texel, 0);
}

mat4 pointFaceMatrix(int face) {
    if (face == 0) return PointFaceViewProjection[0];
    if (face == 1) return PointFaceViewProjection[1];
    if (face == 2) return PointFaceViewProjection[2];
    if (face == 3) return PointFaceViewProjection[3];
    if (face == 4) return PointFaceViewProjection[4];
    return PointFaceViewProjection[5];
}

int pointDominantFace(vec3 value) {
    vec3 magnitude = abs(value);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) {
        return value.x >= 0.0 ? 0 : 1;
    }
    if (magnitude.y >= magnitude.z) return value.y >= 0.0 ? 2 : 3;
    return value.z >= 0.0 ? 4 : 5;
}

void pointInsertFaceSplit(float value) {
    if (value <= 0.0 || value >= 1.0 || pointFaceSplitCount >= MAX_POINT_FACE_SPLITS) return;
    pointFaceSplits[pointFaceSplitCount++] = value;
}

void pointAddPlaneSplit(vec3 normal) {
    float denominator = dot(normal, pointRelativeDelta);
    if (abs(denominator) <= 1.0e-10) return;
    pointInsertFaceSplit(-dot(normal, pointRelativeStart) / denominator);
}

void pointBuildFaceSplits() {
    pointFaceSplitCount = 0;
    pointFaceSplits[pointFaceSplitCount++] = 0.0;
    pointAddPlaneSplit(vec3(1.0, -1.0, 0.0));
    pointAddPlaneSplit(vec3(1.0, 1.0, 0.0));
    pointAddPlaneSplit(vec3(1.0, 0.0, -1.0));
    pointAddPlaneSplit(vec3(1.0, 0.0, 1.0));
    pointAddPlaneSplit(vec3(0.0, 1.0, -1.0));
    pointAddPlaneSplit(vec3(0.0, 1.0, 1.0));
    pointFaceSplits[pointFaceSplitCount++] = 1.0;
    for (int i = 1; i < MAX_POINT_FACE_SPLITS; ++i) {
        if (i >= pointFaceSplitCount) break;
        float value = pointFaceSplits[i];
        int j = i - 1;
        for (int shift = 0; shift < MAX_POINT_FACE_SPLITS; ++shift) {
            if (j < 0 || pointFaceSplits[j] <= value) break;
            pointFaceSplits[j + 1] = pointFaceSplits[j];
            j--;
        }
        pointFaceSplits[j + 1] = value;
    }
}

void pointRadialRange(float t0, float t1, out float minimumDepth, out float maximumDepth) {
    vec3 first = pointRelativeStart + pointRelativeDelta * t0;
    vec3 second = pointRelativeStart + pointRelativeDelta * t1;
    float firstDepth = length(first) / PointPositionRadius.w;
    float secondDepth = length(second) / PointPositionRadius.w;
    maximumDepth = max(firstDepth, secondDepth);
    float denominator = dot(pointRelativeDelta, pointRelativeDelta);
    float closest = denominator <= 1.0e-12
            ? t0
            : clamp(-dot(pointRelativeStart, pointRelativeDelta) / denominator, t0, t1);
    minimumDepth = length(pointRelativeStart + pointRelativeDelta * closest)
            / PointPositionRadius.w;
}

void pointAddShadowInterval(float t0, float t1, float storedDepth) {
    if (t1 <= t0 || storedDepth >= 1.0 - FAR_DEPTH_EPSILON) return;
    float threshold = min(storedDepth + PointTraceControl.x, 1.0) * PointPositionRadius.w;
    float a = dot(pointRelativeDelta, pointRelativeDelta);
    float b = 2.0 * dot(pointRelativeStart, pointRelativeDelta);
    float c = dot(pointRelativeStart, pointRelativeStart) - threshold * threshold;
    if (a <= 1.0e-12) {
        if (c > 0.0) {
            pointShadowedSourceIntegral +=
                    pointSourceIntegralAt(t1) - pointSourceIntegralAt(t0);
        }
        return;
    }
    float discriminant = b * b - 4.0 * a * c;
    if (discriminant <= 0.0) {
        float midpoint = (t0 + t1) * 0.5;
        vec3 point = pointRelativeStart + pointRelativeDelta * midpoint;
        if (dot(point, point) > threshold * threshold) {
            pointShadowedSourceIntegral +=
                    pointSourceIntegralAt(t1) - pointSourceIntegralAt(t0);
        }
        return;
    }
    float root = sqrt(discriminant);
    float firstRoot = (-b - root) / (2.0 * a);
    float secondRoot = (-b + root) / (2.0 * a);
    float leftEnd = min(t1, firstRoot);
    if (leftEnd > t0) {
        pointShadowedSourceIntegral +=
                pointSourceIntegralAt(leftEnd) - pointSourceIntegralAt(t0);
    }
    float rightStart = max(t0, secondRoot);
    if (t1 > rightStart) {
        pointShadowedSourceIntegral +=
                pointSourceIntegralAt(t1) - pointSourceIntegralAt(rightStart);
    }
}

vec2 pointProjectedUv(vec4 clip) {
    return clip.xy / clip.w * 0.5 + 0.5;
}

float pointHomogeneousBoundary(
        vec4 clipStart,
        vec4 clipDelta,
        int axis,
        float ndcBoundary,
        float after,
        float limit
) {
    float numerator = ndcBoundary * clipStart.w - clipStart[axis];
    float denominator = clipDelta[axis] - ndcBoundary * clipDelta.w;
    if (abs(denominator) <= 1.0e-12) return limit;
    float result = numerator / denominator;
    float epsilon = PointTraceControl.y;
    return result > after + epsilon && result <= limit + epsilon ? result : limit;
}

float pointProjectedCellExit(
        vec4 clipStart,
        vec4 clipDelta,
        vec2 uv,
        vec2 uvAhead,
        ivec2 cell,
        int cellCoverage,
        int faceSize,
        float after,
        float limit
) {
    vec2 minimumUv = vec2(cell * cellCoverage) / float(faceSize);
    vec2 maximumUv = min(vec2((cell + 1) * cellCoverage) / float(faceSize), vec2(1.0));
    vec2 boundaryUv = vec2(
            uvAhead.x >= uv.x ? maximumUv.x : minimumUv.x,
            uvAhead.y >= uv.y ? maximumUv.y : minimumUv.y);
    float xExit = pointHomogeneousBoundary(
            clipStart, clipDelta, 0, boundaryUv.x * 2.0 - 1.0, after, limit);
    float yExit = pointHomogeneousBoundary(
            clipStart, clipDelta, 1, boundaryUv.y * 2.0 - 1.0, after, limit);
    return clamp(min(xExit, yExit), after, limit);
}

int pointLevelFaceSize(int level) {
    int result = int(PointAtlasLayout.x + 0.5);
    for (int index = 0; index < 4; ++index) {
        if (index > level) break;
        result = (result + 3) / 4;
    }
    return max(result, 1);
}

float pointRawDepth(int face, vec2 uv) {
    int faceSize = int(PointAtlasLayout.x + 0.5);
    int border = int(PointAtlasLayout.y + 0.5);
    int tileSize = int(PointAtlasLayout.z + 0.5);
    vec2 safeUv = clamp(
            uv,
            vec2(0.5 / float(faceSize)),
            vec2(1.0 - 0.5 / float(faceSize)));
    ivec2 localTexel = ivec2(floor(safeUv * float(faceSize)));
    ivec2 faceTile = ivec2(face % 3, face / 3);
    return texelFetch(
            PointShadowAtlas,
            faceTile * tileSize + ivec2(border) + localTexel,
            0).r;
}

vec2 pointNodeMinMax(int face, int level, ivec2 node) {
    int faceSize = pointLevelFaceSize(level);
    ivec2 faceTile = ivec2(face % 3, face / 3);
    vec4 encoded = pointFetchNode(level, faceTile * faceSize + node);
    return vec2(decodeDepth16(encoded.rg), decodeDepth16(encoded.ba));
}

void pointTraceFace(int face, float segmentStart, float segmentEnd) {
    int requiredMask = int(PointVisitDebug.x + 0.5);
    if ((requiredMask & (1 << face)) == 0) return;
    mat4 projection = pointFaceMatrix(face);
    vec4 clipStart = projection * vec4(pointWorldStart, 1.0);
    vec4 clipDelta = projection * vec4(pointWorldDelta, 0.0);
    int faceSize = int(PointAtlasLayout.x + 0.5);
    int topLevel = clamp(int(PointAtlasLayout.w + 0.5), 0, 3);
    int hierarchyLimit = int(PointVisitDebug.y + 0.5);
    int texelLimit = int(PointVisitDebug.z + 0.5);
    float epsilon = PointTraceControl.y;
    float t = segmentStart;
    int level = topLevel;

    for (int visit = 0; visit < MAX_POINT_HIERARCHY_VISITS; ++visit) {
        if (t >= segmentEnd - epsilon) break;
        if (pointHierarchyVisits >= hierarchyLimit) {
            pointTraversalOverflow = true;
            break;
        }
        pointHierarchyVisits++;
        float sampleT = min(segmentEnd, t + epsilon);
        vec4 clip = clipStart + clipDelta * sampleT;
        if (clip.w <= 1.0e-8) {
            pointTraversalOverflow = true;
            break;
        }
        vec2 uv = clamp(pointProjectedUv(clip), vec2(0.0), vec2(1.0));
        vec2 uvAhead = pointProjectedUv(
                clipStart + clipDelta * min(segmentEnd, sampleT + epsilon));
        int coverage = 1 << (2 * (level + 1));
        int cellsPerFace = (faceSize + coverage - 1) / coverage;
        ivec2 node = clamp(
                ivec2(floor(uv * float(faceSize) / float(coverage))),
                ivec2(0),
                ivec2(cellsPerFace - 1));
        float nodeExit = pointProjectedCellExit(
                clipStart, clipDelta, uv, uvAhead, node, coverage, faceSize, t, segmentEnd);
        if (nodeExit <= t + epsilon) nodeExit = min(segmentEnd, t + epsilon);

        vec2 minimumMaximum = pointNodeMinMax(face, level, node);
        float rayMinimum;
        float rayMaximum;
        pointRadialRange(t, nodeExit, rayMinimum, rayMaximum);
        bool definitelyLit = rayMaximum - PointTraceControl.x < minimumMaximum.x;
        bool definitelyShadow = rayMinimum - PointTraceControl.x > minimumMaximum.y;
        if (definitelyLit || definitelyShadow) {
            if (definitelyShadow) {
                pointShadowedSourceIntegral +=
                        pointSourceIntegralAt(nodeExit) - pointSourceIntegralAt(t);
            }
            t = min(segmentEnd, nodeExit + epsilon);
            level = min(topLevel, level + 1);
            continue;
        }
        if (level > 0) {
            level--;
            continue;
        }

        float leafT = t;
        for (int leaf = 0; leaf < MAX_POINT_TEXEL_VISITS; ++leaf) {
            if (leafT >= nodeExit - epsilon) break;
            if (pointRawTexelVisits >= texelLimit) {
                pointTraversalOverflow = true;
                break;
            }
            float leafSample = min(nodeExit, leafT + epsilon);
            vec2 leafUv = clamp(
                    pointProjectedUv(clipStart + clipDelta * leafSample),
                    vec2(0.0),
                    vec2(1.0));
            vec2 leafUvAhead = pointProjectedUv(
                    clipStart + clipDelta * min(nodeExit, leafSample + epsilon));
            ivec2 texel = clamp(
                    ivec2(floor(leafUv * float(faceSize))),
                    ivec2(0),
                    ivec2(faceSize - 1));
            float leafExit = pointProjectedCellExit(
                    clipStart, clipDelta, leafUv, leafUvAhead,
                    texel, 1, faceSize, leafT, nodeExit);
            if (leafExit <= leafT + epsilon) leafExit = min(nodeExit, leafT + epsilon);
            pointAddShadowInterval(leafT, leafExit, pointRawDepth(face, leafUv));
            pointRawTexelVisits++;
            leafT = min(nodeExit, leafExit + epsilon);
        }
        t = pointTraversalOverflow ? leafT : min(segmentEnd, nodeExit + epsilon);
        level = min(topLevel, 1);
        if (pointTraversalOverflow) break;
    }

    if (t < segmentEnd - epsilon) {
        pointTraversalOverflow = true;
        float midpoint = (t + segmentEnd) * 0.5;
        vec4 clip = clipStart + clipDelta * midpoint;
        if (clip.w > 1.0e-8) {
            pointAddShadowInterval(t, segmentEnd, pointRawDepth(face, pointProjectedUv(clip)));
        }
    }
}

vec3 softClipRadiance(vec3 radiance, float limit) {
    return radiance
            / (vec3(1.0) + radiance / max(limit, 1.0e-4));
}

vec3 toneMapLuminance(vec3 color, vec4 control) {
    if (control.w <= 0.5) return color;
    vec3 exposed = max(color * control.x, vec3(0.0));
    float luminance = dot(exposed, vec3(0.2126, 0.7152, 0.0722));
    float mappedLuminance = clamp(
            (luminance - control.z) * control.y + control.z,
            0.0,
            1.0);
    return luminance > 1.0e-6
            ? clamp(exposed * (mappedLuminance / luminance), vec3(0.0), vec3(1.0))
            : vec3(0.0);
}

void main() {
    vec3 cameraWorld = CameraPositionDiffuse.xyz;
    float sceneDepth = texelFetch(SceneDepthSampler, ivec2(gl_FragCoord.xy), 0).r;
    vec3 sceneWorld = unproject(texCoord, sceneDepth);
    vec3 rayOrigin;
    vec3 worldDirection;
    float sceneDistance;
    if (VolumeHalfSizeProjection.w > 0.5) {
        vec3 nearWorld = unproject(texCoord, 0.0);
        vec3 farWorld = unproject(texCoord, 1.0);
        rayOrigin = nearWorld;
        worldDirection = normalize(farWorld - nearWorld);
        sceneDistance = max(dot(sceneWorld - rayOrigin, worldDirection), 0.0);
    } else {
        rayOrigin = cameraWorld;
        vec3 worldRay = sceneWorld - rayOrigin;
        sceneDistance = length(worldRay);
        if (sceneDistance <= 1.0e-8) discard;
        worldDirection = worldRay / sceneDistance;
    }
    vec3 localOrigin = (WorldToVolume * vec4(rayOrigin, 1.0)).xyz;
    vec3 localDirection = (WorldToVolume * vec4(worldDirection, 0.0)).xyz;

    float boxEntry;
    float boxExit;
    if (!intersectBox(localOrigin, localDirection, VolumeHalfSizeProjection.xyz, boxEntry, boxExit)) discard;

    float startDistance = max(boxEntry, 0.0);
    float endDistance = min(boxExit, sceneDistance);
    if (endDistance <= startDistance) discard;

    float worldLength = endDistance - startDistance;
    vec3 worldStart = rayOrigin + worldDirection * startDistance;
    vec3 worldDelta = worldDirection * worldLength;
    vec3 worldEnd = worldStart + worldDelta;
    vec3 localStart = localOrigin + localDirection * startDistance;
    vec3 localDelta = localDirection * worldLength;
    vec3 inverseHalfSize = 1.0 / VolumeHalfSizeProjection.xyz;
    float inverseDensitySegmentCount = 1.0 / float(DENSITY_SEGMENT_COUNT);
    float densitySegmentLength = worldLength * inverseDensitySegmentCount;

    densityPrefix[0] = 0.0;
    for (int index = 0; index < DENSITY_SEGMENT_COUNT; ++index) {
        float midpoint = (float(index) + 0.5) * inverseDensitySegmentCount;
        vec3 localPosition = localStart + localDelta * midpoint;
        float segmentDepth = mediumDensity(localPosition, inverseHalfSize)
                * densitySegmentLength;
        densityPrefix[index + 1] = densityPrefix[index] + segmentDepth;
    }
    float totalOpticalDepth = densityPrefix[DENSITY_SEGMENT_COUNT];
    if (totalOpticalDepth <= TraceControl.w) discard;

    // 用 opticalDepthAt 的同一归一化视线参数裁剪网格下方区间，以防密度中点分类产生色带。
    vec3 gridStart = (VolumeToGrid * vec4(localStart, 1.0)).xyz;
    vec3 gridDelta = (VolumeToGrid * vec4(localDelta, 0.0)).xyz;
    shadowCutoffEntry = 1.0;
    shadowCutoffExit = 0.0;
    float scatterBoundaryEntry = 1.0;
    float scatterBoundaryExit = 0.0;
    if (GridOcclusionControl.y > 0.5) {
        bool hasShadowCutoff = clipGridRegion(
                gridStart, gridDelta, 0.0, shadowCutoffEntry, shadowCutoffExit);
        if (!hasShadowCutoff) {
            shadowCutoffEntry = 1.0;
            shadowCutoffExit = 0.0;
        }
        bool hasScatterBoundary = clipGridRegion(
                gridStart, gridDelta, GridOcclusionControl.x,
                scatterBoundaryEntry, scatterBoundaryExit);
        if (!hasScatterBoundary) {
            scatterBoundaryEntry = 1.0;
            scatterBoundaryExit = 0.0;
        }
    }

    vec4 lightStart = LightViewProjection * vec4(worldStart, 1.0);
    vec4 lightEnd = LightViewProjection * vec4(worldEnd, 1.0);
    if (abs(lightStart.w) < 1.0e-8 || abs(lightEnd.w) < 1.0e-8) discard;
    vec3 projectedStart = lightStart.xyz / lightStart.w * 0.5 + 0.5;
    vec3 projectedEnd = lightEnd.xyz / lightEnd.w * 0.5 + 0.5;
    vec2 uvStart = projectedStart.xy;
    vec2 uvDelta = projectedEnd.xy - projectedStart.xy;
    float depthStart = projectedStart.z;
    float depthDelta = projectedEnd.z - projectedStart.z;

    float shadowOpticalDepth = 0.0;
    int hierarchyVisits = 0;
    int leafVisits = 0;
    int classifiedLevel = -2;
    bool overflow = false;

    if (GridOcclusionControl.w > 0.5
            && ShadowParams.y >= 0.5
            && RoiSizeLevelDebug.x > 0.0
            && RoiSizeLevelDebug.y > 0.0) {
        vec2 shadowSize = ShadowMapSizeRoiOrigin.xy;
        ivec2 shadowTexelSize = ivec2(shadowSize);
        vec2 inverseShadowSize = 1.0 / shadowSize;
        vec2 roiMinimum = ShadowMapSizeRoiOrigin.zw * inverseShadowSize;
        vec2 roiMaximum = (ShadowMapSizeRoiOrigin.zw + RoiSizeLevelDebug.xy) * inverseShadowSize;
        vec2 inverseUvDelta;
        float traceStart;
        float traceEnd;
        bool intersectsShadowVolume = clipUvLine(
                uvStart, uvDelta, roiMinimum, roiMaximum, inverseUvDelta, traceStart, traceEnd);
        if (intersectsShadowVolume) {
            intersectsShadowVolume = clipAxis(
                    depthStart, depthDelta, 0.0, 1.0, traceStart, traceEnd);
        }
        if (intersectsShadowVolume) {
            traceStart = clamp(traceStart, 0.0, 1.0);
            traceEnd = clamp(traceEnd, traceStart, 1.0);
            if (abs(uvDelta.x) + abs(uvDelta.y) < TraceControl.y) {
                vec2 sampleUv = uvStart + uvDelta * ((traceStart + traceEnd) * 0.5);
                ivec2 texel = ivec2(floor(sampleUv * shadowSize));
                shadowOpticalDepth += shadowIntervalContribution(
                        traceStart, traceEnd, rawShadowDepth(texel, shadowTexelSize), depthStart, depthDelta);
                leafVisits = 1;
                classifiedLevel = -1;
            } else {
                float t = traceStart;
                int topLevel = clamp(int(RoiSizeLevelDebug.z + 0.5), 0, 4);
                int level = topLevel;
                for (int visit = 0; visit < MAX_HIERARCHY_VISITS; ++visit) {
                    if (t >= traceEnd - TraceControl.z) break;
                    if (visit >= int(VisitLimitsFogControl.x + 0.5)) {
                        overflow = true;
                        break;
                    }
                    hierarchyVisits++;
                    int coverageShift = 2 * (level + 1);
                    int coverage = 1 << coverageShift;
                    float nodeSampleT = min(traceEnd, t + TraceControl.z);
                    ivec2 originalTexel = ivec2(floor(
                            (uvStart + uvDelta * nodeSampleT) * shadowSize
                                    - ShadowMapSizeRoiOrigin.zw));
                    ivec2 node = originalTexel >> coverageShift;
                    vec2 nodeMinimum = (ShadowMapSizeRoiOrigin.zw + vec2(node * coverage))
                            * inverseShadowSize;
                    vec2 nodeMaximum = (ShadowMapSizeRoiOrigin.zw + vec2((node + 1) * coverage))
                            * inverseShadowSize;
                    float nodeExit = boundaryExit(
                            uvStart, inverseUvDelta, t, nodeMinimum, nodeMaximum, traceEnd);
                    if (nodeExit <= t) nodeExit = min(traceEnd, t + TraceControl.z);

                    vec4 encoded = fetchNode(level, node);
                    float nodeMinimumDepth = decodeDepth16(encoded.rg);
                    float nodeMaximumDepth = decodeDepth16(encoded.ba);
                    float rayDepth0 = depthStart + depthDelta * t;
                    float rayDepth1 = depthStart + depthDelta * nodeExit;
                    float rayMinimumDepth = min(rayDepth0, rayDepth1);
                    float rayMaximumDepth = max(rayDepth0, rayDepth1);
                    bool emptyNode = nodeMinimumDepth > nodeMaximumDepth;
                    bool definitelyLit = emptyNode || rayMaximumDepth - TraceControl.x <= nodeMinimumDepth;
                    bool definitelyShadow = !emptyNode && rayMinimumDepth - TraceControl.x > nodeMaximumDepth;

                    if (definitelyLit || definitelyShadow) {
                        if (definitelyShadow) {
                            shadowOpticalDepth += shadowOpticalDepthAt(nodeExit) - shadowOpticalDepthAt(t);
                        }
                        classifiedLevel = max(classifiedLevel, level);
                        t = min(traceEnd, nodeExit + TraceControl.z);
                        level = min(topLevel, level + 1);
                        continue;
                    }
                    if (level > 0) {
                        level--;
                        continue;
                    }

                    float leafT = t;
                    float leafSampleT = min(nodeExit, leafT + TraceControl.z);
                    ivec2 texel = ivec2(floor((uvStart + uvDelta * leafSampleT) * shadowSize));
                    ivec2 texelStep = ivec2(sign(inverseUvDelta));
                    vec2 nextBoundaryUv = vec2(
                            texelStep.x > 0 ? texel.x + 1 : texel.x,
                            texelStep.y > 0 ? texel.y + 1 : texel.y) * inverseShadowSize;
                    vec2 leafTMax = vec2(
                            texelStep.x == 0 ? 1.0e30
                                    : (nextBoundaryUv.x - uvStart.x) * inverseUvDelta.x,
                            texelStep.y == 0 ? 1.0e30
                                    : (nextBoundaryUv.y - uvStart.y) * inverseUvDelta.y);
                    vec2 leafTDelta = abs(inverseUvDelta) * inverseShadowSize;
                    for (int leaf = 0; leaf < MAX_LEAF_VISITS; ++leaf) {
                        if (leafT >= nodeExit - TraceControl.z) break;
                        if (leafVisits >= int(VisitLimitsFogControl.y + 0.5)) {
                            overflow = true;
                            break;
                        }
                        float leafExit = clamp(min(leafTMax.x, leafTMax.y), leafT, nodeExit);
                        if (leafExit <= leafT) leafExit = min(nodeExit, leafT + TraceControl.z);
                        shadowOpticalDepth += shadowIntervalContribution(
                                leafT, leafExit, rawShadowDepth(texel, shadowTexelSize), depthStart, depthDelta);
                        leafVisits++;
                        classifiedLevel = max(classifiedLevel, -1);
                        leafT = min(nodeExit, leafExit + TraceControl.z);
                        if (leafTMax.x <= leafT) {
                            texel.x += texelStep.x;
                            leafTMax.x += leafTDelta.x;
                        }
                        if (leafTMax.y <= leafT) {
                            texel.y += texelStep.y;
                            leafTMax.y += leafTDelta.y;
                        }
                    }
                    t = overflow ? leafT : min(traceEnd, nodeExit + TraceControl.z);
                    level = min(topLevel, 1);
                    if (overflow) break;
                }
                if (t < traceEnd - TraceControl.z) {
                    overflow = true;
                    float midpoint = (t + traceEnd) * 0.5;
                    ivec2 texel = ivec2(floor((uvStart + uvDelta * midpoint) * shadowSize));
                    float shadowDepth = rawShadowDepth(texel, shadowTexelSize);
                    if (shadowDepth < 1.0 - FAR_DEPTH_EPSILON
                            && depthStart + depthDelta * midpoint - TraceControl.x > shadowDepth) {
                        shadowOpticalDepth += shadowOpticalDepthAt(traceEnd) - shadowOpticalDepthAt(t);
                    }
                }
            }
        }
    }

    pointHierarchyVisits = 0;
    pointRawTexelVisits = 0;
    pointTraversalOverflow = false;
    pointShadowedSourceIntegral = 0.0;
    float pointTotalSourceIntegral = 0.0;
    float pointLitSourceIntegral = 0.0;
    float pointExtinctionSupport = 0.0;
    pointGlobalEntry = 0.0;
    pointGlobalSpan = 0.0;
    pointWorldLength = 0.0;
    pointGridEntry = 1.0;
    pointGridExit = 0.0;
    pointSourceBreakCount = 0;
    int pointLastFace = 0;
    if (PointTraceControl.w > 0.5 && PointPositionRadius.w > 0.0) {
        float sphereEntry;
        float sphereExit;
        if (pointIntersectSphere(
                rayOrigin,
                worldDirection,
                PointPositionRadius.xyz,
                PointPositionRadius.w,
                sphereEntry,
                sphereExit)) {
            float pointStartDistance = max(startDistance, max(sphereEntry, 0.0));
            float pointEndDistance = min(endDistance, min(sphereExit, sceneDistance));
            if (pointEndDistance > pointStartDistance) {
                pointGlobalEntry = clamp(
                        (pointStartDistance - startDistance) / max(worldLength, 1.0e-8),
                        0.0,
                        1.0);
                pointWorldLength = pointEndDistance - pointStartDistance;
                pointGlobalSpan = pointWorldLength / max(worldLength, 1.0e-8);
                pointWorldStart = rayOrigin + worldDirection * pointStartDistance;
                pointWorldDelta = worldDirection * pointWorldLength;
                pointRelativeStart = pointWorldStart - PointPositionRadius.xyz;
                pointRelativeDelta = pointWorldDelta;
                float pointClosestParameter = clamp(
                        -dot(pointRelativeStart, pointRelativeDelta)
                                / max(dot(pointRelativeDelta, pointRelativeDelta), 1.0e-8),
                        0.0,
                        1.0);
                float pointClosestNormalizedRadius = length(
                        pointRelativeStart + pointRelativeDelta * pointClosestParameter)
                        / max(PointPositionRadius.w, 1.0e-6);
                pointExtinctionSupport = 1.0 - smoothstep(
                        clamp(1.0 - PointOpticsControl.y, 0.0, 1.0),
                        1.0,
                        pointClosestNormalizedRadius);
                pointLocalStart = localOrigin + localDirection * pointStartDistance;
                pointLocalDelta = localDirection * pointWorldLength;
                pointInverseVolumeHalfSize = inverseHalfSize;
                vec3 pointGridStart =
                        (VolumeToGrid * vec4(pointLocalStart, 1.0)).xyz;
                vec3 pointGridDelta =
                        (VolumeToGrid * vec4(pointLocalDelta, 0.0)).xyz;
                if (GridOcclusionControl.y > 0.5
                        && !clipGridRegion(
                                pointGridStart,
                                pointGridDelta,
                                GridOcclusionControl.x,
                                pointGridEntry,
                                pointGridExit)) {
                    pointGridEntry = 1.0;
                    pointGridExit = 0.0;
                }
                float inversePointSegmentCount = 1.0 / float(POINT_SOURCE_SEGMENT_COUNT);
                for (int index = 0; index <= POINT_SOURCE_SEGMENT_COUNT; ++index) {
                    pointInsertSourceBreak(float(index) * inversePointSegmentCount);
                }
                if (pointGridExit > pointGridEntry) {
                    // 将精确网格交点加入 prefix 断点
                    pointInsertSourceBreak(pointGridEntry);
                    pointInsertSourceBreak(pointGridExit);
                }
                pointSortSourceBreaks();
                pointSourcePrefix[0] = 0.0;
                for (int index = 0; index < MAX_POINT_SOURCE_BREAKS - 1; ++index) {
                    if (index + 1 >= pointSourceBreakCount) break;
                    float segmentStart = pointSourceBreaks[index];
                    float segmentEnd = pointSourceBreaks[index + 1];
                    pointSourcePrefix[index + 1] = pointSourcePrefix[index]
                            + integratePointSourceSubinterval(segmentStart, segmentEnd);
                }
                pointTotalSourceIntegral = pointSourcePrefix[pointSourceBreakCount - 1];
                if (pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
                    pointBuildFaceSplits();
                    for (int segment = 0; segment < MAX_POINT_FACE_SPLITS - 1; ++segment) {
                        if (segment + 1 >= pointFaceSplitCount) break;
                        float segmentStart = pointFaceSplits[segment];
                        float segmentEnd = pointFaceSplits[segment + 1];
                        if (segmentEnd <= segmentStart + PointTraceControl.y) continue;
                        pointLastFace = pointDominantFace(
                                pointRelativeStart
                                        + pointRelativeDelta * ((segmentStart + segmentEnd) * 0.5));
                        pointTraceFace(pointLastFace, segmentStart, segmentEnd);
                    }
                    pointShadowedSourceIntegral = clamp(
                            pointShadowedSourceIntegral, 0.0, pointTotalSourceIntegral);
                    pointLitSourceIntegral = max(
                            pointTotalSourceIntegral - pointShadowedSourceIntegral,
                            0.0);
                }
            }
        }
    }

    shadowOpticalDepth = clamp(shadowOpticalDepth, 0.0, totalOpticalDepth);
    float pointDisplayShadowedSourceIntegral = min(
            pointTotalSourceIntegral,
            pointShadowedSourceIntegral * SoulShadowAppearanceControl.z);
    float pointDisplayShadowRatio = pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL
            ? clamp(
                    pointDisplayShadowedSourceIntegral / pointTotalSourceIntegral,
                    0.0,
                    1.0)
            : 0.0;
    float pointRadianceVisibility = max(
            1.0 - SoulShadowAppearanceControl.x * pointDisplayShadowRatio,
            SoulShadowAppearanceControl.y);
    pointLitSourceIntegral = pointTotalSourceIntegral * pointRadianceVisibility;
    float displayShadowOpticalDepth = min(
            totalOpticalDepth,
            shadowOpticalDepth * ShadowAppearanceControl.x);
    float shadowRatio = clamp(
            displayShadowOpticalDepth / max(totalOpticalDepth, 1.0e-7),
            0.0,
            1.0);
    float litRatio = 1.0 - shadowRatio;
    float displayOpticalDepth = totalOpticalDepth
            + displayShadowOpticalDepth * ShadowAppearanceControl.y;
    float opacity = 1.0 - exp(-displayOpticalDepth);
    float soulShadowOpticalDepth = totalOpticalDepth
            * pointDisplayShadowRatio
            * pointExtinctionSupport
            * SoulShadowAppearanceControl.w;
    float soulShadowOpacity = 1.0 - exp(-soulShadowOpticalDepth);
    float physicalOpacity = 1.0 - exp(-totalOpticalDepth);
    float reducedScatterContribution = 0.0;
    if (scatterBoundaryExit > scatterBoundaryEntry) {
        // 网格下方的局部 source term 先乘前方介质透射，再合入整条视线。
        float frontOpticalDepth = opticalDepthAt(scatterBoundaryEntry);
        float reducedOpticalDepth = max(
                opticalDepthAt(scatterBoundaryExit) - frontOpticalDepth,
                0.0);
        reducedScatterContribution = exp(-frontOpticalDepth)
                * (1.0 - exp(-reducedOpticalDepth));
    }
    float gridScatterTransmission = 1.0
            - (1.0 - GridOcclusionControl.z)
                    * reducedScatterContribution / max(physicalOpacity, 1.0e-7);
    gridScatterTransmission = clamp(gridScatterTransmission, GridOcclusionControl.z, 1.0);
    vec3 mediumColor = BaseFogColor.rgb
            + SunScatterColor.rgb * litRatio * VolumeDensityScatterShadow.z * gridScatterTransmission;
    mediumColor *= 1.0 - VolumeDensityScatterShadow.w * shadowRatio;
    mediumColor = max(mediumColor, BaseFogColor.rgb * VisitLimitsFogControl.z);
    float globalOpacity = GridOcclusionControl.w > 0.5 ? opacity : 0.0;
    float volumeOpacity = 1.0
            - (1.0 - globalOpacity) * (1.0 - soulShadowOpacity);
    vec3 globalColor = toneMapLuminance(mediumColor, ToneMappingControl);
    // Soul 辐亮度只来自点光单次散射积分；预乘 RGB 与共享介质 Alpha 分别累积。
    vec3 soulRadiance = SoulColorGridTransmission.rgb
            * PointTraceControl.z
            * pointLitSourceIntegral;
    soulRadiance = softClipRadiance(
            soulRadiance,
            PointOpticsControl.w);
    soulRadiance = toneMapLuminance(
            soulRadiance,
            SoulToneMappingControl);
    vec3 volumePremultipliedColor = globalColor * globalOpacity
            + BaseFogColor.rgb * soulShadowOpacity * (1.0 - globalOpacity);
    vec4 volumeColor = vec4(
            volumePremultipliedColor + soulRadiance,
            volumeOpacity);

    int debugMode = int(RoiSizeLevelDebug.w + 0.5);
    int pointDebugMode = int(PointVisitDebug.w + 0.5);
    if (debugMode == 1) {
        vec2 minimumUv = ShadowMapSizeRoiOrigin.zw / ShadowMapSizeRoiOrigin.xy;
        vec2 maximumUv = (ShadowMapSizeRoiOrigin.zw + RoiSizeLevelDebug.xy) / ShadowMapSizeRoiOrigin.xy;
        bool inside = all(greaterThanEqual((uvStart + projectedEnd.xy) * 0.5, minimumUv))
                && all(lessThanEqual((uvStart + projectedEnd.xy) * 0.5, maximumUv));
        fragColor = vec4(inside ? vec3(0.1, 0.8, 0.2) : vec3(0.8, 0.1, 0.1), 1.0);
    } else if (debugMode == 2) {
        float normalizedLevel = classifiedLevel < 0 ? 0.0
                : (float(classifiedLevel) + 1.0) / max(RoiSizeLevelDebug.z + 1.0, 1.0);
        fragColor = vec4(classifiedLevel < 0 ? vec3(0.95, 0.25, 0.1)
                : vec3(normalizedLevel, 0.2, 1.0 - normalizedLevel), 1.0);
    } else if (debugMode == 3) {
        float value = float(hierarchyVisits) / max(VisitLimitsFogControl.x, 1.0);
        fragColor = vec4(value, value, value, 1.0);
    } else if (debugMode == 4) {
        float value = float(leafVisits) / max(VisitLimitsFogControl.y, 1.0);
        fragColor = vec4(value, value, value, 1.0);
    } else if (debugMode == 5) {
        fragColor = vec4(overflow ? vec3(1.0, 0.0, 0.8) : vec3(0.0), 1.0);
    } else if (debugMode == 6) {
        fragColor = vec4(vec3(1.0 - exp(-totalOpticalDepth)), 1.0);
    } else if (debugMode == 7) {
        fragColor = vec4(vec3(1.0 - exp(-shadowOpticalDepth)), 1.0);
    } else if (debugMode == 8) {
        fragColor = vec4(vec3(shadowRatio), 1.0);
    } else if (pointDebugMode == 1 && pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
        vec3 pointDebugColor[6] = vec3[6](
                vec3(1.0, 0.2, 0.2), vec3(0.5, 0.0, 0.0),
                vec3(0.2, 1.0, 0.2), vec3(0.0, 0.5, 0.0),
                vec3(0.2, 0.4, 1.0), vec3(0.0, 0.1, 0.5));
        fragColor = vec4(pointDebugColor[pointLastFace], 1.0);
    } else if (pointDebugMode == 2 && pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
        float value = float(pointHierarchyVisits) / max(PointVisitDebug.y, 1.0);
        fragColor = vec4(vec3(value), 1.0);
    } else if (pointDebugMode == 3 && pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
        float value = float(pointRawTexelVisits) / max(PointVisitDebug.z, 1.0);
        fragColor = vec4(vec3(value), 1.0);
    } else if (pointDebugMode == 4 && pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
        fragColor = vec4(
                pointTraversalOverflow ? vec3(1.0, 0.0, 0.8) : vec3(0.0),
                1.0);
    } else if (pointDebugMode == 5 && pointTotalSourceIntegral > MIN_POINT_SOURCE_INTEGRAL) {
        float pointShadowRatio = pointShadowedSourceIntegral
                / max(pointTotalSourceIntegral, MIN_POINT_SOURCE_INTEGRAL);
        fragColor = vec4(vec3(pointShadowRatio), 1.0);
    } else {
        fragColor = volumeColor;
        // RGB 扰动直接进入预乘合成；Alpha 保持物理透射率。
        fragColor.rgb = minetaleApplyQuantizationDither(
                fragColor.rgb,
                minetaleInterleavedGradientNoise(gl_FragCoord.xy),
                1.0 / 255.0,
                VolumetricOutputDither.x
        );
    }
}
