package com.example.controlfree.ui.usage

import com.example.controlfree.data.AppUsageDetails
import com.example.controlfree.data.UsageOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageDetailSelectionTest {
    @Test
    fun `只有详情仍存在时才返回可见详情`() {
        val details = AppUsageDetails(
            packageName = PACKAGE_NAME,
            label = "测试 App",
            days = emptyList()
        )
        val overview = UsageOverview(
            todayApps = emptyList(),
            lastSevenDays = emptyList(),
            appDetailsByPackage = mapOf(PACKAGE_NAME to details)
        )

        assertEquals(details, resolveSelectedUsageDetails(overview, PACKAGE_NAME))
        assertNull(resolveSelectedUsageDetails(overview, "missing.package"))
        assertNull(resolveSelectedUsageDetails(null, PACKAGE_NAME))
        assertNull(resolveSelectedUsageDetails(overview, null))
    }

    private companion object {
        const val PACKAGE_NAME = "example.app"
    }
}
