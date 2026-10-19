package cn.elder.assistant

import cn.elder.assistant.ui.DebugActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CheckBox
import cn.elder.assistant.core.AppRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DebugActivityTest {
    private fun texts(v:View):List<String> = (if(v is TextView) listOf(v.text.toString()) else emptyList()) + if(v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
    private fun checkboxes(v:View):List<CheckBox> = (if(v is CheckBox) listOf(v) else emptyList()) + if(v is ViewGroup) (0 until v.childCount).flatMap { checkboxes(v.getChildAt(it)) } else emptyList()
    @Test fun debugEmptyStateExplainsHowToCreateLogs() {
        Robolectric.buildActivity(DebugActivity::class.java).setup().use { c ->
            val all=texts(c.get().window.decorView);assertTrue(all.contains("Debug · 开发调试"));assertTrue(all.any { it.contains("还没有运行日志") })
        }
    }
    @Test fun developerCanArmPreparationBeforeStartingTask() {
        AppRuntime.active=false;AppRuntime.debugWaitForStart=false
        try {
            Robolectric.buildActivity(DebugActivity::class.java).setup().use { c ->
                val box=checkboxes(c.get().window.decorView).single()
                assertTrue(box.isEnabled);box.performClick()
                assertTrue(AppRuntime.debugWaitForStart)
            }
        } finally { AppRuntime.debugWaitForStart=false }
    }
    @Test fun preparationCannotBeChangedDuringActiveRun() {
        AppRuntime.active=true
        try {
            Robolectric.buildActivity(DebugActivity::class.java).setup().use { c ->
                assertFalse(checkboxes(c.get().window.decorView).single().isEnabled)
            }
        } finally { AppRuntime.active=false }
    }
}
