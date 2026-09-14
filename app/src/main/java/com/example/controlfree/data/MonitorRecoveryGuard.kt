package com.example.controlfree.data

import android.content.Context
import android.util.AtomicFile
import com.example.controlfree.MonitorCycleSnapshot
import com.example.controlfree.MonitorPhase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 主 SharedPreferences 写入失败时的独立恢复检查点。
 *
 * PREPARED 表示主快照可能尚未提交，恢复时必须保持锁定；COMMITTED 只有在与主快照
 * 完全匹配时才证明事务已完成。这样即使最后的检查点清理失败，也不会把已进入玩机阶段
 * 的用户重新送回完整锁定，同时仍能覆盖真正的中断写入。
 */
class MonitorRecoveryGuard(context: Context) {
    private val appContext = context.applicationContext
    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    // 锁屏界面每秒调用 requiresLock()；本进程内对该文件的所有读写都经过
    // FILE_LOCK 串行，因此读结果可以进程级缓存，仅在写入后失效。
    private val progressPreferences: PreferenceManager by lazy {
        PreferenceManager(appContext)
    }

    fun requiresLock(): Boolean {
        val persistedSnapshot = runCatching {
            progressPreferences.loadMonitorProgress()
        }.getOrNull()
        return requiresLock(persistedSnapshot)
    }

    fun requiresLock(persistedSnapshot: MonitorCycleSnapshot?): Boolean = synchronized(FILE_LOCK) {
        val read = readCheckpoint()
        when {
            !read.exists -> false
            read.checkpoint == null -> true
            read.checkpoint.stage != CheckpointStage.COMMITTED -> true
            persistedSnapshot == null -> true
            else -> !read.checkpoint.matches(persistedSnapshot)
        }
    }

    fun prepare(snapshot: MonitorCycleSnapshot): Boolean = synchronized(FILE_LOCK) {
        writeCheckpoint(RecoveryCheckpoint.from(snapshot, CheckpointStage.PREPARED))
    }

    fun commit(snapshot: MonitorCycleSnapshot): Boolean = synchronized(FILE_LOCK) {
        val prepared = readCheckpoint().checkpoint ?: return@synchronized false
        if (!prepared.matches(snapshot)) return@synchronized false
        if (prepared.stage !in setOf(CheckpointStage.PREPARED, CheckpointStage.COMMITTED)) {
            return@synchronized false
        }
        writeCheckpoint(prepared.copy(stage = CheckpointStage.COMMITTED))
    }

    /** 写入无法与普通快照匹配的旧式强制锁标记，供启动失败等保守恢复路径使用。 */
    fun markLockRequired(): Boolean = synchronized(FILE_LOCK) {
        writeAtomically { output -> output.write(LEGACY_MAGIC_BYTES) }
    }

    fun clear(): Boolean = synchronized(FILE_LOCK) {
        cachedCheckpointRead = null
        try {
            atomicFile.delete()
            !guardFileExists()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun readCheckpoint(): CheckpointRead = synchronized(FILE_LOCK) {
        // 命中缓存时避免真实磁盘读取；写入路径会主动失效缓存。
        cachedCheckpointRead ?: readCheckpointUncached().also { cachedCheckpointRead = it }
    }

    private fun readCheckpointUncached(): CheckpointRead = try {
        atomicFile.openRead().use { input ->
            val data = DataInputStream(input)
            if (data.readUTF() != CHECKPOINT_MAGIC) return CheckpointRead(exists = true)
            val stage = CheckpointStage.entries.getOrNull(data.readUnsignedByte())
                ?: return CheckpointRead(exists = true)
            val phaseValue = data.readUTF()
            val phase = MonitorPhase.entries.firstOrNull { it.storedValue == phaseValue }
                ?: return CheckpointRead(exists = true)
            CheckpointRead(
                exists = true,
                checkpoint = RecoveryCheckpoint(
                    stage = stage,
                    phase = phase,
                    remainingMillis = data.readLong(),
                    checkpointElapsedMillis = data.readLong(),
                    bootCount = data.readInt(),
                    isInteractive = data.readBoolean(),
                    screenOffRecoveryRemainderMillis = data.readLong()
                )
            )
        }
    } catch (_: FileNotFoundException) {
        CheckpointRead(exists = false)
    } catch (_: IOException) {
        CheckpointRead(exists = true)
    } catch (_: RuntimeException) {
        CheckpointRead(exists = true)
    }

    private fun writeCheckpoint(checkpoint: RecoveryCheckpoint): Boolean = writeAtomically { output ->
        DataOutputStream(output).apply {
            writeUTF(CHECKPOINT_MAGIC)
            writeByte(checkpoint.stage.ordinal)
            writeUTF(checkpoint.phase.storedValue)
            writeLong(checkpoint.remainingMillis)
            writeLong(checkpoint.checkpointElapsedMillis)
            writeInt(checkpoint.bootCount)
            writeBoolean(checkpoint.isInteractive)
            writeLong(checkpoint.screenOffRecoveryRemainderMillis)
            flush()
        }
    }

    private fun writeAtomically(write: (FileOutputStream) -> Unit): Boolean {
        var output: FileOutputStream? = null
        return try {
            val stream = atomicFile.startWrite()
            output = stream
            write(stream)
            atomicFile.finishWrite(stream)
            output = null
            synchronized(FILE_LOCK) { cachedCheckpointRead = null }
            true
        } catch (_: IOException) {
            synchronized(FILE_LOCK) { cachedCheckpointRead = null }
            output?.let(::failWriteQuietly)
            false
        } catch (_: RuntimeException) {
            synchronized(FILE_LOCK) { cachedCheckpointRead = null }
            output?.let(::failWriteQuietly)
            false
        }
    }

    private fun failWriteQuietly(output: FileOutputStream) {
        try {
            atomicFile.failWrite(output)
        } catch (_: RuntimeException) {
            // 原始写入失败结果优先。
        }
    }

    private fun guardFileExists(): Boolean = try {
        atomicFile.openRead().use { }
        true
    } catch (_: FileNotFoundException) {
        false
    } catch (_: IOException) {
        true
    } catch (_: RuntimeException) {
        true
    }

    private enum class CheckpointStage {
        PREPARED,
        COMMITTED
    }

    private data class CheckpointRead(
        val exists: Boolean,
        val checkpoint: RecoveryCheckpoint? = null
    )

    private data class RecoveryCheckpoint(
        val stage: CheckpointStage,
        val phase: MonitorPhase,
        val remainingMillis: Long,
        val checkpointElapsedMillis: Long,
        val bootCount: Int,
        val isInteractive: Boolean,
        val screenOffRecoveryRemainderMillis: Long
    ) {
        fun matches(snapshot: MonitorCycleSnapshot): Boolean =
            phase == snapshot.phase &&
                remainingMillis == snapshot.remainingMillis &&
                checkpointElapsedMillis == snapshot.checkpointElapsedMillis &&
                bootCount == snapshot.bootCount &&
                isInteractive == snapshot.isInteractive &&
                screenOffRecoveryRemainderMillis == snapshot.screenOffRecoveryRemainderMillis

        companion object {
            fun from(
                snapshot: MonitorCycleSnapshot,
                stage: CheckpointStage
            ): RecoveryCheckpoint = RecoveryCheckpoint(
                stage = stage,
                phase = snapshot.phase,
                remainingMillis = snapshot.remainingMillis,
                checkpointElapsedMillis = snapshot.checkpointElapsedMillis,
                bootCount = snapshot.bootCount,
                isInteractive = snapshot.isInteractive,
                screenOffRecoveryRemainderMillis = snapshot.screenOffRecoveryRemainderMillis
            )
        }
    }

    private companion object {
        const val FILE_NAME = "monitor_recovery_guard"
        const val CHECKPOINT_MAGIC = "CONTROL_FREE_MONITOR_CHECKPOINT_V2"
        val LEGACY_MAGIC_BYTES = "CONTROL_FREE_LOCK_V1".encodeToByteArray()
        val FILE_LOCK = Any()

        /** 进程级读缓存；仅允许在 FILE_LOCK 内访问，写入路径负责失效。 */
        var cachedCheckpointRead: MonitorRecoveryGuard.CheckpointRead? = null
    }
}
