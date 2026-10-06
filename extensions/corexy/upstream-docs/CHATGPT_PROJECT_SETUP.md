# ChatGPT 项目“CoreXY补偿”组织建议

## 建议结构

项目名称：`CoreXY补偿`

建议把资料按用途分为四组：

1. 当前事实：`PROJECT_HANDOFF.md`、`USER_GUIDE.md`、正式发布 `MANIFEST.md` 和 `TEST_RECORD.md`。
2. 设计依据：`DECISIONS.md`、`ARCHITECTURE.md`、`STATE_VS_ABSOLUTE_RESIDUAL.md`、`UI_LOCALIZATION.md`。
3. 新机器通用化：`NEW_MACHINE_ONBOARDING.md`、候选版 `INSTALL.md` 和 `TEST_RECORD.md`。
4. 维护操作：`TESTING.md`、`MIGRATION.md`、正式发布 `RESTORE.md`。
5. 后续开发：`ROADMAP.md` 和需要分析的具体报告/截图。

源码文件数量较多，建议平时上传正式发布中的 `source.zip` 作为精确版本存档；定位某一问题时，再附上对应脚本、报告和截图。机器运行数据包含本机路径、坐标和测量历史，不建议整包长期上传；只选择与问题相关且已检查敏感信息的报告。

## 可放入项目说明的文本

```text
本项目维护 OpenPnP 的外置 CoreXY A/B 固定二维向量补偿。
默认使用中文回答，UI 和用户报告同时考虑中文与 English。
现有正式流程是：开始新标定 -> 同期未补偿采集 -> 生成候选 -> 选择补偿比例 -> 应用补偿并自动 Disable -> 重新 Enable/Home -> 健康检查或空载往返检查；另有停用补偿。不要重新引入临时补偿/正式补偿双流程。
新机器包必须从禁用、全零、不含真实轴 ID 的模板开始；Startup 只自动识别顶部相机 X/Y 轴，不 Enable、不 Home、不运动。不得把任一机器的 Primary、Session、A/B 参数或 machine.xml 当作通用默认值。
保持外置边界：不修改 OpenPnP 源码、安装 JAR、普通 G-code，不发送 G92。
固定 A/B 向量只处理稳定可重复的方向状态误差；绝对落点误差、机械随机性和二维位置误差场必须分开讨论。
改代码前先阅读 PROJECT_HANDOFF.md、DECISIONS.md 和相关源文件，优先遵循现有状态机和界面约定。
不得覆盖现场 corexy.properties；部署使用 install-scripts.ps1，并在前后核对正式配置 SHA-256。
OpenPnP 使用 Unicode 字符串编译 Jython 脚本，禁止加入 Python encoding declaration；发布前对全部 .py/.pytxt 做同方式编译检查。
应用或停用必须等待静止、自动 Disable、清空 A/B 状态且不做抵消运动，之后提示重新 Enable/Home。
任何涉及运动、Home、坐标回报、配置写入或状态切换的修改，都必须说明安全影响并扩大回归范围。
```

## 建议的项目对话分类

- `正式版维护`：只处理当前冻结版本缺陷和兼容性。
- `UI与双语`：界面、报告、术语和可用性。
- `残差与二维位置模型`：未来算法研究，不直接修改当前固定向量正式版。
- `真机测试记录`：按日期保存截图、正式配置哈希、报告和机械状态。
- `新机器引导`：记录独立机器的首次安装、轴自动识别、首次标定和健康检查验收。

## 每次维护对话应提供

- 使用的正式发布目录或 SHA-256 清单。
- OpenPnP 版本和 Agent JAR 哈希。
- 当前 `enabled`、补偿比例、A/B 阈值和向量。
- 机器是否 Enabled/Homed、问题发生前后的操作步骤。
- 对应 Session、最新报告和截图。
- 是否改过机械结构、相机标定、原生 backlash 或 `machine.xml`。

ChatGPT 项目用于集中上下文和资料，不应成为唯一备份。正式发布目录及机器配置快照仍应保存在本地并另外备份。
