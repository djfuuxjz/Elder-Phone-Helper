package cn.elder.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.elder.assistant.observation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executors

class EvidenceService:AccessibilityService() {
    var testRoot:AccessibilityNodeInfo?=null
    override fun getRootInActiveWindow():AccessibilityNodeInfo?=testRoot?.let { AccessibilityNodeInfo.obtain(it) }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@Suppress("DEPRECATION")
class ObservationEvidenceTest {
    private fun node(text:String="",clickable:Boolean=false)=AccessibilityNodeInfo.obtain().apply {
        packageName="test.app";isVisibleToUser=true;isClickable=clickable;this.text=text
        setBoundsInScreen(Rect(10,10,30,30));className="android.view.View"
    }
    @Test fun emptyContainersDoNotConsumeMeaningfulBudgetAndLateClickableSurvives() {
        val service=Robolectric.buildService(EvidenceService::class.java).create()
        try {
            val root=node();repeat(600) { shadowOf(root).addChild(node()) };shadowOf(root).addChild(node("设置",true));service.get().testRoot=root
            val s=ScreenReader(service.get()) { Rect(0,0,1200,2670) }.capture()!!
            assertEquals(1,s.nodes.size);assertEquals("设置",s.nodes.single().text)
            assertEquals(602,s.collection.getInt("visited"));assertEquals(601,s.collection.getInt("empty_containers_skipped"));assertFalse(s.truncated)
        } finally { service.destroy() }
    }
    @Test fun meaningfulBudgetPrioritizesActionsAndReportsTruncationAndInputRedaction() {
        val service=Robolectric.buildService(EvidenceService::class.java).create()
        try {
            val root=node();repeat(750) { shadowOf(root).addChild(node("标签$it")) }
            shadowOf(root).addChild(node("稍后出现的按钮",true))
            shadowOf(root).addChild(node("OTP-123456").apply { isEditable=true })
            service.get().testRoot=root
            val s=ScreenReader(service.get()) { Rect(0,0,1200,2670) }.capture()!!
            assertEquals(700,s.nodes.size);assertTrue(s.truncated);assertTrue(s.nodes.any { it.text=="稍后出现的按钮" })
            assertTrue(s.nodes.any { it.editable });assertFalse(s.json().toString().contains("OTP-123456"))
            assertTrue(s.collection.getJSONArray("truncation_reasons").toString().contains("meaningful_node_limit"))
        } finally { service.destroy() }
    }
    @Test fun screenshotPermissionFailureIsReturnedWithExplicitCodeAndReason() {
        val service=Robolectric.buildService(EvidenceService::class.java).create();val worker=Executors.newSingleThreadExecutor()
        try {
            service.get().testRoot=node("设置",true)
            val reader=ScreenReader(service.get()) { Rect(0,0,1200,2670) };val expected=reader.capture()!!
            shadowOf(service.get()).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
            var failure:Throwable?=null
            Screenshotter(service.get(),reader,worker).captureFrame(expected,{}, { true }) { failure=it.exceptionOrNull() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertNotNull(failure);assertTrue(failure!!.message!!.contains("缺少截图权限"))
        } finally { worker.shutdown();service.destroy() }
    }
}
