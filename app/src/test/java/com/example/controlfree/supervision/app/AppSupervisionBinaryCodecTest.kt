package com.example.controlfree.supervision.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionBinaryCodecTest {
    @Test
    fun `规则载荷可以无损往返`() {
        val rules = listOf(rule("video", "example.video"), rule("game", "example.game"))

        val encoded = AppSupervisionBinaryCodec.encodeRules(rules)

        assertEquals(rules, AppSupervisionBinaryCodec.decodeRules(encoded))
    }

    @Test
    fun `运行快照可以无损往返`() {
        val rules = listOf(rule("video", "example.video"), rule("game", "example.game"))
        val states = listOf(
            state(rules[0], AppSupervisionPhase.ALLOWANCE),
            state(rules[1], AppSupervisionPhase.REST)
        )
        val snapshot = AppSupervisionRuntimeSnapshot(rules, states, savedAtEpochMillis = 9_000L)

        val encoded = AppSupervisionBinaryCodec.encodeSnapshot(snapshot)

        assertEquals(snapshot, AppSupervisionBinaryCodec.decodeSnapshot(encoded))
    }

    @Test
    fun `多个App触发规则和运行快照可以无损往返`() {
        val triggerRules = listOf(
            AppTriggerRule(
                planId = "global-a",
                planUpdatedAtEpochMillis = 7L,
                planName = "应用触发监督",
                packageNames = linkedSetOf("example.video", "example.music"),
                occurrenceEndEpochMillis = 100_000L
            )
        )
        val snapshot = AppSupervisionRuntimeSnapshot(
            rules = emptyList(),
            states = emptyList(),
            savedAtEpochMillis = 9_000L,
            triggerRules = triggerRules
        )

        assertEquals(
            triggerRules,
            AppSupervisionBinaryCodec.decodeTriggerRules(
                AppSupervisionBinaryCodec.encodeTriggerRules(triggerRules)
            )
        )
        assertEquals(
            snapshot,
            AppSupervisionBinaryCodec.decodeSnapshot(
                AppSupervisionBinaryCodec.encodeSnapshot(snapshot)
            )
        )
    }

    @Test
    fun `带可信阻止状态的运行快照可以无损往返`() {
        val rule = rule("video", "example.video")
        val restState = state(rule, AppSupervisionPhase.REST)
        val enforcement = AppSupervisionEnforcementState(
            trustedBlock = BlockedAppSupervision(rule, remainingRestMillis = 45_000L),
            validUntilEpochMillis = restState.restUntilEpochMillis,
            lastConfirmedElapsedMillis = 5_000L,
            unavailableSinceElapsedMillis = 6_000L,
            recoveryRequested = true
        )
        val snapshot = AppSupervisionRuntimeSnapshot(
            rules = listOf(rule),
            states = listOf(restState),
            savedAtEpochMillis = 9_000L,
            enforcementState = enforcement
        )

        assertEquals(snapshot, AppSupervisionBinaryCodec.decodeSnapshot(
            AppSupervisionBinaryCodec.encodeSnapshot(snapshot)
        ))
    }

    @Test
    fun `不带扩展的旧快照仍可读取`() {
        val rule = rule("video", "example.video")
        val state = state(rule, AppSupervisionPhase.ALLOWANCE)
        // encodeSnapshot 在扩展字段加入前的默认格式，扩展为空时必须保持兼容。
        val legacy = AppSupervisionRuntimeSnapshot(
            rules = listOf(rule),
            states = listOf(state),
            savedAtEpochMillis = 9_000L,
            enforcementState = null
        )

        assertEquals(legacy, AppSupervisionBinaryCodec.decodeSnapshot(
            AppSupervisionBinaryCodec.encodeSnapshot(legacy)
        ))
        assertNull(legacy.enforcementState)
    }

    @Test
    fun `版本一二进制快照按旧默认值兼容读取`() {
        val legacyRule = rule("video", "example.video")
        val legacyState = state(legacyRule, AppSupervisionPhase.REST)
        val legacyEnforcement = AppSupervisionEnforcementState(
            trustedBlock = BlockedAppSupervision(legacyRule, remainingRestMillis = 40_000L),
            validUntilEpochMillis = legacyState.restUntilEpochMillis,
            lastConfirmedElapsedMillis = 5_000L,
            unavailableSinceElapsedMillis = 6_000L,
            recoveryRequested = true
        )

        val decoded = AppSupervisionBinaryCodec.decodeSnapshot(
            encodeVersionOneSnapshot(
                rule = legacyRule,
                state = legacyState,
                savedAtEpochMillis = 9_000L,
                enforcementState = legacyEnforcement
            )
        )

        val expectedRule = legacyRule.copy(
            dailyUsageLimitMillis = 24L * 60L * 60_000L,
            dailyUsageDateEpochDay = 0L,
            dailyUsageResetAtEpochMillis = Long.MAX_VALUE,
            isExecutionWindowActive = true,
            cycleOccurrenceEndEpochMillis = legacyRule.occurrenceEndEpochMillis,
            disabledUntilEpochMillis = 0L
        )
        assertEquals(
            AppSupervisionRuntimeSnapshot(
                rules = listOf(expectedRule),
                states = listOf(legacyState),
                savedAtEpochMillis = 9_000L,
                enforcementState = legacyEnforcement.copy(
                    trustedBlock = BlockedAppSupervision(
                        expectedRule,
                        remainingRestMillis = 40_000L,
                        reason = AppSupervisionBlockReason.REST
                    )
                )
            ),
            decoded
        )
        assertTrue(decoded?.triggerRules.orEmpty().isEmpty())
    }

    @Test
    fun `载荷被篡改或截断时拒绝读取`() {
        val encoded = AppSupervisionBinaryCodec.encodeRules(listOf(rule("video", "example.video")))
        val original = encoded.copyOf()
        val tampered = encoded.copyOf().apply { this[10] = (this[10].toInt() xor 0x20).toByte() }

        assertNull(AppSupervisionBinaryCodec.decodeRules(tampered))
        assertNull(AppSupervisionBinaryCodec.decodeRules(encoded.copyOf(encoded.size - 1)))
        assertArrayEquals(original, encoded)
    }

    @Test
    fun `规则和状态不匹配时拒绝构造快照`() {
        val video = rule("video", "example.video")
        val game = rule("game", "example.game")

        assertThrows(IllegalArgumentException::class.java) {
            AppSupervisionRuntimeSnapshot(
                rules = listOf(video),
                states = listOf(state(game, AppSupervisionPhase.ALLOWANCE)),
                savedAtEpochMillis = 1L
            )
        }
    }

    @Test
    fun `可信阻止必须对应规则的休息状态`() {
        val rule = rule("video", "example.video")
        val allowance = state(rule, AppSupervisionPhase.ALLOWANCE)

        assertThrows(IllegalArgumentException::class.java) {
            AppSupervisionRuntimeSnapshot(
                rules = listOf(rule),
                states = listOf(allowance),
                savedAtEpochMillis = 1L,
                enforcementState = AppSupervisionEnforcementState(
                    trustedBlock = BlockedAppSupervision(rule, 10_000L),
                    validUntilEpochMillis = 10_000L,
                    lastConfirmedElapsedMillis = 1L
                )
            )
        }
    }

    @Test
    fun `错误载荷类型不能互相解析`() {
        val snapshot = AppSupervisionRuntimeSnapshot(
            rules = listOf(rule("video", "example.video")),
            states = emptyList(),
            savedAtEpochMillis = 1L
        )

        assertNull(
            AppSupervisionBinaryCodec.decodeRules(
                AppSupervisionBinaryCodec.encodeSnapshot(snapshot)
            )
        )
        assertNull(
            AppSupervisionBinaryCodec.decodeSnapshot(
                AppSupervisionBinaryCodec.encodeRules(snapshot.rules)
            )
        )
    }

    private fun rule(id: String, packageName: String) = AppSupervisionRule(
        planId = id,
        planUpdatedAtEpochMillis = 2L,
        planName = "计划-$id",
        packageName = packageName,
        occurrenceEndEpochMillis = 100_000L,
        usageAllowanceMillis = 60_000L,
        restDurationMillis = 120_000L
    )

    private fun state(
        rule: AppSupervisionRule,
        phase: AppSupervisionPhase
    ) = AppSupervisionRuntimeState(
        planId = rule.planId,
        planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
        occurrenceEndEpochMillis = rule.occurrenceEndEpochMillis,
        phase = phase,
        remainingAllowanceMillis = if (phase == AppSupervisionPhase.REST) 0L else 30_000L,
        restUntilEpochMillis = if (phase == AppSupervisionPhase.REST) 50_000L else 0L,
        checkpointElapsedMillis = 5_000L,
        bootCount = 3,
        wasTargetForeground = true,
        wasInteractive = true
    )

    private fun encodeVersionOneSnapshot(
        rule: AppSupervisionRule,
        state: AppSupervisionRuntimeState,
        savedAtEpochMillis: Long,
        enforcementState: AppSupervisionEnforcementState? = null
    ): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x43464150)
                output.writeInt(1)
                output.writeInt(2)
                output.writeLong(savedAtEpochMillis)
                output.writeInt(1)
                output.writeLegacyString(rule.planId)
                output.writeLong(rule.planUpdatedAtEpochMillis)
                output.writeLegacyString(rule.planName)
                output.writeLegacyString(rule.packageName)
                output.writeLong(rule.occurrenceEndEpochMillis)
                output.writeLong(rule.usageAllowanceMillis)
                output.writeLong(rule.restDurationMillis)
                output.writeInt(1)
                output.writeLegacyString(state.planId)
                output.writeLong(state.planUpdatedAtEpochMillis)
                output.writeLong(state.occurrenceEndEpochMillis)
                output.writeInt(state.phase.ordinal)
                output.writeLong(state.remainingAllowanceMillis)
                output.writeLong(state.restUntilEpochMillis)
                output.writeLong(state.checkpointElapsedMillis)
                output.writeInt(state.bootCount)
                output.writeBoolean(state.wasTargetForeground)
                output.writeBoolean(state.wasInteractive)
                enforcementState?.let { enforcement ->
                    output.writeInt(0x454E4653)
                    output.writeInt(1)
                    val trustedBlock = enforcement.trustedBlock
                    output.writeBoolean(trustedBlock != null)
                    if (trustedBlock != null) {
                        output.writeLegacyString(trustedBlock.rule.planId)
                        output.writeLong(trustedBlock.remainingRestMillis)
                    }
                    output.writeLong(enforcement.validUntilEpochMillis)
                    output.writeLong(enforcement.lastConfirmedElapsedMillis)
                    output.writeBoolean(enforcement.unavailableSinceElapsedMillis != null)
                    enforcement.unavailableSinceElapsedMillis?.let(output::writeLong)
                    output.writeBoolean(enforcement.recoveryRequested)
                }
            }
        }.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun DataOutputStream.writeLegacyString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
