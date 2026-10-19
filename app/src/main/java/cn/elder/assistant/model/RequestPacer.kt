package cn.elder.assistant.model

import java.io.IOException

/** One process-wide send gate, also shared by retries and newly started sessions. */
class RequestPacer(private val clock:()->Long={ System.nanoTime()/1_000_000 },private val sleep:(Long)->Unit={ Thread.sleep(it) }) {
    private var lastStart:Long?=null
    @Synchronized fun <T> start(cancelled:()->Boolean, action:()->T):T {
        while(true) {
            if(cancelled()) throw IOException("请求已取消，未发送")
            val remaining=lastStart?.let { 1000-(clock()-it) } ?: 0
            if(remaining<=0) break
            sleep(minOf(remaining,50))
        }
        if(cancelled()) throw IOException("请求已取消，未发送")
        val result=action()
        lastStart=clock()
        return result
    }
    companion object { val shared=RequestPacer() }
}
