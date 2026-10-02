package kr.inmc.titleforge.badge

import kr.inmc.titleforge.util.Text

/**
 * 칭호 꾸미기 — 괄호 모양·괄호 색·글자 색(단색 또는 그라데이션)·굵게를 골라 표시 이름(MiniMessage)을 만든다(테섭 요청 2026-10-02).
 *
 * 만든 원문은 **태그를 모두 닫는다** — 레거시 `&3[` 처럼 닫지 않은 색은 뒤따르는 닉네임까지 물들인다. 글자는 이스케이프해 서식으로 읽히지 않는다.
 * 순수(`TitleStyleTest`).
 */
data class TitleStyle(
    val text: String,
    val bracket: Bracket = Bracket.SQUARE,
    val bracketColor: String = "#AAAAAA",
    val start: String = "#FFFFFF",
    /** 그라데이션 끝 색. null 이면 단색. */
    val end: String? = null,
    val bold: Boolean = false,
) {

    enum class Bracket(val open: String, val close: String, val label: String) {
        NONE("", "", "없음"),
        SQUARE("[", "]", "[ ]"),
        LENTICULAR("【", "】", "【 】"),
        CORNER("「", "」", "「 」"),
        WHITE_CORNER("『", "』", "『 』"),
        ROUND("(", ")", "( )"),
        GUILLEMET("«", "»", "« »"),
        ANGLE("〈", "〉", "〈 〉"),
        STAR("✦ ", " ✦", "✦ ✦"),
    }

    fun toMini(): String {
        val body = Text.escape(text)
        val content = if (end != null && !end.equals(start, ignoreCase = true)) "<gradient:$start:$end>$body</gradient>" else "<color:$start>$body</color>"
        val open = if (bracket.open.isEmpty()) "" else "<color:$bracketColor>${Text.escape(bracket.open)}</color>"
        val close = if (bracket.close.isEmpty()) "" else "<color:$bracketColor>${Text.escape(bracket.close)}</color>"
        val inner = open + content + close
        return if (bold) "<bold>$inner</bold>" else inner
    }

    companion object {

        /** 지금 표시 이름의 글자에서 시작 — 알려진 괄호로 감싸 있으면 벗겨 그 모양을 고른다. */
        fun from(plain: String): TitleStyle {
            val trimmed = plain.trim()
            val bracket = Bracket.entries.filter { it != Bracket.NONE }.firstOrNull {
                trimmed.length > it.open.trim().length + it.close.trim().length &&
                    trimmed.startsWith(it.open.trim()) && trimmed.endsWith(it.close.trim())
            }
            if (bracket == null) return TitleStyle(trimmed, Bracket.NONE)
            return TitleStyle(trimmed.removePrefix(bracket.open.trim()).removeSuffix(bracket.close.trim()).trim(), bracket)
        }
    }
}
