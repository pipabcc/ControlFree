package com.example.controlfree.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AllowlistAppPartition(
    val selectableApps: List<AllowedApp>,
    val allowedApps: List<AllowedApp>,
    val customPackages: Set<String>,
    val requiredPackages: Set<String>
)

data class AllowlistLoadError(
    val appPartitionFailed: Boolean,
    val callUiPartitionFailed: Boolean
) {
    val message: String
        get() = when {
            appPartitionFailed && callUiPartitionFailed -> "App 列表和电话白名单刷新失败"
            appPartitionFailed -> "App 列表刷新失败，继续使用上次成功数据"
            else -> "电话白名单刷新失败，继续使用上次成功数据"
        }
}

enum class AllowlistSaveResult {
    SAVED,
    CONFLICT,
    FAILED
}

data class AllowlistSnapshot(
    val version: Long,
    val selectableApps: List<AllowedApp>,
    val allowedApps: List<AllowedApp>,
    val allowedPackages: Set<String>,
    val customPackages: Set<String>,
    val requiredPackages: Set<String>,
    val callUiPackages: Set<String>,
    val hasAppPartitionData: Boolean,
    val hasCallUiPartitionData: Boolean,
    val lastFullSuccessMillis: Long
)

sealed interface AllowlistRepositoryState {
    val snapshot: AllowlistSnapshot?

    data object Uninitialized : AllowlistRepositoryState {
        override val snapshot: AllowlistSnapshot? = null
    }

    data class Loading(override val snapshot: AllowlistSnapshot?) : AllowlistRepositoryState

    data class Ready(override val snapshot: AllowlistSnapshot) : AllowlistRepositoryState

    data class Stale(
        override val snapshot: AllowlistSnapshot,
        val error: AllowlistLoadError
    ) : AllowlistRepositoryState

    data class Failed(val error: AllowlistLoadError) : AllowlistRepositoryState {
        override val snapshot: AllowlistSnapshot? = null
    }
}

internal interface AllowlistDataSource {
    fun loadSystemRolePackages(): SystemRolePackages

    fun loadAppPartition(systemRoles: SystemRolePackages): AllowlistAppPartition

    fun loadCallUiPackages(systemRoles: SystemRolePackages): Set<String>

    fun saveCustomPackages(
        packages: Set<String>,
        expectedCustomPackages: Set<String>
    ): AllowlistSaveResult

    fun getApplicationIcon(packageName: String): Drawable

    fun invalidateApplicationIcon(packageName: String?)
}

/**
 * 进程级白名单仓库。刷新分区独立失败，已有成功快照永不被瞬时空结果覆盖。
 */
class AllowlistRepository internal constructor(
    private val source: AllowlistDataSource,
    private val workExecutor: ExecutorService,
    private val resultExecutor: Executor,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<ListenerSubscription>()
    private val mutableState = MutableStateFlow<AllowlistRepositoryState>(
        AllowlistRepositoryState.Uninitialized
    )
    private var refreshInFlight = false
    private var refreshPending = false
    private var refreshGeneration = 0L
    private var isClosed = false
    private val pendingSaveRequests = linkedSetOf<PendingSaveRequest>()
    private var invalidationContext: Context? = null
    private var invalidationReceiver: BroadcastReceiver? = null

    val state: StateFlow<AllowlistRepositoryState> = mutableState.asStateFlow()

    fun refresh() {
        val request = synchronized(lock) {
            if (isClosed) return
            if (refreshInFlight) {
                refreshPending = true
                return
            }
            refreshInFlight = true
            refreshGeneration = nextGeneration(refreshGeneration)
            RefreshRequest(
                generation = refreshGeneration,
                previousSnapshot = mutableState.value.snapshot
            )
        }
        val scheduled = executeResult {
            if (
                publishStateForGeneration(
                    AllowlistRepositoryState.Loading(request.previousSnapshot),
                    request.generation
                )
            ) {
                executeRefresh(request)
            }
        }
        if (!scheduled) {
            synchronized(lock) {
                if (request.generation == refreshGeneration) {
                    refreshInFlight = false
                    refreshPending = false
                }
            }
        }
    }

    fun saveCustomPackages(
        packages: Set<String>,
        expectedCustomPackages: Set<String>,
        onComplete: (AllowlistSaveResult) -> Unit
    ) {
        val snapshot = packages.toSet()
        val expectedSnapshot = expectedCustomPackages.toSet()
        val saveRequest = PendingSaveRequest(onComplete)
        val accepted = synchronized(lock) {
            if (isClosed) false else pendingSaveRequests.add(saveRequest)
        }
        if (!accepted) {
            executeResult { onComplete(AllowlistSaveResult.FAILED) }
            return
        }
        try {
            workExecutor.execute {
                val result = try {
                    source.saveCustomPackages(snapshot, expectedSnapshot)
                } catch (_: RuntimeException) {
                    AllowlistSaveResult.FAILED
                }
                executeResult {
                    if (completePendingSave(saveRequest)) {
                        if (result != AllowlistSaveResult.FAILED) refresh()
                        onComplete(result)
                    }
                }
            }
        } catch (_: RuntimeException) {
            executeResult {
                if (completePendingSave(saveRequest)) {
                    onComplete(AllowlistSaveResult.FAILED)
                }
            }
        }
    }

    fun getApplicationIcon(packageName: String): Drawable =
        source.getApplicationIcon(packageName)

    fun addListener(listener: (AllowlistRepositoryState) -> Unit): AutoCloseable {
        val subscription = ListenerSubscription(listener)
        val accepted = synchronized(lock) {
            if (isClosed) false else listeners.add(subscription)
        }
        if (!accepted) {
            subscription.isActive.set(false)
            return AutoCloseable { }
        }
        executeResult {
            if (subscription.isActive.get() && !isRepositoryClosed()) {
                listener(mutableState.value)
            }
        }
        return AutoCloseable {
            subscription.isActive.set(false)
            listeners -= subscription
        }
    }

    private fun completePendingSave(request: PendingSaveRequest): Boolean = synchronized(lock) {
        pendingSaveRequests.remove(request)
    }

    private fun isRepositoryClosed(): Boolean = synchronized(lock) { isClosed }

    private fun executeRefresh(request: RefreshRequest) {
        try {
            workExecutor.execute {
                val roleResult = runCatching(source::loadSystemRolePackages)
                val appResult = roleResult.mapCatching(source::loadAppPartition)
                val callResult = roleResult.mapCatching(source::loadCallUiPackages)
                if (!executeResult { completeRefresh(request, appResult, callResult) }) {
                    abandonRefresh(request)
                }
            }
        } catch (_: RuntimeException) {
            if (!executeResult {
                completeRefresh(
                    request,
                    Result.failure(IllegalStateException("refresh rejected")),
                    Result.failure(IllegalStateException("refresh rejected"))
                )
            }) {
                abandonRefresh(request)
            }
        }
    }

    private fun abandonRefresh(request: RefreshRequest) {
        synchronized(lock) {
            if (!isClosed && request.generation == refreshGeneration) {
                refreshInFlight = false
                refreshPending = false
            }
        }
    }

    private fun completeRefresh(
        request: RefreshRequest,
        appResult: Result<AllowlistAppPartition>,
        callResult: Result<Set<String>>
    ) {
        val nextState = synchronized(lock) {
            if (isClosed || request.generation != refreshGeneration) return
            val previous = request.previousSnapshot
            val appPartition = appResult.getOrNull()
            val callPackages = callResult.getOrNull()
            val error = AllowlistLoadError(
                appPartitionFailed = appPartition == null,
                callUiPartitionFailed = callPackages == null
            )
            val snapshot = if (appPartition == null && previous == null && callPackages == null) {
                null
            } else {
                val selectableApps = appPartition?.selectableApps ?: previous?.selectableApps.orEmpty()
                val allowedApps = appPartition?.allowedApps ?: previous?.allowedApps.orEmpty()
                AllowlistSnapshot(
                    version = nextGeneration(previous?.version ?: 0L),
                    selectableApps = selectableApps,
                    allowedApps = allowedApps,
                    allowedPackages = allowedApps.mapTo(linkedSetOf(), AllowedApp::packageName),
                    customPackages = appPartition?.customPackages ?: previous?.customPackages.orEmpty(),
                    requiredPackages =
                        appPartition?.requiredPackages ?: previous?.requiredPackages.orEmpty(),
                    callUiPackages = callPackages ?: previous?.callUiPackages.orEmpty(),
                    hasAppPartitionData =
                        appPartition != null || previous?.hasAppPartitionData == true,
                    hasCallUiPartitionData =
                        callPackages != null || previous?.hasCallUiPartitionData == true,
                    lastFullSuccessMillis = if (appPartition != null && callPackages != null) {
                        nowMillis().coerceAtLeast(0L)
                    } else {
                        previous?.lastFullSuccessMillis ?: 0L
                    }
                )
            }
            when {
                snapshot == null -> AllowlistRepositoryState.Failed(error)
                !error.appPartitionFailed && !error.callUiPartitionFailed ->
                    AllowlistRepositoryState.Ready(snapshot)
                else -> AllowlistRepositoryState.Stale(snapshot, error)
            }
        }
        if (!publishStateForGeneration(nextState, request.generation)) return
        val nextRequest = synchronized(lock) {
            if (isClosed || request.generation != refreshGeneration) return
            refreshInFlight = false
            if (!refreshPending) {
                null
            } else {
                refreshPending = false
                refreshInFlight = true
                refreshGeneration = nextGeneration(refreshGeneration)
                RefreshRequest(refreshGeneration, nextState.snapshot)
            }
        }
        nextRequest?.let { pending ->
            if (
                publishStateForGeneration(
                    AllowlistRepositoryState.Loading(pending.previousSnapshot),
                    pending.generation
                )
            ) {
                executeRefresh(pending)
            }
        }
    }

    private fun publishStateForGeneration(
        state: AllowlistRepositoryState,
        generation: Long
    ): Boolean {
        val currentListeners = synchronized(lock) {
            if (isClosed || generation != refreshGeneration) return false
            mutableState.value = state
            listeners.toList()
        }
        currentListeners.forEach { subscription ->
            if (!subscription.isActive.get()) return@forEach
            try {
                subscription.listener(state)
            } catch (_: RuntimeException) {
                // 单个观察者异常不能破坏仓库刷新。
            }
        }
        return true
    }

    private fun executeResult(block: () -> Unit): Boolean =
        try {
            resultExecutor.execute(block)
            true
        } catch (_: RuntimeException) {
            // 结果线程已关闭时不得回退到 PackageManager 工作线程。
            false
        }

    override fun close() {
        val callbacks: List<(AllowlistSaveResult) -> Unit>
        val context: Context?
        val receiver: BroadcastReceiver?
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            refreshGeneration = nextGeneration(refreshGeneration)
            refreshInFlight = false
            refreshPending = false
            listeners.forEach { it.isActive.set(false) }
            listeners.clear()
            callbacks = pendingSaveRequests.map(PendingSaveRequest::callback)
            pendingSaveRequests.clear()
            context = invalidationContext
            receiver = invalidationReceiver
            invalidationContext = null
            invalidationReceiver = null
        }
        workExecutor.shutdownNow()
        callbacks.forEach { callback ->
            executeResult { callback(AllowlistSaveResult.FAILED) }
        }
        if (context != null && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // 进程结束或注册失败时无需再次处理。
            }
        }
        synchronized(Companion) {
            if (instance === this) instance = null
        }
    }

    private class ListenerSubscription(
        val listener: (AllowlistRepositoryState) -> Unit,
        val isActive: AtomicBoolean = AtomicBoolean(true)
    )

    private class PendingSaveRequest(val callback: (AllowlistSaveResult) -> Unit)

    private data class RefreshRequest(
        val generation: Long,
        val previousSnapshot: AllowlistSnapshot?
    )

    companion object {
        @Volatile
        private var instance: AllowlistRepository? = null

        fun get(context: Context): AllowlistRepository {
            instance?.takeIf { repository -> !repository.isRepositoryClosed() }?.let { return it }
            return synchronized(this) {
                instance
                    ?.takeIf { repository -> !repository.isRepositoryClosed() }
                    ?: createAndroidRepository(context.applicationContext).also { repository ->
                        instance = repository
                    }
            }
        }

        private fun createAndroidRepository(context: Context): AllowlistRepository {
            val manager = AppAllowlistManager(context)
            val handler = Handler(Looper.getMainLooper())
            val repository = AllowlistRepository(
                source = AndroidAllowlistDataSource(manager),
                workExecutor = Executors.newSingleThreadExecutor(
                    ThreadFactory { task ->
                        Thread(task, "controlfree-allowlist-repository").apply { isDaemon = true }
                    }
                ),
                resultExecutor = Executor { command ->
                    if (!handler.post(command)) {
                        throw RejectedExecutionException("allowlist result looper unavailable")
                    }
                }
            )
            repository.registerInvalidationReceivers(context)
            return repository
        }

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L

        private val PACKAGE_INVALIDATION_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED
        )
    }

    private fun registerInvalidationReceivers(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action in PACKAGE_INVALIDATION_ACTIONS) {
                    source.invalidateApplicationIcon(intent?.data?.schemeSpecificPart)
                } else if (
                    intent?.action == Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE ||
                    intent?.action == Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE
                ) {
                    source.invalidateApplicationIcon(null)
                }
                refresh()
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                    addAction(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED)
                    addAction(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                    addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (error: RuntimeException) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // 前置注册尚未成功时无需处理。
            }
            close()
            throw error
        }
        synchronized(lock) {
            if (isClosed) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: RuntimeException) {
                    // close 与注册并发时，确保不保留进程级接收器。
                }
            } else {
                invalidationContext = context
                invalidationReceiver = receiver
            }
        }
    }
}

private class AndroidAllowlistDataSource(
    private val manager: AppAllowlistManager
) : AllowlistDataSource {
    override fun loadSystemRolePackages(): SystemRolePackages = manager.getSystemRolePackages()

    override fun loadAppPartition(systemRoles: SystemRolePackages): AllowlistAppPartition {
        val allowedApps = manager.getAllowedApps(systemRoles)
        return AllowlistAppPartition(
            selectableApps = manager.getSelectableApps(),
            allowedApps = allowedApps,
            customPackages = manager.getCustomPackages(),
            requiredPackages = allowedApps
                .filter(AllowedApp::isSystemRequired)
                .mapTo(linkedSetOf(), AllowedApp::packageName)
        )
    }

    override fun loadCallUiPackages(systemRoles: SystemRolePackages): Set<String> =
        manager.getCallUiPackages(systemRoles)

    override fun saveCustomPackages(
        packages: Set<String>,
        expectedCustomPackages: Set<String>
    ): AllowlistSaveResult = manager.setCustomPackages(packages, expectedCustomPackages)

    override fun getApplicationIcon(packageName: String): Drawable =
        manager.getApplicationIcon(packageName)

    override fun invalidateApplicationIcon(packageName: String?) {
        manager.invalidateApplicationIcon(packageName)
    }
}
