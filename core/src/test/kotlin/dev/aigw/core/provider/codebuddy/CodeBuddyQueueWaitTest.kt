package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.ThinkTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeBuddyQueueWaitTest {

    @Test
    fun `6020 带重试口径分类为 QUEUE_WAIT`() {
        val provider = codeBuddyForClassify()
        val body = """{"code":6020,"message":"queue waiting","data":{"retry_after":40}}"""
        val err = provider.classify(200, body)
        assertEquals(ErrorKind.QUEUE_WAIT, err.kind)
        assertEquals(40L, err.retryAfterSeconds)
    }

    @Test
    fun `6021 队列满仍按限流轮转`() {
        val provider = codeBuddyForClassify()
        val body = """{"code":6021,"message":"queue full"}"""
        val err = provider.classify(429, body)
        assertEquals(ErrorKind.SOFT_RATE, err.kind)
    }

    @Test
    fun `6020 没给 retry_after 时给保守值`() {
        val provider = codeBuddyForClassify()
        val body = """{"code":6020,"message":"queue waiting"}"""
        val err = provider.classify(200, body)
        assertEquals(ErrorKind.QUEUE_WAIT, err.kind)
        assertEquals(2L, err.retryAfterSeconds)
    }

    /** 只为触发 classify 构造一个最小 provider。 */
    private fun codeBuddyForClassify() =
        CodeBuddyProvider(region = { CodeBuddyProvider.REGION_CN }, nowMillis = { 0L })
}
