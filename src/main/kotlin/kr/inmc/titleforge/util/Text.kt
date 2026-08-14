package kr.inmc.titleforge.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit

/**
 * 모든 텍스트는 MiniMessage 를 통해서만 생성한다 (맞춤 지침 7.3-10).
 * 레거시 색 코드(&, §)는 코드 어디에서도 사용하지 않는다.
 */
object Text {

    private val MM: MiniMessage = MiniMessage.miniMessage()
    private val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

    /** `§#rrggbb` 형태의 hex 도 읽을 수 있게 만든 레거시 직렬화기. */
    private val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(SECTION)
        .hexColors()
        .build()

    /** 레거시 색 코드 구분자. `§`(전달용)와 `&`(설정 원문용) 둘 다 본다. */
    private const val SECTION = '§'
    private const val AMPERSAND = '&'

    /** `&a` `&l` 같은 표준 코드. */
    private val AMPERSAND_CODE = Regex("&([0-9a-fk-orA-FK-OR])")

    /** `&#rrggbb` 형태의 hex 코드. */
    private val AMPERSAND_HEX = Regex("&#([0-9a-fA-F]{6})")

    /**
     * @param input MiniMessage 원문
     * @param placeholders `"name" to value` 형태. Component 는 그대로, 그 외는 문자열로 파싱된다.
     */
    fun mini(input: String, vararg placeholders: Pair<String, Any?>): Component {
        // 레거시 색 코드가 섞여 있으면 MiniMessage 가 예외를 던진다. 칭호 이름은 관리자가
        // 직접 입력하는 값이라 &/§ 가 들어올 수 있으므로 파싱 전에 정규화한다.
        val safe = fromLegacy(input)
        return runCatching {
            if (placeholders.isEmpty()) return@runCatching MM.deserialize(safe)
            val resolvers = placeholders.map { (key, value) ->
                when (value) {
                    is Component -> Placeholder.component(key, value)
                    null -> Placeholder.unparsed(key, "")
                    else -> Placeholder.parsed(key, value.toString())
                }
            }
            MM.deserialize(safe, TagResolver.resolver(resolvers))
        }.getOrElse { error ->
            // 태그가 망가져도 화면 전체가 죽지는 않게 원문을 그대로 보여준다.
            // 다만 그냥 삼키면 "placeholder 가 그대로 노출된다" 같은 증상만 보이고 원인을
            // 찾기 어려우므로, 콘솔에는 반드시 남긴다.
            // (흔한 원인: MiniMessage 태그 이름에 대문자가 섞였을 때 — 소문자/숫자/밑줄/하이픈만 허용됨)
            Bukkit.getLogger().warning(
                "[InMc-TitleForge] MiniMessage 파싱 실패, 원문 그대로 표시합니다: '$safe' (${error.message})",
            )
            Component.text(safe)
        }
    }

    /** 유저가 입력한 값을 안전하게 넣을 때 사용 (서식 태그 무력화). */
    fun escape(raw: String): String = MM.escapeTags(raw)

    /**
     * 레거시 색 코드(`§a` `&l` `&#rrggbb`)를 **제거**한다.
     *
     * [escape] 는 MiniMessage 태그만 막으므로, 유저 입력에서 `&` 색까지 차단하려면 함께 쓴다.
     * 구분자만 지우면 뒤 글자가 그대로 남아 `&c윤` 이 `c윤` 이 되어 버리므로 코드 전체를 지운다.
     */
    fun stripLegacyCodes(raw: String): String = LEGACY_ANY_CODE.replace(raw, "")

    private val LEGACY_ANY_CODE = Regex("[§&](#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])")

    /**
     * 외부에서 받은 문자열을 MiniMessage 원문에 끼워 넣을 수 있는 형태로 바꾼다.
     *
     * 다른 플러그인의 플레이스홀더(CMI·Vault 등)는 아직 레거시 색 코드(`§a` 같은)를
     * 돌려주는 경우가 많은데, MiniMessage 는 레거시 코드가 섞인 문자열을 **거부한다**
     * (파싱 시 예외). 그래서 레거시로 한 번 해석한 뒤 MiniMessage 표기로 다시 직렬화한다.
     *
     * 색이 없는 평범한 문자열은 손대지 않고 그대로 돌려주므로 비용이 거의 없다.
     */
    fun fromLegacy(raw: String): String {
        // `&` 코드를 먼저 `§` 로 통일한 뒤 한 번만 해석한다.
        // 색 코드로 쓰이지 않는 `&`(예: "A&B")는 정규식에 걸리지 않아 그대로 남는다.
        val unified = if (raw.indexOf(AMPERSAND) < 0) {
            raw
        } else {
            AMPERSAND_CODE.replace(AMPERSAND_HEX.replace(raw) { "$SECTION#${it.groupValues[1]}" }) {
                "$SECTION${it.groupValues[1]}"
            }
        }
        if (unified.indexOf(SECTION) < 0) return unified
        return MM.serialize(LEGACY.deserialize(unified))
    }

    /**
     * 외부 플러그인에 넘길 때 쓰는 레거시(`§`) 표기.
     *
     * PlaceholderAPI 로 값을 받아 가는 쪽(TAB·채팅 플러그인 등)은 대부분 MiniMessage 를
     * 모르고 `§` 만 해석하므로, 플레이스홀더 반환값은 이 형태로 내보낸다.
     */
    fun toLegacy(component: Component): String = LEGACY.serialize(component)

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
