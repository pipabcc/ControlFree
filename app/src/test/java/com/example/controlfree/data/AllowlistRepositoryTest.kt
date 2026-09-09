package com.example.controlfree.data

import android.graphics.drawable.Drawable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistRepositoryTest {
    private val repositories = mutableListOf<AllowlistRepository>()

    @After
    fun tearDown() {
        repositories.forEach(AllowlistRepository::close)
    }

    @Test
    fun `首次完整加载进入 Ready`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)

        repository.refresh()
        assertTrue(repository.state.value is AllowlistRepositoryState.Loading)
        executor.runNext()

        val ready = repository.state.value as AllowlistRepositoryState.Ready
        assertEquals(setOf("example.allowed", "example.phone"), ready.snapshot.allowedPackages)
        assertEquals(setOf("example.dialer"), ready.snapshot.callUiPackages)
        assertTrue(ready.snapshot.hasAppPartitionData)
        assertTrue(ready.snapshot.hasCallUiPartitionData)
    }

    @Test
    fun `App 分区刷新失败保留旧列表且电话分区仍更新`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        repository.refresh()
        executor.runNext()
        val previous = requireNotNull(repository.state.value.snapshot)

        source.failApps = true
        source.callPackages = setOf("example.newdialer")
        repository.refresh()
        executor.runNext()

        val stale = repository.state.value as AllowlistRepositoryState.Stale
        assertEquals(previous.allowedApps, stale.snapshot.allowedApps)
        assertEquals(setOf("example.newdialer"), stale.snapshot.callUiPackages)
        assertTrue(stale.error.appPartitionFailed)
        assertFalse(stale.error.callUiPartitionFailed)
    }

    @Test
    fun `电话分区失败不会清空旧电话包或新的 App 列表`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        repository.refresh()
        executor.runNext()

        source.failCalls = true
        source.allowedPackage = "example.changed"
        repository.refresh()
        executor.runNext()

        val stale = repository.state.value as AllowlistRepositoryState.Stale
        assertTrue("example.changed" in stale.snapshot.allowedPackages)
        assertEquals(setOf("example.dialer"), stale.snapshot.callUiPackages)
        assertFalse(stale.error.appPartitionFailed)
        assertTrue(stale.error.callUiPartitionFailed)
    }

    @Test
    fun `首次全部失败进入 Failed 且没有可放行快照`() {
        val source = FakeSource().apply {
            failApps = true
            failCalls = true
        }
        val executor = ManualExecutorService()
        val repository = repository(source, executor)

        repository.refresh()
        executor.runNext()

        val failed = repository.state.value as AllowlistRepositoryState.Failed
        assertNull(failed.snapshot)
        assertTrue(failed.error.appPartitionFailed)
        assertTrue(failed.error.callUiPartitionFailed)
    }

    @Test
    fun `首次 App 分区失败仅保留电话分区且自定义白名单失败关闭`() {
        val source = FakeSource().apply { failApps = true }
        val executor = ManualExecutorService()
        val repository = repository(source, executor)

        repository.refresh()
        executor.runNext()

        val stale = repository.state.value as AllowlistRepositoryState.Stale
        assertFalse(stale.snapshot.hasAppPartitionData)
        assertTrue(stale.snapshot.hasCallUiPartitionData)
        assertTrue(stale.snapshot.allowedPackages.isEmpty())
        assertEquals(setOf("example.dialer"), stale.snapshot.callUiPackages)
    }

    @Test
    fun `首次电话分区失败仍保留 App 分区并让电话界面失败关闭`() {
        val source = FakeSource().apply { failCalls = true }
        val executor = ManualExecutorService()
        val repository = repository(source, executor)

        repository.refresh()
        executor.runNext()

        val stale = repository.state.value as AllowlistRepositoryState.Stale
        assertTrue(stale.snapshot.hasAppPartitionData)
        assertFalse(stale.snapshot.hasCallUiPartitionData)
        assertTrue("example.allowed" in stale.snapshot.allowedPackages)
        assertTrue(stale.snapshot.callUiPackages.isEmpty())
    }

    @Test
    fun `刷新期间的多个请求合并为一次补充刷新`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)

        repository.refresh()
        repository.refresh()
        repository.refresh()

        assertEquals(1, executor.pendingCount)
        executor.runNext()
        assertEquals(1, executor.pendingCount)
        executor.runNext()
        assertEquals(2, source.appLoadCount)
        assertEquals(2, source.callLoadCount)
    }

    @Test
    fun `保存确认落盘后才回调成功并触发刷新`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        val callbackSawCommittedValue = AtomicBoolean(false)

        repository.saveCustomPackages(
            packages = setOf("example.saved"),
            expectedCustomPackages = emptySet()
        ) { result ->
            callbackSawCommittedValue.set(
                result == AllowlistSaveResult.SAVED &&
                    source.savedPackages == setOf("example.saved")
            )
        }
        executor.runNext()

        assertTrue(callbackSawCommittedValue.get())
        assertEquals(1, executor.pendingCount)
    }

    @Test
    fun `保存失败不会触发刷新或伪造成功`() {
        val source = FakeSource().apply { saveSucceeds = false }
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        var callbackResult: AllowlistSaveResult? = null

        repository.saveCustomPackages(
            packages = setOf("example.failed"),
            expectedCustomPackages = emptySet()
        ) { result -> callbackResult = result }
        executor.runNext()

        assertEquals(AllowlistSaveResult.FAILED, callbackResult)
        assertEquals(0, executor.pendingCount)
    }

    @Test
    fun `保存基线变化时返回冲突并触发刷新`() {
        val source = FakeSource().apply { currentPackages = setOf("example.changed") }
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        var callbackResult: AllowlistSaveResult? = null

        repository.saveCustomPackages(
            packages = setOf("example.saved"),
            expectedCustomPackages = emptySet()
        ) { result -> callbackResult = result }
        executor.runNext()

        assertEquals(AllowlistSaveResult.CONFLICT, callbackResult)
        assertNull(source.savedPackages)
        assertEquals(1, executor.pendingCount)
    }

    @Test
    fun `关闭后迟到刷新结果不会覆盖状态`() {
        val source = FakeSource()
        val work = ManualExecutorService()
        val results = QueueingExecutor()
        val repository = AllowlistRepository(source, work, results).also(repositories::add)

        repository.refresh()
        results.runNext()
        work.runNext()
        assertEquals(1, results.pendingCount)
        repository.close()
        results.runNext()

        assertTrue(repository.state.value is AllowlistRepositoryState.Loading)
    }

    @Test
    fun `监听器在首次通知执行前关闭不会收到回调`() {
        val source = FakeSource()
        val work = ManualExecutorService()
        val results = QueueingExecutor()
        val repository = AllowlistRepository(source, work, results).also(repositories::add)
        var callbackCount = 0

        val subscription = repository.addListener { callbackCount++ }
        subscription.close()
        results.runNext()

        assertEquals(0, callbackCount)
    }

    @Test
    fun `关闭发生在加载状态提交前不会再更新状态`() {
        val source = FakeSource()
        val work = ManualExecutorService()
        val results = QueueingExecutor()
        val repository = AllowlistRepository(source, work, results).also(repositories::add)

        repository.refresh()
        repository.close()
        results.runNext()

        assertTrue(repository.state.value is AllowlistRepositoryState.Uninitialized)
        assertEquals(0, work.pendingCount)
    }

    @Test
    fun `关闭仓库会以失败结束尚未执行的保存回调`() {
        val source = FakeSource()
        val work = ManualExecutorService()
        val repository = repository(source, work)
        var callbackResult: AllowlistSaveResult? = null

        repository.saveCustomPackages(
            packages = setOf("example.pending"),
            expectedCustomPackages = emptySet()
        ) { result -> callbackResult = result }
        repository.close()

        assertEquals(AllowlistSaveResult.FAILED, callbackResult)
        assertEquals(0, work.pendingCount)
    }

    @Test
    fun `系统角色查询失败会让两个分区失败并保留旧快照`() {
        val source = FakeSource()
        val executor = ManualExecutorService()
        val repository = repository(source, executor)
        repository.refresh()
        executor.runNext()
        val previous = requireNotNull(repository.state.value.snapshot)

        source.failRoles = true
        repository.refresh()
        executor.runNext()

        val stale = repository.state.value as AllowlistRepositoryState.Stale
        assertEquals(previous.allowedPackages, stale.snapshot.allowedPackages)
        assertEquals(previous.callUiPackages, stale.snapshot.callUiPackages)
        assertTrue(stale.error.appPartitionFailed)
        assertTrue(stale.error.callUiPartitionFailed)
    }

    @Test
    fun `刷新结果线程拒绝完成任务后允许下一次刷新`() {
        val source = FakeSource()
        val work = ManualExecutorService()
        val results = RejectSecondExecutor()
        val repository = AllowlistRepository(source, work, results).also(repositories::add)

        repository.refresh()
        work.runNext()
        repository.refresh()

        assertEquals(1, work.pendingCount)
    }

    private fun repository(
        source: FakeSource,
        executor: ManualExecutorService
    ) = AllowlistRepository(
        source = source,
        workExecutor = executor,
        resultExecutor = Executor(Runnable::run),
        nowMillis = { 123_000L }
    ).also(repositories::add)

    private class FakeSource : AllowlistDataSource {
        var failApps = false
        var failCalls = false
        var failRoles = false
        var allowedPackage = "example.allowed"
        var callPackages = setOf("example.dialer")
        var savedPackages: Set<String>? = null
        var currentPackages: Set<String> = emptySet()
        var saveSucceeds = true
        var appLoadCount = 0
        var callLoadCount = 0

        override fun loadSystemRolePackages(): SystemRolePackages {
            if (failRoles) throw IllegalStateException("roles failed")
            return SystemRolePackages(
                defaultDialerPackage = "example.dialer",
                systemDialerPackage = null,
                defaultSmsPackage = "example.sms"
            )
        }

        override fun loadAppPartition(systemRoles: SystemRolePackages): AllowlistAppPartition {
            appLoadCount++
            if (failApps) throw IllegalStateException("apps failed")
            val required = AllowedApp("example.phone", "电话", isSystemRequired = true)
            val custom = AllowedApp(allowedPackage, "允许 App")
            return AllowlistAppPartition(
                selectableApps = listOf(custom),
                allowedApps = listOf(required, custom),
                customPackages = setOf(allowedPackage),
                requiredPackages = setOf(required.packageName)
            )
        }

        override fun loadCallUiPackages(systemRoles: SystemRolePackages): Set<String> {
            callLoadCount++
            if (failCalls) throw IllegalStateException("calls failed")
            return callPackages
        }

        override fun saveCustomPackages(
            packages: Set<String>,
            expectedCustomPackages: Set<String>
        ): AllowlistSaveResult {
            if (currentPackages != expectedCustomPackages) return AllowlistSaveResult.CONFLICT
            if (!saveSucceeds) return AllowlistSaveResult.FAILED
            savedPackages = packages
            currentPackages = packages
            return AllowlistSaveResult.SAVED
        }

        override fun getApplicationIcon(packageName: String): Drawable =
            throw UnsupportedOperationException("not used")

        override fun invalidateApplicationIcon(packageName: String?) = Unit
    }

    private class ManualExecutorService : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            check(!shutdown)
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private class QueueingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private class RejectSecondExecutor : Executor {
        private var invocationCount = 0

        override fun execute(command: Runnable) {
            invocationCount++
            if (invocationCount == 2) throw IllegalStateException("rejected")
            command.run()
        }
    }
}
