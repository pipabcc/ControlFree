package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessStopCoordinatorTest {
    @Test
    fun `清理成功但界面回调丢失时看门狗仍可消费结果`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(Any(), worker, 1_000L))
        val result = MonitorStateClearResult(progressCleared = true, guardCleared = true)

        assertTrue(coordinator.recordResult(stop.generation, result))

        val claim = coordinator.claimResult(stop.generation)
        assertNotNull(claim)
        assertEquals(result, claim?.result)
        assertSame(worker, claim?.worker)
        assertFalse(coordinator.hasPending())
    }

    @Test
    fun `清理失败但界面回调丢失时看门狗仍可恢复同一worker`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(Any(), worker, 1_000L))
        val result = MonitorStateClearResult(progressCleared = false, guardCleared = false)

        assertTrue(coordinator.recordResult(stop.generation, result))

        val claim = coordinator.claimResult(stop.generation)
        assertNotNull(claim)
        assertEquals(result, claim?.result)
        assertSame(worker, claim?.worker)
    }

    @Test
    fun `原owner销毁后新实例接管已经完成的成功结果`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val originalOwner = Any()
        val replacementOwner = Any()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(originalOwner, worker, 5_000L))
        coordinator.recordResult(
            stop.generation,
            MonitorStateClearResult(progressCleared = true, guardCleared = true)
        )

        val adoption = coordinator.adopt(replacementOwner)

        assertNotNull(adoption)
        assertEquals(stop.generation, adoption?.generation)
        assertEquals(5_000L, adoption?.startedElapsedMillis)
        assertTrue(adoption?.resultReady == true)
        assertSame(worker, adoption?.worker)
        assertTrue(coordinator.isOwnedBy(stop.generation, replacementOwner))
    }

    @Test
    fun `原owner销毁后新实例接管已经完成的失败结果`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val replacementOwner = Any()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(Any(), worker, 5_000L))
        val failed = MonitorStateClearResult(progressCleared = true, guardCleared = false)
        coordinator.recordResult(stop.generation, failed)

        val adoption = requireNotNull(coordinator.adopt(replacementOwner))
        val claim = coordinator.claimResult(adoption.generation)

        assertEquals(failed, claim?.result)
        assertSame(worker, claim?.worker)
    }

    @Test
    fun `旧回调和新看门狗对同一代结果只能成功认领一次`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val stop = requireNotNull(coordinator.begin(Any(), Any(), 1_000L))
        coordinator.adopt(Any())
        coordinator.recordResult(
            stop.generation,
            MonitorStateClearResult(progressCleared = true, guardCleared = true)
        )

        assertNotNull(coordinator.claimResult(stop.generation))
        assertNull(coordinator.claimResult(stop.generation))
    }

    @Test
    fun `清理IO未返回时接管沿用原worker且不能提前认领`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(Any(), worker, 10_000L))

        val adoption = requireNotNull(coordinator.adopt(Any()))

        assertFalse(adoption.resultReady)
        assertSame(worker, adoption.worker)
        assertSame(worker, coordinator.pendingWorker())
        assertNull(coordinator.claimResult(stop.generation))
        assertTrue(coordinator.isPending(stop.generation))
    }

    @Test
    fun `失配generation不得登记认领或取消当前事务`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val worker = Any()
        val stop = requireNotNull(coordinator.begin(Any(), worker, 1_000L))
        val staleGeneration = stop.generation + 1L

        assertFalse(
            coordinator.recordResult(
                staleGeneration,
                MonitorStateClearResult(progressCleared = true, guardCleared = true)
            )
        )
        assertNull(coordinator.claimResult(staleGeneration))
        assertNull(coordinator.cancel(staleGeneration))
        assertTrue(coordinator.isPending(stop.generation))
        assertSame(worker, coordinator.pendingWorker())
    }
}
