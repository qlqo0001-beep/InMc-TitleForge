package kr.inmc.titleforge.display

import kr.inmc.titleforge.config.Settings
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 말풍선·접속 메시지 설정(2026-10-10). 테섭 config.yml 에는 이 절이 없으므로 **비었을 때의 기본값**이 곧 테섭 동작이다.
 */
class ChatBubbleJoinSettingsTest {

    private fun load(yaml: String): Settings =
        Settings.load(YamlConfiguration().apply { loadFromString(yaml) })

    @Test
    fun `절이 없으면 말풍선과 접속 메시지가 켜진 기본값`() {
        val display = load("").display
        assertTrue(display.chatBubble.enabled)
        assertTrue(display.joinMessages.enabled)
        assertEquals(-1, display.joinMessages.autoHideFrom)
        assertTrue(display.joinMessages.join.contains("<nameplate>"))
        assertTrue(display.joinMessages.firstJoin.contains("<total>"))
    }

    @Test
    fun `떠 있는 시간은 기본 + 글자당, 최대를 넘지 않는다`() {
        val bubble = load(
            """
            display:
              chat-bubble:
                show-for: 2.0
                per-char: 0.1
                max-show-for: 5.0
            """.trimIndent(),
        ).display.chatBubble
        assertEquals(2000L, bubble.durationMillis(0))
        assertEquals(3000L, bubble.durationMillis(10))
        assertEquals(5000L, bubble.durationMillis(1000))
    }

    @Test
    fun `이름 거르기 정규식은 대소문자를 가리지 않고, 깨진 식은 건너뛴다`() {
        val messages = load(
            """
            display:
              join-messages:
                filter: ['^bad', '([']
            """.trimIndent(),
        ).display.joinMessages
        assertEquals(1, messages.filter.size)
        assertTrue(messages.filter.single().containsMatchIn("BADguy"))
    }

    @Test
    fun `CMI 식 토큰을 MiniMessage 태그로`() {
        assertEquals(
            "<name> 님 환영 (<total>번째, 지금 <online>명)",
            JoinMessages.cmiTokens("[playerName] 님 환영 ([totalUsers]번째, 지금 [onlinePlayers]명)"),
        )
        assertEquals("<name>", JoinMessages.cmiTokens("[playername]"))
    }
}
