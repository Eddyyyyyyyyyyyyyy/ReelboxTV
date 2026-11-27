package com.reelix.app.data.image

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtworkDownloadQueueTest {
    @Test fun `最后确认使旧提交失效但不影响其他作品`() {
        val gate = ArtworkCommitGate()
        val old = gate.begin("account:a")
        val other = gate.begin("account:b")
        val latest = gate.begin("account:a")
        val saved = mutableListOf<String>()
        assertFalse(old.commit { saved += "old" })
        assertTrue(other.commit { saved += "other" })
        assertTrue(latest.commit { saved += "latest" })
        assertEquals(listOf("other", "latest"), saved)
    }
    @Test fun `关闭搜索窗口后已提交任务继续完成`() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dialog = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val downloaded = CompletableDeferred<Unit>()
        var saved = false
        val queue = ArtworkDownloadQueue(process)
        dialog.launch { queue.submit("work", "作品") { ticket -> downloaded.await(); ticket.commit { saved = true } } }
        dialog.cancel()
        downloaded.complete(Unit)
        yield()
        assertTrue(saved)
        assertEquals(ArtworkDownloadPhase.SUCCEEDED, queue.states.value["work"]?.phase)
        process.cancel()
    }
    @Test fun `不可及时取消的旧下载也不能覆盖新选择`() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = ArtworkDownloadQueue(process)
        val oldDownload = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        queue.submit("work", "作品") { ticket ->
            withContext(NonCancellable) { oldDownload.await(); ticket.commit { saved += "old" } }
        }
        queue.submit("work", "作品") { ticket -> ticket.commit { saved += "new" } }
        oldDownload.complete(Unit)
        yield()
        assertEquals(listOf("new"), saved)
        assertEquals(ArtworkDownloadPhase.SUCCEEDED, queue.states.value["work"]?.phase)
        process.cancel()
    }
    @Test fun `失败保留重试操作且不会误报成功`() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = ArtworkDownloadQueue(process)
        var attempts = 0
        queue.submit("work", "作品") { ticket ->
            if (++attempts == 1) error("offline")
            ticket.commit { }
        }
        assertEquals(ArtworkDownloadPhase.FAILED, queue.states.value["work"]?.phase)
        assertTrue(queue.retry("work"))
        assertEquals(2, attempts)
        assertEquals(ArtworkDownloadPhase.SUCCEEDED, queue.states.value["work"]?.phase)
        assertFalse(queue.retry("work"))
        process.cancel()
    }
}
