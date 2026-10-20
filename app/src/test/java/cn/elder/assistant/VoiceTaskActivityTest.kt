package cn.elder.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.*
import android.widget.*
import cn.elder.assistant.core.*
import cn.elder.assistant.ui.MainActivity
import cn.elder.assistant.speech.VoiceTaskInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
class VoiceTaskActivityTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun button(a:Activity,text:String)=all(a.window.decorView).filterIsInstance<Button>().single { it.text==text }
    private fun input(a:Activity)=all(a.window.decorView).filterIsInstance<EditText>().single { it.contentDescription=="自定义任务内容" }
    private fun result(a:MainActivity,code:Int,data:Intent?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
            .invoke(a,VoiceTaskInput.REQUEST,code,data)
    }
    private fun data(text:String)=Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf(text))
    @After fun reset() { AppRuntime.active=false;AppRuntime.connected=false;AppRuntime.start=null;AppRuntime.startCustom=null }
    @Test fun speechUsesChineseRecognizerAndNeverStartsUntilConfirmed() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            var started:TaskGoal?=null;AppRuntime.startCustom={started=it};AppRuntime.connected=true
            c.get().getSharedPreferences("model_config",0).edit().putString("encrypted_key","test-presence-only").commit()
            button(c.get(),"语音输入任务").performClick()
            val request=shadowOf(c.get()).nextStartedActivityForResult
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,request.intent.action)
            assertEquals("zh-CN",request.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            result(c.get(),Activity.RESULT_OK,data("打开设置调大字体"))
            assertEquals("打开设置调大字体",input(c.get()).text.toString());assertNull(started)
            input(c.get()).setText("打开相机")
            button(c.get(),"开始引导").performClick();assertNull(started)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("打开相机",started!!.goal);assertFalse(started!!.fixed)
        }
    }
    @Test fun cancellationEmptyResultsAndUnsolicitedResultsPreserveDraft() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            input(c.get()).setText("原任务")
            result(c.get(),Activity.RESULT_OK,data("不应接受"));assertEquals("原任务",input(c.get()).text.toString())
            button(c.get(),"语音输入任务").performClick();result(c.get(),Activity.RESULT_CANCELED,null)
            assertEquals("原任务",input(c.get()).text.toString())
            button(c.get(),"语音输入任务").performClick();result(c.get(),Activity.RESULT_OK,data("  "))
            assertEquals("原任务",input(c.get()).text.toString())
            assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("没有听清"))
        }
    }
    @Test fun draftSurvivesTabChangesAndActivityRecreation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            input(c.get()).setText("给妈妈发消息")
            button(c.get(),"我的").performClick();button(c.get(),"任务").performClick()
            assertEquals("给妈妈发消息",input(c.get()).text.toString())
            button(c.get(),"语音输入任务").performClick()
            c.recreate();assertEquals("给妈妈发消息",input(c.get()).text.toString())
            result(c.get(),Activity.RESULT_OK,data("打开相册"))
            assertEquals("打开相册",input(c.get()).text.toString())
        }
    }
    @Test fun activeTaskBlocksVoiceAndEmptyTaskCannotStart() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            AppRuntime.active=true;button(c.get(),"语音输入任务").performClick()
            assertNull(shadowOf(c.get()).nextStartedActivityForResult)
            AppRuntime.active=false;button(c.get(),"开始引导").performClick()
            assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("请先说出"))
        }
    }
    @Test fun keyboardSpaceMovesShortcutIntoScrollableContentWithoutDroppingInput() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            input(c.get()).setText("调大字体")
            val shortcut=button(c.get(),"给女儿打视频电话")
            val root=shortcut.parent.parent as View
            val w=View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY)
            val h=View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY)
            root.measure(w,h);root.layout(0,0,822,1782)
            root.setPadding(root.paddingLeft,root.paddingTop,root.paddingRight,1100)
            root.measure(w,h);root.layout(0,0,822,1782);root.viewTreeObserver.dispatchOnGlobalLayout()
            assertTrue(shortcut.parent.parent.parent is ScrollView)
            assertEquals("调大字体",input(c.get()).text.toString())
            root.setPadding(root.paddingLeft,root.paddingTop,root.paddingRight,16)
            root.measure(w,h);root.layout(0,0,822,1782);root.viewTreeObserver.dispatchOnGlobalLayout()
            assertSame(root,shortcut.parent.parent)
        }
    }
    @Test fun missingRecognizerKeepsTypedInputAndAllowsAnotherAttempt() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            input(c.get()).setText("打开相册")
            val app=shadowOf(c.get().application)
            app.checkActivities(true)
            try {
                button(c.get(),"语音输入任务").performClick()
                assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("没有可用的语音识别服务"))
                assertEquals("打开相册",input(c.get()).text.toString())
            } finally { app.checkActivities(false) }
            button(c.get(),"语音输入任务").performClick()
            assertNotNull(shadowOf(c.get()).nextStartedActivityForResult)
        }
    }
    @Test fun shortcutRemainsDefaultAndSitsImmediatelyAboveNavigation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            AppRuntime.connected=true;var called=false;AppRuntime.start={called=true}
            c.get().getSharedPreferences("model_config",0).edit().putString("encrypted_key","test-presence-only").commit()
            val shortcut=button(c.get(),"给女儿打视频电话")
            val dock=shortcut.parent as ViewGroup;val root=dock.parent as ViewGroup
            assertEquals(root.childCount-2,root.indexOfChild(dock))
            assertTrue(all(root.getChildAt(root.childCount-1)).filterIsInstance<Button>().map { it.text }.containsAll(listOf("任务","我的")))
            shortcut.performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(called)
        }
    }
}
