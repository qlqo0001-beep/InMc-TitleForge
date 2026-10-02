package kr.inmc.titleforge.badge

import kr.inmc.titleforge.util.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 칭호 꾸미기 — 만든 원문은 태그를 모두 닫고, 글자는 서식으로 읽히지 않는다. */
class TitleStyleTest {

    @Test
    fun `괄호·단색·굵게 — 보이는 글자와 닫힌 태그`() {
        val mini = TitleStyle("깔깔슨", TitleStyle.Bracket.SQUARE, "#55FFFF", "#FF5555", bold = true).toMini()
        assertEquals("[깔깔슨]", Text.plain(Text.mini(mini)))
        assertTrue(mini.startsWith("<bold>") && mini.endsWith("</bold>"))
        assertEquals(mini.split("<color").size - 1, mini.split("</color>").size - 1, "여는 색과 닫는 색의 수가 같다")
    }

    @Test
    fun `그라데이션 — 끝 색이 있으면 gradient, 같으면 단색`() {
        assertTrue(TitleStyle("전설", TitleStyle.Bracket.NONE, start = "#FFAA00", end = "#FF5555").toMini().contains("<gradient:#FFAA00:#FF5555>"))
        assertFalse(TitleStyle("전설", TitleStyle.Bracket.NONE, start = "#FFAA00", end = "#ffaa00").toMini().contains("gradient"))
        assertEquals("✦ 전설 ✦", Text.plain(Text.mini(TitleStyle("전설", TitleStyle.Bracket.STAR).toMini())))
    }

    @Test
    fun `글자에 든 태그는 서식이 아니라 글자`() {
        val mini = TitleStyle("<red>해킹</red>", TitleStyle.Bracket.NONE).toMini()
        assertEquals("<red>해킹</red>", Text.plain(Text.mini(mini)))
    }

    @Test
    fun `지금 이름에서 시작 — 알려진 괄호를 벗겨 고른다`() {
        assertEquals(TitleStyle("깔깔슨", TitleStyle.Bracket.SQUARE), TitleStyle.from("[깔깔슨]"))
        assertEquals(TitleStyle("용사", TitleStyle.Bracket.LENTICULAR), TitleStyle.from(" 【용사】 "))
        assertEquals(TitleStyle("그냥", TitleStyle.Bracket.NONE), TitleStyle.from("그냥"))
        assertEquals(TitleStyle("[]", TitleStyle.Bracket.NONE), TitleStyle.from("[]"), "괄호만 있으면 벗기지 않는다")
    }
}
