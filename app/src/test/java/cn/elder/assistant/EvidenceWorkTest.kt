package cn.elder.assistant

import cn.elder.assistant.logging.EvidenceWork
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EvidenceWorkTest {
    @Test fun queuedAndActiveEvidenceBlocksExportUntilFullyDrained() {
        val executor=Executors.newSingleThreadExecutor();val release=CountDownLatch(1);val started=CountDownLatch(1)
        try {
            EvidenceWork.submit(executor) { started.countDown();release.await() }
            assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(EvidenceWork.pending)
            EvidenceWork.submit(executor) { assertTrue(EvidenceWork.pending) }
            release.countDown();executor.submit {}.get()
            assertFalse(EvidenceWork.pending)
        } finally { release.countDown();executor.shutdown() }
    }
}
