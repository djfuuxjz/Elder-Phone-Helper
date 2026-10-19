package cn.elder.assistant

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import cn.elder.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import cn.elder.assistant.core.AppRuntime
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MainActivityTest {
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun homeContainsOnlyFixedTaskAndTwoTabs() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val texts=all(c.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("任务","我的","给女儿打视频电话")))
            assertFalse(texts.any { it.contains("登录") || it.contains("语音输入") })
        }
    }
    @Test fun mineShowsPermissionAndModelConfiguration() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            all(c.get().window.decorView).filterIsInstance<Button>().first { it.text=="我的" }.performClick()
            val texts=all(c.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("保存模型配置")); assertTrue(texts.contains("无障碍设置")); assertTrue(texts.contains("deepseek-flash"))
        }
    }
    @Test fun permissionStatusRefreshesAfterReturningFromSettings() {
        AppRuntime.connected=false
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            all(c.get().window.decorView).filterIsInstance<Button>().first { it.text=="我的" }.performClick()
            c.pause();AppRuntime.connected=true;c.resume();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(all(c.get().window.decorView).filterIsInstance<TextView>().any { it.text=="无障碍服务已连接" })
        }
        AppRuntime.connected=false
    }
}
