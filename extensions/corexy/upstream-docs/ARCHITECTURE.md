# Agent 架构

## 运行链路

1. 用户通过官方 `OpenPnP.exe` 或 `openpnp.bat` 启动 OpenPnP。
2. OpenPnP 脚本系统执行 `scripts/Events/Startup.00.CoreXYAgent.py`。
3. Jython 从用户脚本目录动态加载自包含 `openpnp-corexy-agent.jar`。
4. Byte Buddy Agent 拦截官方 `AbstractMotionPlanner` 的关键运动方法。
5. 参数从 `config/corexy.properties` 读取；`machine.xml` 不保存 Agent 私有字段。

不替换官方类文件、不修改官方 JAR、不要求专用启动器。

## 补偿模型

对每段 XY 逻辑运动计算：

```text
dA = dX + dY
dB = dX - dY
```

当 `|dA|` 或 `|dB|` 达到各自阈值时，对应状态切换为正向或负向。物理偏置为当前 A 状态与 B 状态二维偏置之和。例如：

```text
A+ = (+0.010, +0.020) mm
B+ = (+0.060, -0.100) mm
A+ 与 B+ 同时成立：(+0.070, -0.080) mm
```

这是传动状态对应的二维偏置，不是每次运动累加的位移。保持同一状态时，新移动沿用当前物理偏置；只有状态改变时才增加一次收尾修正运动。

## 拦截点

- `createBacklashCompensatedMotion`：读取逻辑位移、判断状态、规划状态修正。
- `waitForCompletion`：驱动完成后恢复 OpenPnP 逻辑坐标视图。
- `setGlobalOffsets`：坐标系重设后同步状态偏置。
- `home` / `unhome`：Home 全过程旁路补偿，并在进入和退出时清空 A/B 状态，防止归零或坐标同步运动被误判为传动状态切换。

## 安全边界

- 配置解析失败、版本不符、轴 ID 无效或 X/Y 不属于同一驱动器时，拒绝启用。
- 检测到修改版 OpenPnP 内嵌的 CoreXY API 时，拒绝与 Agent 叠加。
- `require-homed=true` 时，未 Home 不允许真实补偿。
- 主运动与补偿后的物理终点均检查 OpenPnP 软限位，并保留配置的安全余量。
- 干运行也检查假设补偿终点；任一 A/B 合成偏置超过
  `safety.max-offset.mm` 时配置加载失败。
- `dry-run=true` 时只计算和记录，不添加物理修正运动。
- 普通诊断配置重载仍要求未 Home 且没有活动偏置。
- Tuner 的“应用补偿”和“停用补偿”使用受控即时切换：先等待运动静止并自动 Disable，再写入配置、允许 Agent 清空非零 A/B 状态，且不执行抵消运动。切换后必须重新 Enable 和 Home。

`fail-closed=true` 是设计约束：无法证明当前环境兼容时，补偿保持关闭，不能带警告继续运行。

## 数据边界

- 正式运行参数：`config/corexy.properties`
- 诊断结果：`diagnostics/`
- 候选与验证：`trials/`
- 迁移和正式配置备份：`backups/`

Tuner 界面只负责诊断、拟合、可视化和受控写入配置；全局运动拦截始终由 Java Agent 完成。UI 语言偏好保存在 `config/ui-language.properties`，不得参与配置哈希、测量计算或补偿判定。
