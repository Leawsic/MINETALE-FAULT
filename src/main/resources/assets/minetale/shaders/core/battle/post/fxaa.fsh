#version 330

uniform sampler2D Sampler0;

in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float FXAA_EDGE_THRESHOLD     = 1.0 / 8.0;
const float FXAA_EDGE_THRESHOLD_MIN = 1.0 / 16.0;

const float FXAA_SUBPIX_TRIM        = 1.0 / 4.0;
const float FXAA_SUBPIX_CAP         = 3.0 / 4.0;
const float FXAA_SUBPIX_TRIM_SCALE  = 1.0 / (1.0 - FXAA_SUBPIX_TRIM);

const int   FXAA_SEARCH_STEPS       = 12;
const float FXAA_SEARCH_THRESHOLD   = 1.0 / 4.0;

// 边缘搜索与阈值参数遵循 Timothy Lottes 的 FXAA white paper。

float fxaaLuma(vec4 c) {
    return dot(c.rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));

    vec4 cM  = texture(Sampler0, texCoord);
    vec4 cN  = texture(Sampler0, texCoord + vec2( 0.0,      texel.y));
    vec4 cS  = texture(Sampler0, texCoord + vec2( 0.0,     -texel.y));
    vec4 cW  = texture(Sampler0, texCoord + vec2(-texel.x,  0.0));
    vec4 cE  = texture(Sampler0, texCoord + vec2( texel.x,  0.0));

    vec4 cNW = texture(Sampler0, texCoord + vec2(-texel.x,  texel.y));
    vec4 cNE = texture(Sampler0, texCoord + vec2( texel.x,  texel.y));
    vec4 cSW = texture(Sampler0, texCoord + vec2(-texel.x, -texel.y));
    vec4 cSE = texture(Sampler0, texCoord + vec2( texel.x, -texel.y));

    float lumaM  = fxaaLuma(cM);
    float lumaN  = fxaaLuma(cN);
    float lumaS  = fxaaLuma(cS);
    float lumaW  = fxaaLuma(cW);
    float lumaE  = fxaaLuma(cE);
    float lumaNW = fxaaLuma(cNW);
    float lumaNE = fxaaLuma(cNE);
    float lumaSW = fxaaLuma(cSW);
    float lumaSE = fxaaLuma(cSE);

    float rangeMin = min(lumaM, min(min(lumaN, lumaS), min(lumaW, lumaE)));
    float rangeMax = max(lumaM, max(max(lumaN, lumaS), max(lumaW, lumaE)));
    float range = rangeMax - rangeMin;

    float aMin = min(cM.a, min(min(cN.a, cS.a), min(cW.a, cE.a)));
    float aMax = max(cM.a, max(max(cN.a, cS.a), max(cW.a, cE.a)));
    float aRange = aMax - aMin;

    float edgeRange = max(range, aRange);
    if (edgeRange < max(FXAA_EDGE_THRESHOLD_MIN, rangeMax * FXAA_EDGE_THRESHOLD)) {
        fragColor = cM;
        return;
    }

    vec4 cL = (cM + cN + cS + cW + cE + cNW + cNE + cSW + cSE) * (1.0 / 9.0);

    float lumaL = (lumaN + lumaS + lumaW + lumaE) * 0.25;
    float safeRange = max(range, 1e-4);

    float blendL = max(0.0, (abs(lumaL - lumaM) / safeRange) - FXAA_SUBPIX_TRIM) * FXAA_SUBPIX_TRIM_SCALE;
    blendL = min(FXAA_SUBPIX_CAP, blendL);

    float edgeVert =
        abs((0.25 * lumaNW) + (-0.5 * lumaN) + (0.25 * lumaNE)) +
        abs((0.50 * lumaW ) + (-1.0 * lumaM) + (0.50 * lumaE )) +
        abs((0.25 * lumaSW) + (-0.5 * lumaS) + (0.25 * lumaSE));

    float edgeHorz =
        abs((0.25 * lumaNW) + (-0.5 * lumaW) + (0.25 * lumaSW)) +
        abs((0.50 * lumaN ) + (-1.0 * lumaM) + (0.50 * lumaS )) +
        abs((0.25 * lumaNE) + (-0.5 * lumaE) + (0.25 * lumaSE));

    bool horzSpan = edgeHorz >= edgeVert;

    float gradientNeg;
    float gradientPos;
    float lumaNeg;
    float lumaPos;
    vec2 perpStep;
    vec2 edgeStep;

    if (horzSpan) {
        gradientNeg = abs(lumaN - lumaM);
        gradientPos = abs(lumaS - lumaM);
        lumaNeg = lumaN;
        lumaPos = lumaS;
        perpStep = vec2(0.0, texel.y);
        edgeStep = vec2(texel.x, 0.0);
    } else {
        gradientNeg = abs(lumaW - lumaM);
        gradientPos = abs(lumaE - lumaM);
        lumaNeg = lumaW;
        lumaPos = lumaE;
        perpStep = vec2(texel.x, 0.0);
        edgeStep = vec2(0.0, texel.y);
    }

    bool usePos = gradientPos > gradientNeg;
    float gradient = max(gradientNeg, gradientPos);

    if (gradient < 1e-5) {
        fragColor = mix(cM, cL, blendL);
        return;
    }

    float lumaPairAvg = 0.5 * (lumaM + (usePos ? lumaPos : lumaNeg));
    vec2 pairCenter = texCoord + (usePos ? perpStep : -perpStep) * 0.5;

    vec2 posN = pairCenter - edgeStep;
    vec2 posP = pairCenter + edgeStep;

    float lumaEndN = lumaPairAvg;
    float lumaEndP = lumaPairAvg;
    bool doneN = false;
    bool doneP = false;
    float searchThreshold = gradient * FXAA_SEARCH_THRESHOLD;

    for (int i = 0; i < FXAA_SEARCH_STEPS; ++i) {
        if (!doneN) {
            lumaEndN = fxaaLuma(texture(Sampler0, posN));
            doneN = abs(lumaEndN - lumaPairAvg) >= searchThreshold;
            if (!doneN) {
                posN -= edgeStep;
            }
        }

        if (!doneP) {
            lumaEndP = fxaaLuma(texture(Sampler0, posP));
            doneP = abs(lumaEndP - lumaPairAvg) >= searchThreshold;
            if (!doneP) {
                posP += edgeStep;
            }
        }

        if (doneN && doneP) {
            break;
        }
    }

    float distN = horzSpan ? (pairCenter.x - posN.x) : (pairCenter.y - posN.y);
    float distP = horzSpan ? (posP.x - pairCenter.x) : (posP.y - pairCenter.y);
    float spanLength = max(distN + distP, 1e-4);
    float distToNearestEnd = min(distN, distP);

    bool directionN = distN < distP;
    float lumaEnd = directionN ? lumaEndN : lumaEndP;
    bool goodSpan = ((lumaM - lumaPairAvg) < 0.0) != ((lumaEnd - lumaPairAvg) < 0.0);

    float edgeOffset = goodSpan ? (-distToNearestEnd / spanLength + 0.5) : 0.0;

    vec2 finalOffset = horzSpan
        ? vec2(0.0, (usePos ? 1.0 : -1.0) * edgeOffset * texel.y)
        : vec2((usePos ? 1.0 : -1.0) * edgeOffset * texel.x, 0.0);

    vec4 aaColor = texture(Sampler0, texCoord + finalOffset);
    fragColor = mix(aaColor, cL, blendL);
}
