# 钢网夹具 1.14.0 迁移对照

来源为应用中心实际注册的 `stencil-studio` 1.14.0 当前源码，不使用旧输出模型或技能快照。来源文件哈希和 Java 对应实现见 `provenance.json`。原应用目录、虚拟环境、数据库、用户输出及应用中心注册项均保持独立。

| 能力或细节 | 来源 | Java 沿用方式 / 差异 | 验证 |
|---|---|---|---|
| 全量 schema 1 参数、严格类型/范围、尺寸联动 | domain.py、factory-defaults.json、web/parameter-options.js | StencilParameters；工厂参数一致；保留旧 engrave_depth 导入导出但隐藏不影响现行几何的旧编辑项 | 参数及界面测试 |
| 三件主体、开口轴座、扁轴、左右快拆扩展 | geometry.py、domain.py | StencilCadTask / StencilPlan，几何版本 17 | 三组原源码实体体积和包围盒对照、实体有效性 |
| 侧/底部取板孔、开边通道和 3 mm 实心圈 | 同上 | 逐公式迁移，底部孔半径受 PCB 长宽共同约束 | 不对称底部孔参数对照及公差边界测试 |
| 网格/侧面减重和底厚保护 | 同上 | 同一网格划分及避让，报告计算移除体积 | STEP 实体对照、最小底厚组合测试 |
| PCB 槽深、轴径和轴向补偿 | domain.py、hinge_calibration.py | 同样 0.05 mm 步长和正负语义 | 非法步长拒绝、基准测试 |
| 2/3/4 颗 M2.5、4/5/6 mm 螺钉与两级沉孔 | geometry.py | 完整孔和六角螺母槽、打印朝向 | 实体对照；6 mm 螺钉每 5° 扫掠 |
| 9 组 C0.00～C0.40，18 件同板 | hinge_calibration.py | 真实同宽轴座/扁轴截取，带 C 标记 | 18 实体、Z=0、45 个翻转/取出检查、STEP 回读 |
| 中文凹刻、同排名称/尺寸、手动/自动定位 | text_layout.py、font_catalog.py、geometry.py | Java 字体轮廓；实际字高，最大 0.008 mm 折线化；不自动缩字或扩大底板 | 放不下拒绝、几何底厚/工作区检查 |
| 二维/三维点击定位 | web/app.js、web/viewer.js | Java2D 示意图 / 三维顶面拾取，合拢姿态下定位 | 界面交互及图像检查 |
| 实际三维、旋转/平移/缩放、展开/合拢、翻转/抬升 | web/viewer.js | Java 软件深度缓冲渲染，保持视角；增加打印姿态页 | 实体网格预览及截图检查 |
| STEP / STL / 3MF / ZIP、任务冻结源、按型号保存 | worker.py、mesh_export.py、output_files.py | 精确 BRep STEP 回读；共享顶点的标准 3MF；冻结 CAD JSON 程序替代 Python 脚本 | 原生集成、3MF OPC/XML 测试 |
| 后台进度、耗时/ETA、取消、历史、重启恢复 | app.py、worker.py、web/app.js | 每个 Pro 数据目录单一串行服务；关闭窗口继续、退出中断恢复；已完成文件可打开 | 取消及重启状态测试 |
| 导入/导出、启动参数、出厂重置 | app.py | 原参数 envelope 与直接参数兼容，独立 Java 存储 | 参数往返、启动参数隔离测试 |
| 命名配置搜索、软删除/恢复、同名二次确认 | app.py、web/app.js | 保留 ID、修订校验、替换前备份；可导出和重新载入 | 同名、旧修订、删除恢复冲突测试 |
| 结果面板收起/展开、目录/文件/Bambu 打开 | web/app.js、local_open.py、desktop_open.py | Swing 分隔条一键收起，窗口/分隔条记忆；系统关联或 Bambu 可执行文件 | 本地路径约束、界面检查；不自动启动外部切片打印 |
| 独立物理纸张标签、纸张默认记忆、中文打印 | labels.py、label_print.py | 原生 Java 203 dpi 标签，默认 50×30 mm；系统打印选择；PNG 预览替代网页打印页 | 纸张尺寸和非空图像测试；不实际耗纸打印 |

继承范围中不再运行的基础设施：FastAPI/Pydantic/Python worker、网页 HTTP API、浏览器/Three.js、Python CAD CLI、PowerShell 标签打印脚本。它们分别由 Java 参数模型/后台任务/Swing/本地 C++ 内核/Java 打印系统替代；不提供对原 HTTP API 的兼容服务。

引用方向仅为 OpenPnP Pro → stencil-studio。此次读取来源、不改来源，不影响应用中心的独立钢网夹具或依赖它的其他应用；后续同步应按已登记哈希比较两端改动。
