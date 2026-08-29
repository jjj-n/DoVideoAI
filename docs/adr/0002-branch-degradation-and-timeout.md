# VideoContext 构建的分支降级与超时退出

## Context

VideoContext 构建阶段并行执行 ASR 与 OCR。该阶段需要同时处理三类边界：

- 单条分支业务失败时是否保留另一条有效证据
- FFmpeg 或 Tesseract 子进程卡死时如何退出
- 总预算耗尽后，主线程如何确认分支是否真的停止，再决定是否清理工作目录

`CompletableFuture.cancel(true)` 不保证中断 `supplyAsync` 已经运行的任务，因此不能把它作为长耗时分支的取消基础。

## Decision

**业务失败允许单路降级。** 两条分支各自把业务异常封装成 `BranchResult.failure`。只有 ASR 与 OCR 都失败时才终止 VideoContext 构建；单路失败时保留另一条分支产生的 VideoSegment，并记录 telemetry。

**可取消任务使用 `executor.submit(Callable)`。** `submitBranch` 直接返回 `Future<BranchResult<T>>`。总预算或调用线程中断时，对尚未完成的 Future 调用 `cancel(true)`，由 `FutureTask` 向工作线程发送 interrupt。线程池拒绝任务时立即减少 `CountDownLatch`，并返回一个已经完成的失败结果，避免等待一个从未启动的分支。

**共享绝对 deadline。** 主线程依次等待两条 Future，但两次 `get` 使用同一个 60 分钟绝对 deadline，第二条分支不会重新获得完整预算。总预算超时或主线程中断属于整个构建失败，不尝试把未完成分支包装成部分结果。

**子进程单独设置上限。** FFmpeg 最长等待 15 分钟，单次 Tesseract 最长等待 2 分钟；超时后调用 `destroyForcibly`。子进程上限与 VideoContext 总预算相互独立，避免 native 进程无限阻塞工作线程。

**退出信号与清理分离。** 每条分支在 `finally` 中减少 `CountDownLatch`。发出取消后，主线程最多等待 10 秒确认两条分支退出；若宽限期内仍有分支运行，则保留 workDir，避免删除正在被写入的文件。

**OCR 文本与证据图片分别降级。** 单帧上传 MinIO 失败时，OCR 文本仍参与 merge，证据引用回退为原视频时间锚点。OCR 分支整体失败时，显式清理已经上传的受管证据帧。

## Trade-offs

**收益：**

- ASR 或 OCR 单路故障不会丢掉另一条真实证据通道
- `Future.cancel(true)` 可以把取消信号送到工作线程，超时后不必等待正常完成
- 共享 deadline 防止串行等待把总预算翻倍
- latch 提供“分支已经退出”的独立信号，使工作目录清理更安全

**代价：**

- 单路降级得到的是不完整 VideoContext，下游结论可能缺少语音或视觉证据
- interrupt 需要业务代码和子进程等待逻辑正确响应，不能等同于线程已经退出
- 分支在宽限期内未退出时会保留临时目录，需要运维侧定期清理遗留文件
- 当前超时值是代码常量，不同部署环境无法直接通过配置调整

**考虑过的替代方案：**

1. **单路失败即让整个任务 Replay。** 这会丢弃另一条已经成功的证据通道，增加重复 ASR/OCR 成本，因此不采用。
2. **使用 `CompletableFuture.supplyAsync`。** API 简洁，但 `cancel(true)` 不保证中断运行任务；独立 probe 已验证该差异，因此不用于需要可取消语义的分支。
3. **取消后立即删除 workDir。** `cancel(true)` 只发出 interrupt，不证明任务已经退出，立即删除会与仍在写文件的分支竞争，因此通过 latch 加入退出宽限期。

## 边界与共存

**覆盖范围：** VideoContext 的 ASR/OCR 并行构建、取消与本地资源清理。

**不覆盖：**

- AgentLoop 的 deadline 与 Token/成本预算，见 ADR 0003
- 外部平台是否真正终止已经接收的请求
- 超过宽限期后遗留目录的后台清理任务

## Prototype 验证

`server/prototype/PrototypeCancelInterruptProbe.java` 可独立运行，验证三种语义：

- `CompletableFuture.supplyAsync(...).cancel(true)` 不会中断正在运行的 worker
- `ExecutorService.submit(...).cancel(true)` 会向 worker 发送 interrupt
- 如果调用方必须暴露 CompletableFuture，也可以用显式 promise 把取消转发给底层 Future；当前 VideoContext 实现不需要这一层

## 相关代码

- `service/VideoContextService.build()` - 分支等待、总 deadline 与失败处理
- `service/VideoContextService.submitBranch()` - `executor.submit` 与 BranchResult 封装
- `service/VideoContextService.cancelBranches()` - 取消和 CountDownLatch 宽限期
- `service/VideoContextService.runCommand()` - FFmpeg 超时与销毁
- `utils/OcrUtils` - Tesseract 超时与销毁
- `prototype/PrototypeCancelInterruptProbe.java` - cancel 语义 probe
