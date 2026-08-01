# OpenPnP Pro 相机实时预览分析与诊断说明

## 1. 原有实时预览调用链

```text
MainFrame
  → CameraPanel.addCamera()
  → CameraView.setCamera()
  → Camera.startContinuousCapture(CameraView)
  → AbstractBroadcastingCamera.ensureOpen()
  → AbstractBroadcastingCamera.start() 后台线程
  → AbstractBroadcastingCamera.run()
      → hasNewFrame()
      → captureTransformed()
      → broadcastCapture()
  → CameraView.frameReceived()
      → 保存 lastFrame
      → repaint()
  → CameraView.paintComponent()
      → 缩放图像
      → 绘制准星、识别附加框和文字
```

结论：OpenPnP 的实时预览本来就是由相机后台线程采集，不是在 Swing 界面线程中
反复调用 `capture()`。界面线程只负责采用收到的图像并绘制。

## 2. 已确认的低帧率原因

### 2.1 软件默认预览帧率较低

`AbstractBroadcastingCamera.previewFps` 的默认值是 5 FPS。相机配置页中的
Preview FPS 控制的是 OpenPnP 主动读取实时流的目标频率，不等于摄像头驱动或视频格式
标称的原生帧率。

### 2.2 原预览线程存在额外等待

原逻辑完成采集、变换和广播以后，再等待一个完整的 `1000 / FPS` 周期。因此实际周期为：

```text
实际周期 = 采集耗时 + 图像变换耗时 + UI 回调耗时 + 完整等待周期
```

例如目标 30 FPS、采集及变换耗时 15 ms，原逻辑仍再等待约 33 ms，实际只能达到约
21 FPS。本次已改为只等待当前帧周期的剩余时间。

### 2.3 相机曝光和 USB 格式仍是硬限制

即使 OpenPnP 目标设为 30 FPS，以下情况仍会降低真实帧率：

- 自动曝光在弱光下把曝光时间拉长。
- 高分辨率使用 YUY2、RGB 等未压缩格式，占满 USB 2.0 带宽。
- 顶部和底部相机位于同一个 USB 控制器并同时高速工作。
- 驱动报告 30 FPS，但当前分辨率或格式实际只支持更低帧率。
- 旋转、镜头校正、裁剪、颜色转换或其他图像变换耗时较高。
- 开启“任务期间暂停预览”后，机器忙碌时预览会主动停止。

这些因素必须用真实相机测量，不能只从配置值判断。

## 3. 灯光控制位置

正式视觉抓图的灯光由以下接口控制：

```text
Camera.actuateLightBeforeCapture()
  → AbstractCamera.actuateLightBeforeCapture()
  → 灯光执行器默认开启值

Camera.actuateLightAfterCapture()
  → AbstractCamera.actuateLightAfterCapture()
  → 灯光执行器默认关闭值
```

普通连续预览不会自动永久打开灯光。因此只在视觉识别或抓图时亮灯属于原有设计，
不是摄像头帧率设置错误。

## 4. 本次新增功能

入口：

```text
窗口 → 实验模式 → 实验功能 → 相机诊断与高速预览
```

功能包括：

- 选择顶部、底部或其他已配置相机。
- 同时监听全部相机，比较双相机 USB 带宽竞争。
- 临时设置 5、10、15、30、60 或最高 120 FPS 的测试目标。
- 采集线程与 Swing 显示解耦，只显示最新帧，不排队播放旧帧。
- 测量真实接收 FPS、显示 FPS、帧间隔、抖动、最大卡顿和绘制耗时。
- 统计主动丢弃帧及重复内容帧。
- 对支持的驱动读取格式、原生 FPS、曝光、自动曝光、增益和亮度。
- 控制灯光打开、关闭或预览期间常亮。
- 停止、关闭或异常时恢复进入测试前的预览 FPS 和灯光状态。
- 导出 UTF-8 CSV 诊断报告。

诊断监听器不运行 Vision Pipeline，也不修改正式任务、顶部视觉或底部视觉流程。

## 5. 建议上机测试顺序

| 轮次 | 测试方式 | 重点观察 |
|---|---|---|
| 1 | 顶部相机单独 15 FPS | 实际 FPS、曝光、帧间抖动和最大卡顿 |
| 2 | 顶部相机单独 30 FPS | 实际 FPS 是否随目标提高 |
| 3 | 底部相机单独 30 FPS并常亮 | 元件边缘是否清晰、曝光是否过长 |
| 4 | 全部相机同时 30 FPS | 两台相机 FPS 是否同时明显下降 |
| 5 | 分别更换 MJPEG/YUY2 和分辨率 | 确认 USB 带宽与格式影响 |
| 6 | 机器空闲与运行任务时对比 | 检查是否启用了任务期间暂停预览 |

## 6. 飞拍前建议记录

至少连续运行 30 秒并导出报告，记录：

- 单相机和双相机真实 FPS。
- 帧间隔平均值、抖动和最大卡顿。
- 相机曝光模式及数值。
- 当前分辨率和像素/压缩格式。
- 灯光关闭与常亮时的画质差异。
- 运动中元件边缘是否拖影。
- CPU 占用和界面绘制耗时。

普通 USB 相机没有可靠硬件曝光时间戳时，软件回调时间不能直接当作曝光时间。只有真实
帧率、抖动和相机延迟完成标定后，才能进一步确定飞拍速度和时间补偿参数。
