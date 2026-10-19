package cn.elder.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.*
import android.widget.*
import cn.elder.assistant.ui.*
import cn.elder.assistant.overlay.GuideOverlay
import cn.elder.assistant.accessibility.GuideService
import cn.elder.assistant.core.Box
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WarmPresentationTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun render(v:View,w:Int,h:Int,name:String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        val dir=File("../docs/design/ui-0.1.7").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        assertTrue(bitmap.getPixel(w/2,h/2)!=0);bitmap.recycle()
    }
    @Test fun actualActivityViewsRenderAndKeepAllEntrances() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val root=c.get().findViewById<View>(android.R.id.content)
            render(root,822,1782,"task")
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,all(root).filterIsInstance<CatView>().single().importantForAccessibility)
            all(root).filterIsInstance<Button>().single { it.text=="我的" }.performClick()
            render(root,822,1782,"mine")
            val labels=all(root).filterIsInstance<Button>().map { it.text.toString() }
            assertTrue(labels.containsAll(listOf("无障碍设置","保存模型配置","删除已保存密钥","流程库 · 开发维护","Debug · 开发日志")))
        }
        for((cls,name) in listOf(DebugActivity::class.java to "debug",WorkflowActivity::class.java to "workflow")) {
            Robolectric.buildActivity(cls).setup().use { c -> render(c.get().findViewById(android.R.id.content),822,1782,name) }
        }
    }
    @Test @Config(qualifiers="w320dp-h480dp-xhdpi")
    fun smallScreenLargeFontHomeKeepsNavigationAndScrollableTask() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val activity=c.get()
            val config=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=2f }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config,activity.resources.displayMetrics)
            c.recreate()
            val root=c.get().findViewById<View>(android.R.id.content)
            render(root,640,960,"task-large-font-small-screen")
            val buttons=all(root).filterIsInstance<Button>()
            for(label in listOf("任务","我的")) {
                val button=buttons.single { it.text==label }
                val rect=android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(rect));assertEquals(button.height,rect.height())
                assertTrue(button.height>=WarmStyle.dp(c.get(),48))
            }
            val task=buttons.single { it.text=="给女儿打视频电话" }
            assertTrue(task.height>=task.layout.height+task.paddingTop+task.paddingBottom)
            assertTrue(all(root).filterIsInstance<ScrollView>().single().getChildAt(0).height>400)
        }
    }
    @Test fun largeFontOverlayKeepsFullMessageAndReachableButtons() {
        val c=Robolectric.buildService(GuideService::class.java).create()
        try {
            val service=c.get()
            val config=android.content.res.Configuration(service.resources.configuration).apply { fontScale=2f }
            @Suppress("DEPRECATION")
            service.resources.updateConfiguration(config,service.resources.displayMetrics)
            val views=linkedMapOf<View,WindowManager.LayoutParams>();val real=service.getSystemService(WindowManager::class.java)
            val wm=Proxy.newProxyInstance(WindowManager::class.java.classLoader,arrayOf(WindowManager::class.java)) { _,method,args ->
                when(method.name) {
                    "addView","updateViewLayout" -> { views[args!![0] as View]=args[1] as WindowManager.LayoutParams;null }
                    "removeViewImmediate" -> { views.remove(args!![0]);null }
                    else -> method.invoke(real,*(args ?: emptyArray()))
                }
            } as WindowManager
            var stops=0;var retries=0
            val overlay=GuideOverlay(service,{stops++},{retries++},wm)
            val message="请点击左上角的返回按钮，然后在微信中找到搜索入口。".repeat(12)
            overlay.show(message,Box(10,10,60,60),true,"从当前页开始")
            assertEquals(2,views.size)
            assertTrue(views.values.first().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
            val card=views.keys.last();val lp=views.getValue(card)
            render(card,lp.width,lp.height,"overlay-large-font")
            val text=all(card).filterIsInstance<TextView>().single { it.text==message }
            assertEquals(Int.MAX_VALUE,text.maxLines)
            val scroll=all(card).filterIsInstance<ScrollView>().single()
            assertTrue(text.measuredHeight>scroll.height)
            for(button in all(card).filterIsInstance<Button>()) {
                assertTrue(button.height>=WarmStyle.dp(service,48))
                val bounds=android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(bounds));assertEquals(button.height,bounds.height())
            }
            all(card).filterIsInstance<Button>().single { it.text=="从当前页开始" }.performClick();assertEquals(1,retries)
            all(card).filterIsInstance<Button>().single { it.text=="停止" }.performClick();assertEquals(1,stops);assertTrue(views.isEmpty())
            config.fontScale=1f
            @Suppress("DEPRECATION")
            service.resources.updateConfiguration(config,service.resources.displayMetrics)
            overlay.show("请点击微信的搜索按钮",retry=true)
            val normal=views.keys.last();val normalParams=views.getValue(normal)
            render(normal,normalParams.width,normalParams.height,"overlay")
            overlay.hide()
        } finally { c.destroy() }
    }
}
