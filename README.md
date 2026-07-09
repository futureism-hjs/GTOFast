# GTO Fast

为 GTOCore 提供可配置配方速度倍率的 Minecraft 1.20.1 Forge 附属模组。

灵感来自 GTOCutCorners，但支持自定义倍率（而非固定 1 tick）。

## 功能

- **配方速度倍率**：所有 GT 和原版配方时间乘以 `durationFactor`
- **电炉加速修复**：通过 Java Proxy 拦截 GTCEu 超频机制（`RecipeModifier`）
- **发电机排除**：自动跳过 19 种发电机类型的配方
- **运行时扫描**：守护线程持续修正被超频重置的配方时间

## 警告

> **AI 生成声明**
>
> 本模组全部代码由 AI 生成，未经人工审查。代码中可能包含错误、不合理的实现或安全隐患。使用者需自行承担风险。

> **丢档风险**
>
> 本模组通过 JVMTI 字节码注入和 JNI 直接修改游戏运行时数据。以下情况可能导致存档损坏：
> - Minecraft / Forge / GTOCore / GTCEu 更新后未重新测试
> - 与其他修改配方逻辑或机器内部行为的模组同时使用
> - 在游戏运行中修改配置文件后执行 `/reload`
>
> **使用前请务必备份存档。**

> **未知错误**
>
> JVMTI 字节码补丁依赖 GTCEu 内部实现细节（`RecipeModifier.overclocking()` 的字节码布局）。GTCEu 版本更新后补丁位置可能失效，导致：
> - 电炉及其他机器的配方不受加速
> - JVM 崩溃（`EXCEPTION_ACCESS_VIOLATION`）
> - 部分机器无法正常工作或产出异常
>
> 如遇问题，请删除模组并恢复备份。

## 依赖

| 依赖 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.20+ |
| GTCEu | 1.20.1-1.8.0 |
| GTOCore | 0.5.6-beta |
| JDK | 21 |

## 版本说明

自 v1.20.1-v1.3-alpha-for-gtocore-0.5.6-beta 起，版本号采用 Forge 兼容格式（以数字开头），修复了先前版本因版本号格式不合法导致模组被 Forge 拒绝加载的问题。

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
