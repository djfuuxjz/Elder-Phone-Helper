package cn.elder.assistant.guidance

import android.os.Handler

/** Owned by the main thread: a previous run must never hide a new run's overlay. */
class SuccessNotice(private val handler:Handler, private val display:()->Unit, private val hide:()->Unit) {
    private var pending:Runnable?=null
    fun show() { cancel(); display(); pending=Runnable { pending=null;hide() }.also { handler.postDelayed(it,1000) } }
    fun cancel() { pending?.let { handler.removeCallbacks(it) };pending=null }
}
