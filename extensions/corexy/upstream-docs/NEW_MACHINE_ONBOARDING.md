# 新机器通用安装与初始化

## 用户前提

新用户已经完成官方 OpenPnP 安装、驱动器和 X/Y/Z 轴配置、Home、软限位、顶部相机与 UPP 标定、机械检查以及自己的 `.openpnp2` 备份。CoreXY 包不要求重复这些步骤。

## 包的边界

新机器包只包含：

- 已验证的外置 Agent JAR。
- Startup 事件和 Tuner/工作流脚本。
- 不含真实轴 ID、坐标和补偿量的全零模板。
- 通用标定策略、安装器、使用说明和哈希清单。

包内不包含任何开发机的 `machine.xml`、Primary 坐标、Session、候选、诊断、报告、轴 ID 或 A/B 参数。

## 安装和自动初始化

1. 用户完全关闭 OpenPnP，解压新机器安装包并运行 `Install-CoreXY-New-Machine.ps1`。
2. 安装器校验 payload SHA-256，确认 `.openpnp2/machine.xml` 已存在，并自动保存一份额外备份。
3. 检测到已有非 onboarding CoreXY 配置时拒绝覆盖。
4. 用户正常启动 OpenPnP。
5. Startup 仅在 `onboarding.state=uninitialized` 时读取顶部相机 X/Y 轴。
6. X/Y 必须是不同的 `ReferenceControllerAxis` 且使用同一驱动器，否则 fail-closed。
7. 初始化写入轴 ID、OpenPnP 版本和时间，将状态改为 `ready-for-calibration`；补偿保持 `enabled=false`，全部向量为 0。
8. 初始化过程不 Enable、不 Home、不运动，也不修改 OpenPnP 原生 backlash。

## 首次标定

打开 Tuner 后，当前状态显示新机器已初始化。用户只需保存 Primary，随后按现有正式流程操作：

```text
Disable 且未 Home
-> 开始新标定
-> 自动关闭原生 X/Y backlash
-> Enable + Home
-> 采集未补偿基线
-> 生成候选
-> 应用补偿并自动 Disable
-> Enable + Home
-> 健康检查或空载往返检查
```

首份未补偿基线是在正式补偿保持停用时建立的，不需要也不允许借用旧机器的生产验收数据。该例外只适用于 `ready-for-calibration + bootstrap-new-machine` 的首次同期 Session；正式健康检查仍要求已经应用持久补偿。

第一次应用成功后，正式配置写入 `onboarding.state=calibrated`。后续安装器不会把它识别为新机器模板，也不会覆盖。

## 故障处理

- Startup 报 X/Y 轴类型或驱动器错误：回到 OpenPnP 机器配置检查顶部相机轴映射，不手工复制其他机器轴 ID。
- 安装器提示已有 CoreXY 配置：使用升级/维护流程，不使用新机器安装器强行覆盖。
- 首次标定按钮不可用：机器应处于 Disable 且未 Home，并确认状态页显示 `ready-for-calibration`。
- 任何自动运动前仍需按 Tuner 弹窗确认测试区域和 Safe Z 安全。
