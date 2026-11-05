package cn.elder.assistant.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import cn.elder.assistant.core.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

@Suppress("DEPRECATION")
class ScreenReader(private val service:AccessibilityService,private val screenBounds:()->Rect = { service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }) {
    fun capture():Snapshot? {
        val root=service.rootInActiveWindow ?: return null
        val windowId=root.windowId
        val pkg=root.packageName?.toString().orEmpty()
        if(pkg==service.packageName) { root.recycle();return null }
        val bounds=screenBounds()
        val id="snap_${UUID.randomUUID().toString().take(12)}"
        val candidates=mutableListOf<UiNode>()
        val queue=ArrayDeque<Pair<AccessibilityNodeInfo,Int>>()
        queue.add(root to 0)
        val reasons=linkedSetOf<String>()
        var visited=0;var empty=0
        val traversalLimit=3000;val nodeLimit=700;val depthLimit=50
        while(queue.isNotEmpty()) {
            val (node,depth)=queue.removeFirst()
            try {
                if(visited>=traversalLimit) { reasons.add("traversal_limit");continue }
                visited++
                val rect=Rect();node.getBoundsInScreen(rect)
                val box=Box(rect.left.coerceAtLeast(0),rect.top.coerceAtLeast(0),rect.right.coerceAtMost(bounds.width()),rect.bottom.coerceAtMost(bounds.height()))
                // Never store editable values, password text, or their descriptions/hints.
                val sensitive=node.isPassword || node.isEditable
                val text=if(sensitive) "" else node.text?.toString()?.take(300).orEmpty()
                val description=if(sensitive) "" else node.contentDescription?.toString()?.take(300).orEmpty()
                val hint=if(sensitive) "" else node.hintText?.toString()?.take(300).orEmpty()
                val meaningful=node.isClickable || node.isEditable || text.isNotBlank() || description.isNotBlank() || hint.isNotBlank()
                if(node.isVisibleToUser && box.valid(bounds.width(),bounds.height())) {
                    if(meaningful) candidates.add(UiNode("${id}_${visited}",text,description,node.className?.toString().orEmpty(),node.isClickable,box,node.isEditable,node.isFocused,hint,node.viewIdResourceName.orEmpty()))
                    else empty++
                }
                if(depth>=depthLimit && node.childCount>0) reasons.add("depth_limit")
                else if(visited<traversalLimit) for(i in 0 until node.childCount) {
                    if(visited+queue.size>=traversalLimit) { reasons.add("traversal_limit");break }
                    node.getChild(i)?.let { queue.add(it to depth+1) }
                }
                else if(node.childCount>0) reasons.add("traversal_limit")
            } finally { node.recycle() }
        }
        // Retain actionable controls before passive text if the meaningful-node budget is exhausted.
        if(candidates.size>nodeLimit) reasons.add("meaningful_node_limit")
        val nodes=candidates.sortedByDescending { if(it.clickable || it.editable) 1 else 0 }.take(nodeLimit)
        val stats=JSONObject().put("visited",visited).put("collected",nodes.size).put("meaningful_candidates",candidates.size)
            .put("empty_containers_skipped",empty).put("traversal_limit",traversalLimit).put("node_limit",nodeLimit).put("depth_limit",depthLimit)
            .put("truncation_reasons",JSONArray(reasons.toList())).put("input_values_redacted",true)
        return Snapshot(id,pkg,windowId,bounds.width(),bounds.height(),nodes,SystemClock.elapsedRealtime(),reasons.isNotEmpty(),stats)
    }
}
