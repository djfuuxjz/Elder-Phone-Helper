package cn.elder.assistant.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import cn.elder.assistant.core.Snapshot
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService

object SnapshotConsistency {
    fun matches(expected: Snapshot, actual: Snapshot?) = actual!=null && expected.fingerprint==actual.fingerprint
}

class Screenshotter(private val service:AccessibilityService,private val reader:ScreenReader,private val worker:ExecutorService) {
    private val handler=Handler(Looper.getMainLooper())
    fun capture(snapshot:Snapshot, hide:()->Unit, valid:()->Boolean, callback:(Result<ByteArray>)->Unit) {
        hide()
        handler.postDelayed({
            if(!valid()) return@postDelayed
            if(!SnapshotConsistency.matches(snapshot,reader.capture())) { callback(Result.failure(IllegalStateException("截图前页面已变化"))); return@postDelayed }
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object:AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode:Int) { callback(Result.failure(IllegalStateException("截图失败，系统错误码 $errorCode"))) }
                override fun onSuccess(result:AccessibilityService.ScreenshotResult) {
                    if(!valid()) { result.hardwareBuffer.close();return }
                    val software=try {
                        val hardware=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)
                        try { hardware?.copy(Bitmap.Config.ARGB_8888,false) ?: error("截图转换失败") }
                        finally { hardware?.recycle(); result.hardwareBuffer.close() }
                    } catch(e:Exception) { callback(Result.failure(e)); return }
                    try { worker.execute {
                        val encoded=runCatching {
                            require(software.width==snapshot.width && software.height==snapshot.height) { "截图与屏幕尺寸不一致" }
                            ByteArrayOutputStream().use { out -> check(software.compress(Bitmap.CompressFormat.JPEG,85,out)); out.toByteArray() }
                        }
                        software.recycle()
                        handler.post {
                            if(!valid()) return@post
                            if(!SnapshotConsistency.matches(snapshot,reader.capture())) callback(Result.failure(IllegalStateException("截图过程中页面已变化")))
                            else callback(encoded)
                        }
                    } } catch(e:java.util.concurrent.RejectedExecutionException) { software.recycle();if(valid()) callback(Result.failure(e)) }
                }
            })
        },250)
    }
}
