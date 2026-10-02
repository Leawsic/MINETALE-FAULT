#version 330

uniform sampler2D SourceSampler;
in vec2 texCoord;
out vec4 fragColor;

vec4 sampleSafe(vec2 uv) {
    vec2 halfTexel = 0.5 / vec2(textureSize(SourceSampler, 0));
    return texture(SourceSampler, clamp(uv, halfTexel, 1.0 - halfTexel));
}

void main() {
#ifdef DOWNSAMPLE
    // 每个 4×4 区域完整取平均，细束也能进入外晕源；边缘尺寸不足时重复最末纹素。
    ivec2 size = textureSize(SourceSampler, 0);
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    vec4 sum = vec4(0.0);
    for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) {
        vec4 core = texelFetch(SourceSampler, min(base + ivec2(x,y), size - 1), 0);
        sum += vec4(core.rgb * core.a, 0.0);
    }
    fragColor = sum / 16.0;
#else
    // 四分之一尺寸上分离横／纵模糊，各五次线性采样
#ifdef HORIZONTAL
    vec2 stepUV = vec2(1.0 / float(textureSize(SourceSampler, 0).x), 0.0);
#else
    vec2 stepUV = vec2(0.0, 1.0 / float(textureSize(SourceSampler, 0).y));
#endif
#ifdef LENS_FILTER
    // 连续相邻采样的核限制场梯度
    // 不能复用辉光的稀疏宽核，否则强叠加可能折叠画面。
    vec2 sum = vec2(0.0);
    float total = 0.0;
    for (int i = -6; i <= 6; ++i) {
        float weight = exp(-float(i*i)/18.0);
        vec4 sampleValue = sampleSafe(texCoord+stepUV*float(i));
#ifdef HORIZONTAL
        vec2 value = sampleValue.rg-sampleValue.ba;
#else
        vec2 value = (vec2(dot(sampleValue.rg,vec2(65280.0,255.0)),
                          dot(sampleValue.ba,vec2(65280.0,255.0)))-32768.0)/32767.0;
#endif
        sum += value*weight;
        total += weight;
    }
    // 高低字节的解码是线性的，双线性采样跨进位连续；32768 精确表示零。
    vec2 encoded = round(clamp(sum/total,-1.0,1.0)*32767.0+32768.0);
    fragColor = vec4(floor(encoded.x/256.0),mod(encoded.x,256.0),
                     floor(encoded.y/256.0),mod(encoded.y,256.0))/255.0;
#else
    // 扩大已有模糊核的跨度，让外晕离开实体轮廓；采样数、目标尺寸和 pass 数保持不变。
    stepUV *= 1.8;
    fragColor = sampleSafe(texCoord) * 0.227027;
    fragColor += (sampleSafe(texCoord + stepUV * 1.384615) + sampleSafe(texCoord - stepUV * 1.384615)) * 0.316216;
    fragColor += (sampleSafe(texCoord + stepUV * 3.230769) + sampleSafe(texCoord - stepUV * 3.230769)) * 0.070270;
#endif
#endif
}
