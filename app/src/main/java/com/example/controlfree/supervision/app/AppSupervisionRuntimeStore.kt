package com.example.controlfree.supervision.app

import android.content.Context
import com.example.controlfree.boot.BootRecoveryHintStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

data class AppSupervisionRuntimeSnapshot(
    val rules: List<AppSupervisionRule>,
    val states: List<AppSupervisionRuntimeState>,
    val savedAtEpochMillis: Long,
    val enforcementState: AppSupervisionEnforcementState? = null,
    val dailyUsageStates: List<AppSupervisionDailyUsageState> = emptyList(),
    val triggerRules: List<AppTriggerRule> = emptyList()
) {
    init {
        require(savedAtEpochMillis >= 0L) { "App 监督快照时间无效" }
        require(rules.size <= MAX_APP_SUPERVISION_RULES) { "App 监督规则过多" }
        require(states.size <= MAX_APP_SUPERVISION_RULES) { "App 监督状态过多" }
        require(dailyUsageStates.size <= MAX_APP_SUPERVISION_RULES) { "App 每日累计状态过多" }
        require(triggerRules.size <= MAX_APP_SUPERVISION_RULES) { "App 触发规则过多" }
        val rulesById = rules.associateBy(AppSupervisionRule::planId)
        val statesById = states.associateBy(AppSupervisionRuntimeState::planId)
        val dailyById = dailyUsageStates.associateBy(AppSupervisionDailyUsageState::planId)
        require(rulesById.size == rules.size) { "App 监督规则编号重复" }
        require(statesById.size == states.size) { "App 监督状态编号重复" }
        require(dailyById.size == dailyUsageStates.size) { "App 每日累计状态编号重复" }
        require(triggerRules.map(AppTriggerRule::planId).distinct().size == triggerRules.size) {
            "App 触发规则编号重复"
        }
        require(states.all { state -> rulesById[state.planId]?.let(state::matches) == true }) {
            "App 监督状态与规则不匹配"
        }
        require(dailyUsageStates.all { state ->
            rulesById[state.planId]?.let(state::matches) == true
        }) { "App 每日累计状态与规则不匹配" }
        enforcementState?.trustedBlock?.let { blocked ->
            val rule = rulesById[blocked.rule.planId]
            val state = statesById[blocked.rule.planId]
            require(rule == blocked.rule) { "App 监督阻止状态与规则不匹配" }
            val reasonEndEpochMillis = when (blocked.reason) {
                AppSupervisionBlockReason.REST -> {
                    require(
                        state?.matches(blocked.rule) == true &&
                            state.phase == AppSupervisionPhase.REST
                    ) { "App 监督阻止状态缺少对应休息状态" }
                    state.restUntilEpochMillis
                }
                AppSupervisionBlockReason.DAILY_LIMIT -> {
                    require(dailyById[blocked.rule.planId]?.remainingUsageMillis == 0L) {
                        "App 监督阻止状态缺少每日上限状态"
                    }
                    rule.dailyUsageResetAtEpochMillis
                }
                AppSupervisionBlockReason.DISABLED_TIME -> {
                    require(rule.disabledUntilEpochMillis > 0L) {
                        "App 监督阻止状态缺少禁用时段"
                    }
                    rule.disabledUntilEpochMillis
                }
            }
            require(
                enforcementState.validUntilEpochMillis in 1L..minOf(
                    rule.occurrenceEndEpochMillis,
                    reasonEndEpochMillis
                )
            ) {
                "App 监督阻止截止时间超出规则时段"
            }
        }
    }

    val isActive: Boolean
        get() = rules.isNotEmpty() || triggerRules.isNotEmpty()
}

sealed interface AppSupervisionSnapshotReadResult {
    data object None : AppSupervisionSnapshotReadResult
    data class Available(val snapshot: AppSupervisionRuntimeSnapshot) :
        AppSupervisionSnapshotReadResult
    data object Corrupted : AppSupervisionSnapshotReadResult
}

sealed interface AppSupervisionDailyUsageReadResult {
    data object None : AppSupervisionDailyUsageReadResult
    data class Available(val states: List<AppSupervisionDailyUsageState>) :
        AppSupervisionDailyUsageReadResult
    data object Corrupted : AppSupervisionDailyUsageReadResult
}

class AppSupervisionRuntimeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val bootRecoveryHintStore = BootRecoveryHintStore(context)

    fun read(): AppSupervisionSnapshotReadResult = try {
        val encoded = preferences.getString(KEY_RUNTIME_SNAPSHOT, null)
            ?: return AppSupervisionSnapshotReadResult.None
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return AppSupervisionSnapshotReadResult.Corrupted
        }
        AppSupervisionBinaryCodec.decodeSnapshot(bytes)
            ?.let(AppSupervisionSnapshotReadResult::Available)
            ?: AppSupervisionSnapshotReadResult.Corrupted
    } catch (_: RuntimeException) {
        AppSupervisionSnapshotReadResult.Corrupted
    }

    fun save(snapshot: AppSupervisionRuntimeSnapshot): Boolean {
        if (snapshot.isActive) {
            bootRecoveryHintStore.setAppSupervisionRecoveryRequired(true)
        }
        val saved = try {
            val encoded = Base64.getEncoder().encodeToString(
                AppSupervisionBinaryCodec.encodeSnapshot(snapshot)
            )
            val mergedDailyStates = mergeDailyUsageStates(
                existing = readDailyUsageStatesOrEmpty(),
                incoming = snapshot.dailyUsageStates
            )
            val encodedDaily = Base64.getEncoder().encodeToString(
                AppSupervisionBinaryCodec.encodeDailyUsageStates(mergedDailyStates)
            )
            preferences.edit()
                .putString(KEY_RUNTIME_SNAPSHOT, encoded)
                .putString(KEY_DAILY_USAGE, encodedDaily)
                .commit()
        } catch (_: RuntimeException) {
            false
        }
        if (saved && !snapshot.isActive) {
            bootRecoveryHintStore.setAppSupervisionRecoveryRequired(false)
        }
        return saved
    }

    fun clear(): Boolean {
        val cleared = try {
            preferences.edit().remove(KEY_RUNTIME_SNAPSHOT).commit()
        } catch (_: RuntimeException) {
            false
        }
        if (cleared) {
            bootRecoveryHintStore.setAppSupervisionRecoveryRequired(false)
        }
        return cleared
    }

    fun readDailyUsageStates(): AppSupervisionDailyUsageReadResult = try {
        val encoded = preferences.getString(KEY_DAILY_USAGE, null)
            ?: return AppSupervisionDailyUsageReadResult.None
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return AppSupervisionDailyUsageReadResult.Corrupted
        }
        AppSupervisionBinaryCodec.decodeDailyUsageStates(bytes)
            ?.let(AppSupervisionDailyUsageReadResult::Available)
            ?: AppSupervisionDailyUsageReadResult.Corrupted
    } catch (_: RuntimeException) {
        AppSupervisionDailyUsageReadResult.Corrupted
    }

    private fun readDailyUsageStatesOrEmpty(): List<AppSupervisionDailyUsageState> =
        (readDailyUsageStates() as? AppSupervisionDailyUsageReadResult.Available)?.states.orEmpty()

    private fun mergeDailyUsageStates(
        existing: List<AppSupervisionDailyUsageState>,
        incoming: List<AppSupervisionDailyUsageState>
    ): List<AppSupervisionDailyUsageState> {
        val incomingById = incoming.associateBy(AppSupervisionDailyUsageState::planId)
        val retained = existing.asSequence()
            .filterNot { it.planId in incomingById }
            .sortedByDescending(AppSupervisionDailyUsageState::localDateEpochDay)
            .take((MAX_APP_SUPERVISION_RULES - incoming.size).coerceAtLeast(0))
        return (incoming + retained).sortedBy(AppSupervisionDailyUsageState::planId)
    }

    /** 损坏状态按仍有监督处理，避免通过破坏本地快照绕过进入 App 的验证。 */
    fun requiresAppAuthentication(nowEpochMillis: Long): Boolean = when (val result = read()) {
        AppSupervisionSnapshotReadResult.None -> false
        AppSupervisionSnapshotReadResult.Corrupted -> true
        is AppSupervisionSnapshotReadResult.Available -> result.snapshot.rules.any { rule ->
            nowEpochMillis < rule.occurrenceEndEpochMillis
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_supervision_runtime"
        const val KEY_RUNTIME_SNAPSHOT = "runtime_snapshot_v1"
        const val KEY_DAILY_USAGE = "daily_usage_v1"
    }
}

object AppSupervisionBinaryCodec {
    fun encodeRules(rules: Collection<AppSupervisionRule>): ByteArray = encodeEnvelope(
        payloadType = PAYLOAD_RULES
    ) { output ->
        writeRules(output, rules.toList())
    }

    fun decodeRules(bytes: ByteArray): List<AppSupervisionRule>? = decodeEnvelope(
        bytes = bytes,
        expectedPayloadType = PAYLOAD_RULES
    ) { input, schemaVersion ->
        readRules(input, schemaVersion)
    }

    fun encodeTriggerRules(rules: Collection<AppTriggerRule>): ByteArray = encodeEnvelope(
        payloadType = PAYLOAD_TRIGGER_RULES
    ) { output ->
        writeTriggerRules(output, rules.toList())
    }

    fun decodeTriggerRules(bytes: ByteArray): List<AppTriggerRule>? = decodeEnvelope(
        bytes = bytes,
        expectedPayloadType = PAYLOAD_TRIGGER_RULES
    ) { input, schemaVersion ->
        require(schemaVersion >= 3) { "App 触发载荷版本无效" }
        readTriggerRules(input)
    }

    fun encodeDailyUsageStates(
        states: Collection<AppSupervisionDailyUsageState>
    ): ByteArray = encodeEnvelope(payloadType = PAYLOAD_DAILY_USAGE) { output ->
        writeDailyUsageStates(output, states.toList())
    }

    fun decodeDailyUsageStates(bytes: ByteArray): List<AppSupervisionDailyUsageState>? =
        decodeEnvelope(bytes, PAYLOAD_DAILY_USAGE) { input, schemaVersion ->
            require(schemaVersion >= 2) { "App 每日累计载荷版本无效" }
            readDailyUsageStates(input)
        }

    fun encodeSnapshot(snapshot: AppSupervisionRuntimeSnapshot): ByteArray = encodeEnvelope(
        payloadType = PAYLOAD_SNAPSHOT
    ) { output ->
        output.writeLong(snapshot.savedAtEpochMillis)
        writeRules(output, snapshot.rules)
        output.writeInt(snapshot.states.size)
        snapshot.states.forEach { state ->
            writeString(output, state.planId)
            output.writeLong(state.planUpdatedAtEpochMillis)
            output.writeLong(state.occurrenceEndEpochMillis)
            output.writeInt(state.phase.ordinal)
            output.writeLong(state.remainingAllowanceMillis)
            output.writeLong(state.restUntilEpochMillis)
            output.writeLong(state.checkpointElapsedMillis)
            output.writeInt(state.bootCount)
            output.writeBoolean(state.wasTargetForeground)
            output.writeBoolean(state.wasInteractive)
        }
        writeDailyUsageStates(output, snapshot.dailyUsageStates)
        writeTriggerRules(output, snapshot.triggerRules)
        snapshot.enforcementState?.let { enforcementState ->
            output.writeInt(SNAPSHOT_EXTENSION_MAGIC)
            output.writeInt(SNAPSHOT_EXTENSION_VERSION)
            writeEnforcementState(output, enforcementState)
        }
    }

    fun decodeSnapshot(bytes: ByteArray): AppSupervisionRuntimeSnapshot? = decodeEnvelope(
        bytes = bytes,
        expectedPayloadType = PAYLOAD_SNAPSHOT
    ) { input, schemaVersion ->
        val savedAtEpochMillis = input.readLong()
        val rules = readRules(input, schemaVersion)
        val stateCount = readBoundedCount(input)
        val states = List(stateCount) {
            AppSupervisionRuntimeState(
                planId = readString(input),
                planUpdatedAtEpochMillis = input.readLong(),
                occurrenceEndEpochMillis = input.readLong(),
                phase = AppSupervisionPhase.entries.getOrNull(input.readInt())
                    ?: throw IOException("App 监督阶段无效"),
                remainingAllowanceMillis = input.readLong(),
                restUntilEpochMillis = input.readLong(),
                checkpointElapsedMillis = input.readLong(),
                bootCount = input.readInt(),
                wasTargetForeground = input.readBoolean(),
                wasInteractive = input.readBoolean()
            )
        }
        val dailyUsageStates = if (schemaVersion >= 2) {
            readDailyUsageStates(input).filter { state ->
                rules.any { rule -> state.matches(rule) }
            }
        } else {
            emptyList()
        }
        val triggerRules = if (schemaVersion >= 3) readTriggerRules(input) else emptyList()
        val enforcementState = if (input.available() == 0) {
            null
        } else {
            require(input.readInt() == SNAPSHOT_EXTENSION_MAGIC) {
                "App 监督快照扩展标识无效"
            }
            require(input.readInt() == SNAPSHOT_EXTENSION_VERSION) {
                "App 监督快照扩展版本无效"
            }
            readEnforcementState(input, rules, schemaVersion)
        }
        AppSupervisionRuntimeSnapshot(
            rules = rules,
            states = states,
            savedAtEpochMillis = savedAtEpochMillis,
            enforcementState = enforcementState,
            dailyUsageStates = dailyUsageStates,
            triggerRules = triggerRules
        )
    }

    private fun writeEnforcementState(
        output: DataOutputStream,
        state: AppSupervisionEnforcementState
    ) {
        val trustedBlock = state.trustedBlock
        output.writeBoolean(trustedBlock != null)
        if (trustedBlock != null) {
            writeString(output, trustedBlock.rule.planId)
            output.writeLong(trustedBlock.remainingRestMillis)
            output.writeInt(trustedBlock.reason.ordinal)
        }
        output.writeLong(state.validUntilEpochMillis)
        output.writeLong(state.lastConfirmedElapsedMillis)
        output.writeBoolean(state.unavailableSinceElapsedMillis != null)
        state.unavailableSinceElapsedMillis?.let(output::writeLong)
        output.writeBoolean(state.recoveryRequested)
    }

    private fun readEnforcementState(
        input: DataInputStream,
        rules: List<AppSupervisionRule>,
        schemaVersion: Int
    ): AppSupervisionEnforcementState {
        val trustedBlock = if (input.readBoolean()) {
            val planId = readString(input)
            val rule = rules.firstOrNull { it.planId == planId }
                ?: throw IOException("App 监督阻止规则不存在")
            BlockedAppSupervision(
                rule = rule,
                remainingRestMillis = input.readLong(),
                reason = if (schemaVersion >= 2) {
                    AppSupervisionBlockReason.entries.getOrNull(input.readInt())
                        ?: throw IOException("App 监督阻止原因无效")
                } else {
                    AppSupervisionBlockReason.REST
                }
            )
        } else {
            null
        }
        val validUntilEpochMillis = input.readLong()
        val lastConfirmedElapsedMillis = input.readLong()
        val unavailableSinceElapsedMillis = if (input.readBoolean()) input.readLong() else null
        val recoveryRequested = input.readBoolean()
        return AppSupervisionEnforcementState(
            trustedBlock = trustedBlock,
            validUntilEpochMillis = validUntilEpochMillis,
            lastConfirmedElapsedMillis = lastConfirmedElapsedMillis,
            unavailableSinceElapsedMillis = unavailableSinceElapsedMillis,
            recoveryRequested = recoveryRequested
        )
    }

    private fun writeRules(output: DataOutputStream, rules: List<AppSupervisionRule>) {
        require(rules.size <= MAX_APP_SUPERVISION_RULES) { "App 监督规则过多" }
        require(rules.map(AppSupervisionRule::planId).distinct().size == rules.size) {
            "App 监督规则编号重复"
        }
        output.writeInt(rules.size)
        rules.forEach { rule ->
            writeString(output, rule.planId)
            output.writeLong(rule.planUpdatedAtEpochMillis)
            writeString(output, rule.planName)
            writeString(output, rule.packageName)
            output.writeLong(rule.occurrenceEndEpochMillis)
            output.writeLong(rule.usageAllowanceMillis)
            output.writeLong(rule.restDurationMillis)
            output.writeLong(rule.dailyUsageLimitMillis)
            output.writeLong(rule.dailyUsageDateEpochDay)
            output.writeLong(rule.dailyUsageResetAtEpochMillis)
            output.writeBoolean(rule.isExecutionWindowActive)
            output.writeLong(rule.cycleOccurrenceEndEpochMillis)
            output.writeLong(rule.disabledUntilEpochMillis)
        }
    }

    private fun readRules(input: DataInputStream, schemaVersion: Int): List<AppSupervisionRule> {
        val count = readBoundedCount(input)
        return List(count) {
            val planId = readString(input)
            val planUpdatedAtEpochMillis = input.readLong()
            val planName = readString(input)
            val packageName = readString(input)
            val occurrenceEndEpochMillis = input.readLong()
            val usageAllowanceMillis = input.readLong()
            val restDurationMillis = input.readLong()
            AppSupervisionRule(
                planId = planId,
                planUpdatedAtEpochMillis = planUpdatedAtEpochMillis,
                planName = planName,
                packageName = packageName,
                occurrenceEndEpochMillis = occurrenceEndEpochMillis,
                usageAllowanceMillis = usageAllowanceMillis,
                restDurationMillis = restDurationMillis,
                dailyUsageLimitMillis = if (schemaVersion >= 2) input.readLong()
                else MAX_LEGACY_DAILY_USAGE_MILLIS,
                dailyUsageDateEpochDay = if (schemaVersion >= 2) input.readLong() else 0L,
                dailyUsageResetAtEpochMillis = if (schemaVersion >= 2) input.readLong()
                else Long.MAX_VALUE,
                isExecutionWindowActive = if (schemaVersion >= 2) input.readBoolean() else true,
                cycleOccurrenceEndEpochMillis = if (schemaVersion >= 2) input.readLong()
                else occurrenceEndEpochMillis,
                disabledUntilEpochMillis = if (schemaVersion >= 2) input.readLong() else 0L
            )
        }.also { rules ->
            require(rules.map(AppSupervisionRule::planId).distinct().size == rules.size) {
                "App 监督规则编号重复"
            }
        }
    }

    private fun writeDailyUsageStates(
        output: DataOutputStream,
        states: List<AppSupervisionDailyUsageState>
    ) {
        require(states.size <= MAX_APP_SUPERVISION_RULES) { "App 每日累计状态过多" }
        require(states.map(AppSupervisionDailyUsageState::planId).distinct().size == states.size) {
            "App 每日累计状态编号重复"
        }
        output.writeInt(states.size)
        states.forEach { state ->
            writeString(output, state.planId)
            output.writeLong(state.planUpdatedAtEpochMillis)
            output.writeLong(state.localDateEpochDay)
            output.writeLong(state.remainingUsageMillis)
        }
    }

    private fun writeTriggerRules(output: DataOutputStream, rules: List<AppTriggerRule>) {
        require(rules.size <= MAX_APP_SUPERVISION_RULES) { "App 触发规则过多" }
        require(rules.map(AppTriggerRule::planId).distinct().size == rules.size) {
            "App 触发规则编号重复"
        }
        output.writeInt(rules.size)
        rules.forEach { rule ->
            writeString(output, rule.planId)
            output.writeLong(rule.planUpdatedAtEpochMillis)
            writeString(output, rule.planName)
            output.writeLong(rule.occurrenceEndEpochMillis)
            output.writeInt(rule.packageNames.size)
            rule.packageNames.sorted().forEach { writeString(output, it) }
        }
    }

    private fun readTriggerRules(input: DataInputStream): List<AppTriggerRule> {
        val count = readBoundedCount(input)
        return List(count) {
            val planId = readString(input)
            val planUpdatedAt = input.readLong()
            val planName = readString(input)
            val occurrenceEnd = input.readLong()
            val packageCount = readBoundedCount(input)
            AppTriggerRule(
                planId = planId,
                planUpdatedAtEpochMillis = planUpdatedAt,
                planName = planName,
                packageNames = List(packageCount) { readString(input) }.toSet(),
                occurrenceEndEpochMillis = occurrenceEnd
            )
        }.also { rules ->
            require(rules.map(AppTriggerRule::planId).distinct().size == rules.size) {
                "App 触发规则编号重复"
            }
        }
    }

    private fun readDailyUsageStates(input: DataInputStream): List<AppSupervisionDailyUsageState> {
        val count = readBoundedCount(input)
        return List(count) {
            AppSupervisionDailyUsageState(
                planId = readString(input),
                planUpdatedAtEpochMillis = input.readLong(),
                localDateEpochDay = input.readLong(),
                remainingUsageMillis = input.readLong()
            )
        }.also { states ->
            require(states.map(AppSupervisionDailyUsageState::planId).distinct().size == states.size) {
                "App 每日累计状态编号重复"
            }
        }
    }

    private fun <T> encodeEnvelope(
        payloadType: Int,
        writer: (DataOutputStream) -> T
    ): ByteArray {
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeInt(payloadType)
            writer(output)
        }
        val payload = payloadBuffer.toByteArray()
        require(payload.size <= MAX_PAYLOAD_BYTES) { "App 监督载荷过大" }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return payload + digest
    }

    private fun <T> decodeEnvelope(
        bytes: ByteArray,
        expectedPayloadType: Int,
        reader: (DataInputStream, Int) -> T
    ): T? {
        if (bytes.size <= DIGEST_BYTES || bytes.size > MAX_PAYLOAD_BYTES + DIGEST_BYTES) return null
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val storedDigest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        val actualDigest = try {
            MessageDigest.getInstance("SHA-256").digest(payload)
        } catch (_: RuntimeException) {
            return null
        }
        if (!MessageDigest.isEqual(storedDigest, actualDigest)) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC) { "App 监督载荷标识无效" }
                val schemaVersion = input.readInt()
                require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) {
                    "App 监督载荷版本无效"
                }
                require(input.readInt() == expectedPayloadType) { "App 监督载荷类型无效" }
                val value = reader(input, schemaVersion)
                require(input.read() == -1) { "App 监督载荷包含尾随数据" }
                value
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_STRING_BYTES) { "App 监督文本长度无效" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_STRING_BYTES) { "App 监督文本长度无效" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "App 监督文本编码无效" }
        return value
    }

    private fun readBoundedCount(input: DataInputStream): Int = input.readInt().also { count ->
        require(count in 0..MAX_APP_SUPERVISION_RULES) { "App 监督条目数量无效" }
    }

    private const val MAGIC = 0x43464150
    private const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    private const val SCHEMA_VERSION = 3
    private const val PAYLOAD_RULES = 1
    private const val PAYLOAD_SNAPSHOT = 2
    private const val PAYLOAD_DAILY_USAGE = 3
    private const val PAYLOAD_TRIGGER_RULES = 4
    private const val SNAPSHOT_EXTENSION_MAGIC = 0x454E4653
    private const val SNAPSHOT_EXTENSION_VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_STRING_BYTES = 1_024
    private const val MAX_PAYLOAD_BYTES = 1_048_576
    private const val MAX_LEGACY_DAILY_USAGE_MILLIS = 24L * 60L * 60_000L
}

const val MAX_APP_SUPERVISION_RULES = 128
