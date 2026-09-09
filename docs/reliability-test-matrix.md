# 可靠性测试矩阵

本矩阵严格区分可重复执行的 JVM 故障注入、AndroidTest 平台契约和真实设备端到端验收。CI 在每次合并时运行 JVM 单元测试，并在 API 31、35 模拟器运行 AndroidTest；API 26、34、36 和厂商设备不属于当前自动化覆盖。

| 场景 | CI 自动化故障注入 | AndroidTest 契约覆盖 | 真实设备剩余验收 |
| --- | --- | --- | --- |
| Service 生命周期、重复启动和停止 | `AppSupervisionServiceRecoveryPolicyTest`、`MonitorRecoveryBroadcastPolicyTest` 验证主动停止、非主动销毁、空闲实例和重复恢复决策 | `ServiceLifecycleContractInstrumentedTest` 验证服务不可导出、FGS 类型和接收器声明 | 真机回调顺序、通知创建与移除 |
| 进程被系统杀死后恢复 | `AppSupervisionBinaryCodecTest`、`AppSupervisionEnforcementPolicyTest` 验证快照兼容、跨 boot 单调基线和可信阻止恢复；恢复策略要求重新调度 | 服务与接收器 Manifest 恢复入口 | 区分 `kill`、低内存回收与 `force-stop` 的系统行为 |
| FGS 提升失败或后台启动受限 | `AppSupervisionServiceRecoveryPolicyTest` 与 `MonitorRecoveryBroadcastPolicyTest` 通过注入失败验证独立重试，不调用真实 FGS API | 验证 `specialUse` 声明 | 后台启动限制、频道禁用和真实 `startForeground` 异常 |
| 慢存储/`fsync` 阻塞 | `LatestOnlySnapshotWriterTest`、`MonitorSnapshotPersistenceWorkerTest` 验证调用线程不阻塞、latest-only 合并、关闭排空和失败回调 | 无 | 对真实闪存或 Room 注入延迟并核对 ANR、最终落盘 |
| UsageStats Binder 卡死 | `ForegroundObservationWorkerTest` 以不可中断查询替身验证超时、执行器退役、迟到隔离、熔断；`AppSupervisionTransientSessionTrackerTest` 验证恢复后事件去重和短会话结算 | 无 | 厂商 Binder 的系统级阻塞注入 |
| 系统时间/时区变更 | `SupervisionScheduleReceiverTest` 验证时间、时区和精确闹钟权限 action 路由；恢复策略测试验证墙钟与单调时钟边界 | 验证接收器声明 | 实际广播后核对计划重算与 Alarm 状态 |
| UsageStats、悬浮窗、通知、电话等权限被撤销 | `ForegroundObservationWorkerTest` 覆盖 `ACCESS_DENIED`，`AppSupervisionEnforcementPolicyTest` 验证撤权后保守阻止，能力与覆盖层策略测试验证降级分类 | 无 | 通过 `appops`、`pm` 或系统设置运行中撤权并核对系统 UI |

## 设备端验收边界

JVM 注入用于稳定阻断回归，不能证明所有厂商系统行为一致。发布前的专用设备作业仍应覆盖进程终止、`force-stop`、运行中撤权、FGS 启动限制、系统时间变更和慢存储，并保留 logcat、`dumpsys activity services`、`dumpsys alarm`、权限状态和最近一次监督快照。
