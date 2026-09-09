package com.example.controlfree

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备侧契约测试：服务必须可被系统恢复，但不能被第三方显式启动。
 * 进程被杀和 FGS 提升失败的完整场景见 docs/reliability-test-matrix.md。
 */
@RunWith(AndroidJUnit4::class)
class ServiceLifecycleContractInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun supervisionServiceUsesNonExportedSpecialUseForegroundDeclaration() {
        listOf(MonitorService::class.java, AppSupervisionService::class.java).forEach { service ->
            val info = serviceInfo(service)
            assertFalse("${service.simpleName} must not be exported", info.exported)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                assertTrue(
                    "${service.simpleName} must declare specialUse FGS type",
                    info.foregroundServiceType and
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
                )
            }
        }
    }

    @Test
    fun planReceiverIsRegisteredAndNotExported() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, "${context.packageName}.supervision.runtime.SupervisionScheduleReceiver"),
            PackageManager.GET_META_DATA
        )
        assertNotNull(receiver)
        assertFalse(receiver.exported)
    }

    private fun serviceInfo(service: Class<out Service>): ServiceInfo =
        context.packageManager.getServiceInfo(
            ComponentName(context, service),
            PackageManager.GET_META_DATA
        ).also { assertNotNull(it) }
}
