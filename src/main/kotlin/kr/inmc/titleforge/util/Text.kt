package kr.inmc.titleforge.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 모든 텍스트는 MiniMessage 를 통해서만 생성한다 (맞춤 지침 7.3-10).
 * 레거시 색 코드(&, §)는 코드 어디에서도 사용하지 않는다.
 */
object Text {

    private val MM: MiniMessage = MiniMessage.miniMessage()
    private val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

    /**
     * @param input MiniMessage 원문
     * @param placeholders `"name" to value` 형태. Component 는 그대로, 그 외는 문자열로 파싱된다.
     */
    fun mini(input: String, vararg placeholders: Pair<String, Any?>): Component {
        if (placeholders.isEmpty()) return MM.deserialize(input)
        val resolvers = placeholders.map { (key, value) ->
            when (value) {
                is Component -> Placeholder.component(key, value)
                null -> Placeholder.unparsed(key, "")
                else -> Placeholder.parsed(key, value.toString())
            }
        }
        return MM.deserialize(input, TagResolver.resolver(resolvers))
    }

    /** 유저가 입력한 값을 안전하게 넣을 때 사용 (서식 태그 무력화). */
    fun escape(raw: String): String = MM.escapeTags(raw)

    fun plain(component: Component): String = PLAIN.serialize(component)

    /** 아이템 이름/로어의 기본 기울임을 제거. */
    fun clean(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    fun empty(): Component = Component.empty()

    /** 초 단위를 "3일 4시간 5분" 형태로. */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0초"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val sb = StringBuilder()
        if (days > 0) sb.append(days).append("일 ")
        if (hours > 0) sb.append(hours).append("시간 ")
        if (minutes > 0) sb.append(minutes).append("분 ")
        if (days == 0L && hours == 0L && secs > 0) sb.append(secs).append("초")
        return sb.toString().trim()
    }

    /** 소수점 자리수를 맞춰 문자열화. 정수면 소수점을 떼어낸다. */
    fun number(value: Double, decimals: Int = 1): String {
        val rounded = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        return if (rounded.endsWith(".0") || rounded.endsWith(".00")) {
            rounded.substringBefore('.')
        } else {
            rounded
        }
    }

    fun signed(value: Double, decimals: Int = 1): String =
        if (value >= 0) "+" + number(value, decimals) else number(value, decimals)
}
