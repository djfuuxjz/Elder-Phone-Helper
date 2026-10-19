package cn.elder.assistant

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import cn.elder.assistant.guidance.TargetAppLauncher
import cn.elder.assistant.accessibility.GuideService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TargetAppLauncherTest {
    @Test fun missingWeChatReturnsActionableFailure() {
        val service=Robolectric.buildService(GuideService::class.java).create()
        try { val result=TargetAppLauncher.open(service.get());assertTrue(result.isFailure);assertTrue(result.exceptionOrNull()!!.message!!.contains("微信"));assertNull(shadowOf(service.get()).nextStartedActivity) } finally { service.destroy() }
    }
    @Test fun startsWeChatWithoutDesktopIconOrAccessibilityClick() {
        val service=Robolectric.buildService(GuideService::class.java).create()
        try {
            val context=service.get()
            val query=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.tencent.mm")
            val info=ResolveInfo().apply { activityInfo=ActivityInfo().apply { packageName="com.tencent.mm";name="com.tencent.mm.ui.LauncherUI";enabled=true;exported=true } }
            shadowOf(context.packageManager).addResolveInfoForIntent(query,info)
            assertTrue(TargetAppLauncher.open(context).isSuccess)
            val launched=shadowOf(context).nextStartedActivity
            assertEquals("com.tencent.mm",launched.component!!.packageName)
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
        } finally { service.destroy() }
    }
}
