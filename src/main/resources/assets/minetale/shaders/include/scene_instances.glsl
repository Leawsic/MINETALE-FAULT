layout(std430) readonly buffer MinetaleSceneInstances { vec4 minetale_SceneRows[]; };
uniform int minetale_SceneInstancing;
uniform int minetale_SceneBase;

void minetale_sceneTransform(inout vec3 position, inout vec3 normal, inout vec4 tangent) {
    if (minetale_SceneInstancing == 0) return;
    int row = (minetale_SceneBase + gl_InstanceID) * 7;
    vec4 p = vec4(position, 1.0);
    position = vec3(dot(minetale_SceneRows[row], p), dot(minetale_SceneRows[row+1], p), dot(minetale_SceneRows[row+2], p));
    normal = normalize(vec3(dot(minetale_SceneRows[row+3].xyz, normal), dot(minetale_SceneRows[row+4].xyz, normal), dot(minetale_SceneRows[row+5].xyz, normal)));
    vec4 t = vec4(tangent.xyz, 0.0);
    tangent.xyz = vec3(dot(minetale_SceneRows[row], t), dot(minetale_SceneRows[row+1], t), dot(minetale_SceneRows[row+2], t));
    tangent.xyz = normalize(tangent.xyz - normal * dot(tangent.xyz, normal));
    tangent.w *= minetale_SceneRows[row+6].x;
}
