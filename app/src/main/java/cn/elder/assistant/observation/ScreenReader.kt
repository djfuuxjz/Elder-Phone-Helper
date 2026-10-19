package cn.elder.assistant.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import cn.elder.assistant.core.*
import java.util.UUID

class ScreenReader(private val service: AccessibilityService) {
    fun capture(): Snapshot? {
        val root=service.rootInActiveWindow ?: return null
        val packageName=root.packageName?.toString().orEmpty()
        if(packageName==service.packageName) return null
        val bounds=service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val id="snap_${UUID.randomUUID().toString().take(12)}"
        val nodes=mutableListOf<UiNode>()
        var visited=0; var truncated=false
        fun walk(node: AccessibilityNodeInfo, depth:Int) {
            if(visited++ >= 400 || depth>25) { truncated=true; return }
            val rect=Rect(); node.getBoundsInScreen(rect)
            val box=Box(rect.left.coerceAtLeast(0),rect.top.coerceAtLeast(0),rect.right.coerceAtMost(bounds.width()),rect.bottom.coerceAtMost(bounds.height()))
            if(node.isVisibleToUser && !node.isPassword && box.valid(bounds.width(),bounds.height())) {
                nodes.add(UiNode("${id}_${nodes.size}",node.text?.toString()?.take(300).orEmpty(),
                    node.contentDescription?.toString()?.take(300).orEmpty(),node.className?.toString().orEmpty(),node.isClickable,box,node.isEditable,node.isFocused,node.hintText?.toString()?.take(300).orEmpty()))
            }
            for(i in 0 until node.childCount) {
                if(visited>=400) { truncated=true; break }
                node.getChild(i)?.let { walk(it,depth+1) }
            }
        }
        walk(root,0)
        return Snapshot(id,packageName,root.windowId,bounds.width(),bounds.height(),nodes,SystemClock.elapsedRealtime(),truncated)
    }
}
