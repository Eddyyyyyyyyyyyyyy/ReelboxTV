package com.reelix.app.data.alist

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AlistScanTest {
    @Test fun `手动刷新只在目录第一页绕过服务端缓存`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = client { _, request ->
            requests += request
            val names = if (request.contains("\"page\":1,")) (1..1000) else (1001..1001)
            """{"code":200,"data":{"total":1001,"content":[${names.joinToString { """{"name":"film$it.mkv"}""" }}]}}"""
        }
        source.list("/", forceRefresh = true)
        assertTrue(requests[0].contains("\"refresh\":true"))
        assertTrue(requests[1].contains("\"refresh\":false"))
    }
    private fun client(body: (String, String) -> String) = AlistClient("http://fixture.test", OkHttpClient.Builder()
        .addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request().url.encodedPath, buffer.readUtf8()).toResponseBody()).build()
        }.build())
    @Test fun `超过一千项的目录读取所有分页`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = client { _, request ->
            requests += request
            val names = if (request.contains("\"page\":1,")) (1..1000) else (1001..1001)
            """{"code":200,"data":{"total":1001,"content":[${names.joinToString { """{"name":"film$it.mkv"}""" }}]}}"""
        }
        assertEquals(1001, source.list("/").size)
        assertEquals(2, requests.size)
    }
    @Test fun `根目录影片与挂载点保留明确边界`() = runBlocking {
        val source = client { _, request ->
            val content = when {
                request.contains("\"path\":\"/\"") -> """{"name":"my-drive","is_dir":true},{"name":"root-film.mkv"}"""
                else -> """{"name":"film.mkv"}"""
            }
            """{"code":200,"data":{"content":[$content]}}"""
        }
        val videos = source.scanVideos()
        assertEquals("/", videos.single { it.name == "root-film.mkv" }.mountRoot)
        assertEquals("/my-drive", videos.single { it.name == "film.mkv" }.mountRoot)
    }
    @Test fun `子目录失败明确返回路径且保留有效目录`() = runBlocking {
        val source = client { _, request ->
            if (request.contains("\"path\":\"/\""))
                """{"code":200,"data":{"content":[{"name":"one.mkv"},{"name":"unavailable","is_dir":true}]}}"""
            else """{"code":500,"message":"mount unavailable"}"""
        }
        val snapshot = source.scanSnapshot("/")
        assertEquals(listOf("/one.mkv"), snapshot.videos.map { it.path })
        assertEquals("/unavailable", snapshot.failures.single().path)
        assertTrue(snapshot.failures.single().message.contains("mount unavailable"))
    }
    @Test fun `后续分页失败该目录不能发布第一页文件且兄弟目录继续扫描`() = runBlocking {
        val source = client { _, request ->
            when {
                request.contains("\"path\":\"/\"") -> """{"code":200,"data":{"content":[{"name":"bad","is_dir":true},{"name":"good","is_dir":true}]}}"""
                request.contains("\"path\":\"/good\"") -> """{"code":200,"data":{"content":[{"name":"good.mkv"}]}}"""
                request.contains("\"page\":2,") -> """{"code":500,"message":"driver not init"}"""
                else -> """{"code":200,"data":{"total":1001,"content":[${(1..1000).joinToString { """{"name":"f$it.mkv"}""" }}]}}"""
            }
        }
        val snapshot = source.scanSnapshot("/")
        assertEquals(listOf("/good/good.mkv"), snapshot.videos.map { it.path })
        assertEquals("/bad", snapshot.failures.single().path)
    }
    @Test fun `强制扫描对每个挂载目录传递刷新请求`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = client { _, request ->
            requests += request
            val content = if (request.contains("\"path\":\"/\""))
                """{"name":"drive","is_dir":true}""" else """{"name":"new.mkv"}"""
            """{"code":200,"data":{"content":[$content]}}"""
        }
        assertEquals(1, source.scanVideos(forceRefresh = true).size)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.contains("\"refresh\":true") })
    }
}
