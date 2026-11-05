package cn.elder.assistant.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import cn.elder.assistant.core.Snapshot
import cn.elder.assistant.model.RuleFailure
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService

object SnapshotConsistency {
    fun matches(expected:Snapshot,actual:Snapshot?)=actual!=null && expected.fingerprint==actual.fingerprint
}
data class CapturedImage(val bytes:ByteArray,val width:Int,val height:Int,val capturedAt:Long,val rotation:Int)
@Suppress("DEPRECATION")
class Screenshotter(private val service:AccessibilityService,private val reader:ScreenReader,private val worker:ExecutorService) {
    private val handler=Handler(Looper.getMainLooper())
    fun capture(snapshot:Snapshot,hide:()->Unit,valid:()->Boolean,callback:(Result<ByteArray>)->Unit) =
        captureFrame(snapshot,hide,valid) { callback(it.map { frame -> frame.bytes }) }
    fun captureFrame(snapshot:Snapshot,hide:()->Unit,valid:()->Boolean,callback:(Result<CapturedImage>)->Unit) {
        hide()
        fun changed(whenText:String)=RuleFailure("capture","SNAPSHOT_CHANGED","stable_capture","同一窗口、页面、方向",whenText,"截图$whenText 页面已变化，重新观察")
        handler.postDelayed({
            if(!valid()) return@postDelayed
            if(!SnapshotConsistency.matches(snapshot,reader.capture())) { callback(Result.failure(changed("前")));return@postDelayed }
            try { service.takeScreenshot(Display.DEFAULT_DISPLAY,service.mainExecutor,object:AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode:Int) {
                    val reason=when(errorCode) { AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR->"系统内部错误"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS->"缺少截图权限"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT->"截图间隔太短"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY->"显示屏无效"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW->"窗口无效"
                        else->if(errorCode==AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "安全窗口禁止截图" else "未知系统错误" }
                    if(valid()) callback(Result.failure(RuleFailure("capture","SCREENSHOT_SYSTEM_$errorCode","take_screenshot","系统允许截图",errorCode.toString(),"截图失败：$reason（$errorCode）")))
                }
                override fun onSuccess(result:AccessibilityService.ScreenshotResult) {
                    if(!valid()) { result.hardwareBuffer.close();return }
                    val at=SystemClock.elapsedRealtime()
                    val software=try {
                        val hardware=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)
                        try { hardware?.copy(Bitmap.Config.ARGB_8888,false) ?: error("截图转换失败：hardware bitmap 为空") }
                        finally { hardware?.recycle();result.hardwareBuffer.close() }
                    } catch(e:Exception) { callback(Result.failure(e));return }
                    // Both tree reads bracket the screenshot before compression/disk I/O.
                    if(!SnapshotConsistency.matches(snapshot,reader.capture())) { software.recycle();callback(Result.failure(changed("后")));return }
                    val rotation=service.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation
                    try { worker.execute {
                        val encoded=runCatching {
                            val w=software.width;val h=software.height
                            require(w.toLong()*snapshot.height==h.toLong()*snapshot.width) { "截图与屏幕方向或宽高比例不一致：image=${w}x$h, screen=${snapshot.width}x${snapshot.height}" }
                            ByteArrayOutputStream().use { out ->
                                check(software.compress(Bitmap.CompressFormat.JPEG,85,out)) { "截图 JPEG 编码失败" }
                                CapturedImage(out.toByteArray(),w,h,at,rotation)
                            }
                        }
                        software.recycle()
                        handler.post { if(valid()) callback(encoded) }
                    } } catch(e:java.util.concurrent.RejectedExecutionException) { software.recycle();if(valid()) callback(Result.failure(e)) }
                }
            }) } catch(e:Exception) { if(valid()) callback(Result.failure(e)) }
        },100)
    }
}
