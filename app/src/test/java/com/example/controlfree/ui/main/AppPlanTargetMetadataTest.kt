package com.example.controlfree.ui.main

import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.SupervisionPolicy
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPlanTargetMetadataTest {
    @Test
    fun `目标包只包含App计划并保持去重顺序`() {
        val plans = listOf(
            appPlan("video-one", "com.example.video"),
            globalPlan("global"),
            appPlan("music", "com.example.music"),
            appPlan("video-two", "com.example.video")
        )

        assertEquals(
            linkedSetOf("com.example.video", "com.example.music"),
            appPlanTargetPackages(plans)
        )
    }

    @Test
    fun `解析失败的目标也会被记录避免页面重建后重复查询`() {
        val requestedPackages = linkedSetOf("com.example.video", "com.example.removed")
        val video = AllowedApp("com.example.video", "视频")

        val metadata = AppPlanTargetMetadata().resolve(
            requestedPackages = requestedPackages,
            resolvedApps = listOf(video)
        )

        assertEquals(requestedPackages, metadata.resolvedPackages)
        assertEquals(mapOf(video.packageName to video), metadata.appsByPackage)
        assertTrue(metadata.unresolvedTargets(requestedPackages).isEmpty())
    }

    @Test
    fun `删除任务后清理无关元数据并只加载新增目标`() {
        val video = AllowedApp("com.example.video", "视频")
        val music = AllowedApp("com.example.music", "音乐")
        val initial = AppPlanTargetMetadata().resolve(
            requestedPackages = linkedSetOf(video.packageName, music.packageName),
            resolvedApps = listOf(video, music)
        )

        val retainedTargets = linkedSetOf(music.packageName, "com.example.reader")
        val retained = initial.retainTargets(retainedTargets)

        assertEquals(mapOf(music.packageName to music), retained.appsByPackage)
        assertEquals(setOf(music.packageName), retained.resolvedPackages)
        assertEquals(
            setOf("com.example.reader"),
            retained.unresolvedTargets(retainedTargets)
        )
    }

    private fun appPlan(id: String, packageName: String) = plan(
        id = id,
        type = SupervisionPlanType.APP,
        policy = AppRulePolicy(
            packageName = packageName,
            usageAllowance = Duration.ofMinutes(30),
            restDuration = Duration.ofMinutes(10)
        )
    )

    private fun globalPlan(id: String) = plan(
        id = id,
        type = SupervisionPlanType.GLOBAL,
        policy = GlobalCyclePolicy(
            usageDuration = Duration.ofMinutes(30),
            lockDuration = Duration.ofMinutes(5)
        )
    )

    private fun plan(
        id: String,
        type: SupervisionPlanType,
        policy: SupervisionPolicy
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = type,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
        ),
        policy = policy,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )
}
