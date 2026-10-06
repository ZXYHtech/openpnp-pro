# 迁移与回滚

## 为什么必须清理 machine.xml

当前修改版 OpenPnP 已把 `core-XY-compensation-*` 和 `cross-axis-compensation-*` 私有字段写入 `machine.xml`。官方 OpenPnP 使用严格 XML 映射，无法识别这些字段，直接启动可能导致配置读取失败。

Agent 的参数已经迁移到独立 `corexy.properties`。因此切换官方版前，需要删除 `machine.xml` 中仅属于修改版的 CoreXY 字段，并把 motion planner 类恢复为官方：

```text
org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner
```

该操作不会删除 OpenPnP 原有 X/Y 单轴 backlash 配置。

## 迁移步骤

1. 完全关闭修改版 OpenPnP、官方 OpenPnP 和所有相关 Jython 进程。
2. 确认 `.openpnp2` 已有外部 Agent 文件和 `corexy.properties`。
3. 执行：

```powershell
cd D:\Vscode_openpnp_test_zip-260825\OPENPNP-COREXY
.\tools\prepare-official-machine-xml.ps1
```

4. 工具先在 `OpenPnP-CoreXY/backups/` 创建带时间戳的完整 `machine.xml` 备份，再写入官方兼容配置。
5. 工具同时停用依赖修改版 planner API 的旧 Home 恢复和作业保护事件，并将原文件移入带时间戳的 `legacy-events-*` 备份目录。
6. 第一次仅启动官方 OpenPnP，不连接或移动机器，运行脚本菜单中的 `TornadoSMT/CoreXY_Agent_Status.py`。
7. 状态确认通过后，按 `TESTING.md` 进行干运行和真机验证。

## 防止双重补偿

不要在包含内嵌 CoreXY 修改的 OpenPnP 源码版本中启用外部 Agent。Agent 会主动检测这类 API 并拒绝配置，但迁移时仍应明确使用官方安装版。

## 回滚

1. 关闭 OpenPnP 和相关 Jython 进程。
2. 从 `OpenPnP-CoreXY/backups/machine.xml.before-official-agent-*.bak` 恢复 `machine.xml`。
3. 如需停用 Startup Agent，将 `scripts/Events/Startup.00.CoreXYAgent.py` 移出 `Events` 目录。
4. 使用原修改版启动方式。

仅把 `enabled=false` 写入配置可以停用真实补偿，但 Startup Agent 仍会加载并报告状态；完整回滚应同时恢复对应的 `machine.xml`。
