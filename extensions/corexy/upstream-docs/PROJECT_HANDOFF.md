# CoreXY 补偿项目维护交接

## 项目状态

固定 A/B 向量 Tuner 的完整流程已于 2026-09-10 完成真机回归，用户确认运行正常并符合预期。当前阶段的目标是保持已验证流程稳定；二维位置误差模型作为独立后续研究，不直接叠加到固定向量逻辑。

`v3.1-new-machine-onboarding` 在 v3 稳定内核上增加通用首次安装：纯净模板、自动轴识别、零补偿初始化和无需 JDK/Maven 的安装包。新机器初始化不移动机器，也不改写已有 CoreXY 正式配置。

## 核心边界

- 使用官方 OpenPnP 和外置 Java Agent，不修改 OpenPnP 源码、安装 JAR 或普通 G-code。
- Startup 事件动态加载 `openpnp-corexy-agent.jar`，Agent 拦截运动规划入口。
- Tuner 负责测量、候选、可视化和受控配置切换，Java Agent 负责全局运动补偿。
- 正式配置、候选、Session、报告和备份都在用户脚本目录，不写入项目源码目录。
- 原生 X/Y backlash 在开始新标定时设为 `None`；当前不提供自动恢复。

## 当前产品流程

```text
Disable 且未 Home
  -> 开始新标定
  -> Enable + Home
  -> 未补偿固定网格采集
  -> 生成并自动填入候选
  -> 选择比例或人工调整
  -> 应用补偿（等待静止、自动 Disable、清空 A/B）
  -> Enable + Home
  -> 健康检查或空载往返检查
  -> 可重复检查、停用或调整后重新应用
```

不再区分“临时补偿”和“正式补偿”。用户只有“应用补偿”和“停用补偿”两个配置状态。

## 关键路径

- 源码根目录：`OPENPNP-COREXY/`
- Tuner 入口：`scripts/TornadoSMT/A_CoreXY_Repeatability_Tuner.py`
- UI 主体：`scripts/TornadoSMT/CoreXY_Repeatability_Tuner.pytxt`
- 当前工作流：`scripts/TornadoSMT/CoreXY_Agent_Workflow_UI.pytxt`
- UI 后端和使用说明：`scripts/TornadoSMT/CoreXY_Agent_Original_UI_Backend.pytxt`
- Agent 源码：`src/main/java/org/openpnp/corexy/agent/`
- 现场脚本：`.openpnp2/scripts/TornadoSMT/`
- 现场数据：`.openpnp2/scripts/TornadoSMT/OpenPnP-CoreXY/`
- 正式配置：`OpenPnP-CoreXY/config/corexy.properties`
- UI 语言：`OpenPnP-CoreXY/config/ui-language.properties`
- 部署：`tools/install-scripts.ps1`
- 新机器打包：`tools/build-new-machine-package.ps1`
- 新机器安装说明：`docs/NEW_MACHINE_ONBOARDING.md`

## 数据与判定

- 固定网格：距离 15/28/34/60/180/270 mm，速度 40/70/90%，每格 3 次。
- 默认补偿比例 100%，A/B 页面值写入时保留 3 位小数。
- 健康门限来自顶部相机 UPP 较大值，按 `0.001 mm/px` 取整：PASS 3 px、WARNING 6 px。
- 超过 PASS 线的最差同条件单元自动追加 7 次定向复测。
- 0.05 mm 圈是绝对落点视觉参考，不参与健康 PASS 判定。
- 固定向量优化相反方向状态差，不承诺消除绝对偏心或二维空间误差场。

## 发布和维护规则

1. 修改源文件，不直接把运行时脚本作为唯一源码。
2. 使用安装脚本部署；安装脚本必须保留现场 `corexy.properties` 和标定策略。
3. 脚本不得加入 `# -*- coding: utf-8 -*-` 等编码声明，因为 OpenPnP 会把 UTF-8 内容先转换为 Unicode 再编译。
4. 每次发布完成 Java 测试、全部脚本 Unicode 编译、源码/部署哈希对照和中英文 UI 回归。
5. 冻结前后记录正式配置 SHA-256，任何文档或归档动作都不得改变配置。
6. 新发布建立新目录，不覆盖旧版本；同时保留现场已测试 JAR 和机器数据快照。
7. 对配置写入、Home、运动、坐标回报或状态清零的修改必须扩大测试范围。
8. 新机器包不得包含开发机轴 ID、Primary 坐标、Session、候选、健康报告或补偿向量。

## 已知限制

- 当前模型只适合稳定、可复现的方向状态偏差。
- 不能自动判断或修复机械随机性、视觉漂移和完整二维位置误差场。
- 原生 backlash 无自动回滚；依赖操作前的 `machine.xml` 手工备份。
- 旧开发诊断脚本仍保留在源码中，但正式用户入口只有 Tuner。
- 现场数据含机器路径和测量历史，不应作为通用默认配置分发。

## 后续开发入口

优先级建议：保持正式冻结件不变；问题修复建立补丁版本；二维位置误差图和空间模型在独立发布线研究；获得足够位置采样、留出验证和机械稳定性证据后，再讨论与固定状态向量的组合方式。
