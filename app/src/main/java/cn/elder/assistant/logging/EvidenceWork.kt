package cn.elder.assistant.logging

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** Count queued as well as active writes so export/cleanup cannot race asynchronous evidence. */
object EvidenceWork {
    private val count=AtomicInteger()
    val pending:Boolean get()=count.get()>0
    fun submit(executor:ExecutorService,work:()->Unit) {
        count.incrementAndGet()
        try { executor.execute { try { work() } finally { count.decrementAndGet() } } }
        catch(e:RuntimeException) { count.decrementAndGet();throw e }
    }
}
