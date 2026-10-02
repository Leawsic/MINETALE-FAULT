# Structure Asset Catalog

Catalog 在服务端资源重载时读取：

```text
data/<namespace>/structure_assets/<path>.json
```

文件路径形成 Asset ID；`template` 指向 `data/<namespace>/structure/<path>.nbt`。

## 当前格式

```json
{
  "template": "minetale:snowtown/example",
  "role": "house",
  "category": "minetale:snowtown",
  "weight": 1,
  "tags": ["minetale:small"],
  "footprint": {
    "width": 9,
    "height": 7,
    "depth": 11,
    "foundation_depth": 1,
    "max_slope": 1,
    "allow_liquid": false,
    "require_surface": true
  },
  "placement": {
    "can_rotate": true,
    "can_mirror": false
  },
  "scan_markers": true
}
```

`template` 和非空 `role` 必填。其余默认值为：

- `category = minetale:generic`
- `weight = 1`，且不得小于 1
- `tags = []`
- `footprint = StructureAssetFootprint.DEFAULT`
- `can_rotate = true`
- `can_mirror = false`
- `scan_markers = true`

当 `scan_markers` 为 true 时，Reload Listener 会读取 NBT、执行 DataFix、扫描 Template Marker 并把加载错误附加到定义。Catalog 仍会保留无效定义供诊断，但调用方应通过 `isValid()` 或 `StructureAssetFilters` 排除它们。

常用诊断命令：

```text
/minetale asset list
/minetale asset inspect <asset>
/minetale asset validate <asset>
/minetale asset validate_all
/minetale asset scan_template <template>
```

Catalog 每次替换都会递增 `revision`；Snowtown Plan Cache 把该 Revision 放入 Key，因此资源重载后不会继续复用旧规划。
