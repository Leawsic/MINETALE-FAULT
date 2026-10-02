#version 330

layout(std140) uniform DrawUniform {
    mat4 View;
    mat4 Projection;
    mat4 Model;
    vec4 Color;
    vec4 UvRect;
    vec4 Params;
    vec4 StartPoint;
    vec4 EndPoint;
};

out vec4 vertexColor;

void main() {
    vec4 clipStart = Projection * View * StartPoint;
    vec4 clipEnd = Projection * View * EndPoint;
    vec2 ndcA = clipStart.xy / clipStart.w;
    vec2 ndcB = clipEnd.xy / clipEnd.w;
    vec2 viewport = max(Params.xy, vec2(1.0));
    vec2 pixelDelta = (ndcB - ndcA) * viewport * 0.5;
    vec2 pixelDirection = length(pixelDelta) > 0.000001
        ? normalize(pixelDelta)
        : vec2(1.0, 0.0);
    vec2 pixelPerpendicular = vec2(-pixelDirection.y, pixelDirection.x);
    // Params.z 表示完整像素线宽；NDC 两侧各偏移 width/viewport。
    vec2 ndcOffset = pixelPerpendicular * Params.z / viewport;
    int endpoint = gl_VertexID >> 1;
    int side = (gl_VertexID & 1) * 2 - 1;
    vec4 clipPosition = mix(clipStart, clipEnd, endpoint);
    clipPosition.xy += float(side) * ndcOffset * clipPosition.w;
    gl_Position = clipPosition;
    vertexColor = Color;
}
