package com.example.controlfree.data

import com.example.controlfree.MonitorCycleSnapshot
import com.example.controlfree.MonitorPhase
import com.example.controlfree.MonitorPauseState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSnapshotChecksumTest {
    private val snapshot = MonitorCycleSnapshot(
        phase = MonitorPhase.LOCK,
        remainingMillis = 30_000L,
        checkpointElapsedMillis = 12_345L,
        bootCount = 7,
        isInteractive = false,
        screenOffRecoveryRemainderMillis = 120_000L
    )

    @Test
    fun `完整快照生成的摘要可以稳定验证`() {
        val checksum = MonitorSnapshotChecksum.create(snapshot)

        assertTrue(MonitorSnapshotChecksum.matches(snapshot, checksum))
        assertTrue(MonitorSnapshotChecksum.matches(snapshot, checksum.uppercase()))
    }

    @Test
    fun `任一计时字段被修改后摘要验证失败`() {
        val checksum = MonitorSnapshotChecksum.create(snapshot)

        assertFalse(
            MonitorSnapshotChecksum.matches(
                snapshot.copy(remainingMillis = snapshot.remainingMillis - 1L),
                checksum
            )
        )
        assertFalse(
            MonitorSnapshotChecksum.matches(
                snapshot.copy(isInteractive = !snapshot.isInteractive),
                checksum
            )
        )
        assertFalse(
            MonitorSnapshotChecksum.matches(
                snapshot.copy(screenOffRecoveryRemainderMillis = 119_999L),
                checksum
            )
        )
    }

    @Test
    fun `损坏或截断的摘要验证失败`() {
        val checksum = MonitorSnapshotChecksum.create(snapshot)

        assertFalse(MonitorSnapshotChecksum.matches(snapshot, "not-a-checksum"))
        assertFalse(MonitorSnapshotChecksum.matches(snapshot, checksum.dropLast(2)))
    }

    @Test
    fun `暂停状态属于快照摘要的一部分`() {
        val pause = MonitorPauseState(
            accumulatedPauseMillis = 300_000L,
            startedAtEpochMillis = 1_000_000L,
            untilEpochMillis = 1_300_000L,
            deadlineElapsedMillis = 400_000L,
            bootCount = 7
        )
        val checksum = MonitorSnapshotChecksum.create(snapshot, pause)

        assertTrue(MonitorSnapshotChecksum.matches(snapshot, pause, checksum))
        assertFalse(
            MonitorSnapshotChecksum.matches(
                snapshot,
                pause.copy(accumulatedPauseMillis = 299_999L),
                checksum
            )
        )
    }
}
