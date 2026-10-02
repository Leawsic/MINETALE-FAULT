# Snowdin

Snowdin 是当前完整接入五阶段 Pipeline 的 Underground Region。

## 生成内容

- Stage 1：用 `SnowdinNaturalTerrainFactsKernel` 计算洞腔、地面、洞顶和台地事实并写入地形白板。
- Stage 2：生成 Stalactite、Pillar 等大型元素 Mask。
- Stage 3：按列事实替换材质并铺设可见雪面。
- Stage 4：`SnowtownGenerator` 规划并放置建筑、地基和道路。
- Stage 5：散布 Ice Lake、Snowtown Spruce、雪层和 Chunk Feature。

Snowtown 以 `SnowtownPlanningArea` 为确定性规划单位。规划只根据 Seed、设置、`RainbowCakeModel`、自然地形 Kernel 和当前 `StructureAssetCatalog.revision` 计算，不读取邻居 Chunk 的已生成方块。`SnowtownLotPlanner` 先装箱可用资产，再由 `SnowtownRoadPlanner` 生成道路；最终 `SnowtownGenerator` 只把与当前 Chunk 相交的结果写入世界。

`entity.monsternpc` 另有服务端人群协调、网络 Demand 和客户端 GPU Crowd 表现；外观定义来自 `content.entity.monster_npc`。

资产来源见 [项目借物表](../../../../../../../../../../THIRD_PARTY_NOTICES.md)。调试入口集中在 `SnowtownCommands`。
