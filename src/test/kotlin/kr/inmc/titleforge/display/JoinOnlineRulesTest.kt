package kr.inmc.titleforge.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 접속 메시지를 숨기는 조건·`<online>`·`%tf_online%` 의 셈(2026-10-10). */
class JoinOnlineRulesTest {

    private fun suppress(
        format: String = "<name>",
        vanished: Boolean = false,
        silent: Boolean = false,
        name: String = "Steve",
        filter: List<Regex> = emptyList(),
        online: Int = 5,
        autoHideFrom: Int = -1,
    ) = JoinMessages.shouldSuppress(format, vanished, silent, name, filter, online, autoHideFrom)

    @Test
    fun `아무 조건도 없으면 내보낸다`() {
        assertFalse(suppress())
    }

    @Test
    fun `빈 형식·투명·조용히·이름 거르기면 숨긴다`() {
        assertTrue(suppress(format = " "))
        assertTrue(suppress(vanished = true))
        assertTrue(suppress(silent = true))
        assertTrue(suppress(name = "BadGuy", filter = listOf(Regex("^bad", RegexOption.IGNORE_CASE))))
        assertFalse(suppress(name = "GoodGuy", filter = listOf(Regex("^bad", RegexOption.IGNORE_CASE))))
    }

    @Test
    fun `인원 자동 숨김은 그 수 이상부터, -1 이면 끔`() {
        assertFalse(suppress(online = 9, autoHideFrom = 10))
        assertTrue(suppress(online = 10, autoHideFrom = 10))
        assertTrue(suppress(online = 11, autoHideFrom = 10))
        assertFalse(suppress(online = 1000, autoHideFrom = -1))
    }

    @Test
    fun `나가는 중이면 본인을 접속자 수에서 뺀다`() {
        val self = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertEquals(2, JoinMessages.onlineCount(listOf(self, other), self, leaving = false))
        assertEquals(1, JoinMessages.onlineCount(listOf(self, other), self, leaving = true))
        // 투명이라 목록에 없던 본인은 빼도 그대로
        assertEquals(1, JoinMessages.onlineCount(listOf(other), self, leaving = true))
    }

    @Test
    fun `tf_online 은 볼 권한이 있으면 투명인 사람까지, 투명인 본인은 본인만 더한다`() {
        assertEquals(5, TokenRenderer.onlineCount(5, 2, viewerCanSee = false, viewerVanished = false))
        assertEquals(7, TokenRenderer.onlineCount(5, 2, viewerCanSee = true, viewerVanished = false))
        assertEquals(6, TokenRenderer.onlineCount(5, 2, viewerCanSee = false, viewerVanished = true))
        assertEquals(7, TokenRenderer.onlineCount(5, 2, viewerCanSee = true, viewerVanished = true))
        assertEquals(5, TokenRenderer.onlineCount(5, 0, viewerCanSee = false, viewerVanished = true))
    }
}
