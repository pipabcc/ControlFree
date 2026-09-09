package com.example.controlfree.diagnostics

import com.example.controlfree.MonitorPhase
import com.example.controlfree.data.ForegroundObservationWorkerHealth
import java.util.concurrent.atomic.AtomicReference

data class MonitorRuntimeHealth(
    val serviceAvailable: Boolean = false,
    val lastHeartbeatElapsedMillis: Long = 0L,
    val phase: MonitorPhase? = null,
    val persistenceHealthy: Boolean = true,
    val timerHealthy: Boolean = true,
    val foregroundObserverHealth: ForegroundObservationWorkerHealth? = null,
    val mediaReplayGuardEnabled: Boolean = false,
    val lastMediaProbeElapsedMillis: Long = 0L,
    val mediaProbeAvailable: Boolean = false,
    val internalReceiverRegistered: Boolean = false,
    val screenReceiverRegistered: Boolean = false,
    val systemDialogReceiverRegistered: Boolean = false,
    val callStateMonitorRequired: Boolean = false,
    val callStateMonitorRegistered: Boolean = false
)

/**
 * 仅保存当前进程的运行健康快照，不写磁盘，也不保存包名、凭据或异常文本。
 */
object MonitorRuntimeHealthRegistry {
    private val current = AtomicReference(MonitorRuntimeHealth())

    fun snapshot(): MonitorRuntimeHealth = current.get()

    fun markServiceCreated(nowElapsedMillis: Long) {
        current.set(
            MonitorRuntimeHealth(
                serviceAvailable = true,
                lastHeartbeatElapsedMillis = nowElapsedMillis.coerceAtLeast(0L)
            )
        )
    }

    fun update(health: MonitorRuntimeHealth) {
        current.set(
            health.copy(
                serviceAvailable = true,
                lastHeartbeatElapsedMillis = health.lastHeartbeatElapsedMillis.coerceAtLeast(0L),
                lastMediaProbeElapsedMillis = health.lastMediaProbeElapsedMillis.coerceAtLeast(0L)
            )
        )
    }

    fun markServiceDestroyed(nowElapsedMillis: Long) {
        current.updateAndGet { previous ->
            previous.copy(
                serviceAvailable = false,
                lastHeartbeatElapsedMillis = maxOf(
                    previous.lastHeartbeatElapsedMillis,
                    nowElapsedMillis.coerceAtLeast(0L)
                ),
                mediaReplayGuardEnabled = false
            )
        }
    }
}
