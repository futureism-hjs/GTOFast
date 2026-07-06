# GTO Fast

为 GTOCore 提供可配置配方速度倍率的 Minecraft 1.20.1 Forge 附属模组。

灵感来自 GTOCutCorners，但支持自定义倍率（而非固定 1 tick）。

## 功能

- **配方速度倍率**：所有 GT 和原版配方时间乘以 `durationFactor`
- **电炉加速修复**：通过 Java Proxy 拦截 GTCEu 超频机制（`RecipeModifier`）
- **发电机排除**：自动跳过 19 种发电机类型的配方
- **运行时扫描**：守护线程持续修正被超频重置的配方时间

## 依赖

| 依赖 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.20+ |
| GTCEu | 1.20.1-1.8.0 |
| GTOCore | 0.5.6-alpha |
| JDK | 17+ |

## 配置

首次启动后自动生成 `config/gtofast.json`：

```json
{
  "durationFactor": 1.0
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `durationFactor` | double | 配方时间倍率。`1.0` = 不变，`0.05` = 20 倍速，`0.0` = 1 tick |

## 构建

```bash
./gradlew jar
```

## 许可

LGPL-3.0
