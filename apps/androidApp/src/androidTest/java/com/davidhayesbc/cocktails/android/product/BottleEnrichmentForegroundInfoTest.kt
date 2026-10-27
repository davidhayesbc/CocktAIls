package com.davidhayesbc.cocktails.android.product

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottleEnrichmentForegroundInfoTest {
    @Test
    fun localInferenceForegroundNoticeUsesValidIconWithoutRequestingNotificationPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = createBottleProcessingForegroundInfo(context)
        val icon = info.notification.smallIcon

        assertNotNull("Foreground notification must have a small icon", icon)
        assertEquals(R.drawable.ic_stat_bottle, icon!!.resId)
        assertNotNull(context.getDrawable(R.drawable.ic_stat_bottle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            assertFalse(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS))
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val service = context.packageManager.getServiceInfo(
                ComponentName(context.packageName, "androidx.work.impl.foreground.SystemForegroundService"),
                PackageManager.GET_META_DATA,
            )
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        }
    }
}
