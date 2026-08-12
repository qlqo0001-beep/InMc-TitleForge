package kr.inmc.titleforge.badge

import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material

enum class BadgeType(val id: String, val display: String) {
    TITLE("title", "칭호"),
    SEAL("seal", "인장"),
    ;

    companion object {
        fun of(raw: String): BadgeType? = when (raw.lowercase()) {
            "title", "칭호", "t" -> TITLE
            "seal", "인장", "s" -> SEAL
            else -> null
        }
    }
}

enum class Rarity(val id: String, val display: String, val color: String, val weight: Int) {
    COMMON("common", "일반", "<gray>", 0),
    UNCOMMON("uncommon", "고급", "<green>", 1),
    RARE("rare", "희귀", "<aqua>", 2),
    EPIC("epic", "영웅", "<light_purple>", 3),
    LEGENDARY("legendary", "전설", "<gold>", 4),
    MYTHIC("mythic", "신화", "<red>", 5),
    ;

    fun next(): Rarity = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(raw: String): Rarity? =
            Rarity.entries.firstOrNull { it.id.equals(raw, true) || it.display == raw }
    }
}

/**
 * 칭호 · 인장 공통 정의. 불변이며 수정은 [copy] 후 저장한다.
 *
 * 인장은 스텟을 가질 수 없다 (맞춤 지침 7.4-14). [normalized] 가 저장 직전에 강제로 비운다.
 */
data class Badge(
    val type: BadgeType,
    val id: String,
    val displayName: String,
    val lore: List<String> = emptyList(),
    val rarity: Rarity = Rarity.COMMON,
    val icon: Material = Material.NAME_TAG,
    val permission: String = "",
    val hidden: Boolean = false,
    val order: Int = 0,
    /** 스텟 id → 수치. 레지스트리 인스턴스가 아니라 id 로 들고 있어 리로드에 안전하다. */
    val equipStats: Map<String, Double> = emptyMap(),
    val ownStats: Map<String, Double> = emptyMap(),
) {
    /** 생성 시점에 한 번만 파싱한다. 플레이스홀더가 고빈도로 호출해도 비용이 없다. */
    val nameComponent: Component = Text.mini(displayName)
    val plainName: String = Text.plain(nameComponent)

    val key: String get() = "${type.id}:$id"

    fun normalized(): Badge =
        if (type == BadgeType.SEAL && (equipStats.isNotEmpty() || ownStats.isNotEmpty())) {
            copy(equipStats = emptyMap(), ownStats = emptyMap())
        } else {
            this
        }

    companion object {
        /**
         * 소문자 영문 · 숫자 · 밑줄 · **완성형 한글**을 허용한다.
         * 공백과 특수문자는 명령어 인자·플레이스홀더가 깨질 수 있어 계속 금지한다.
         */
        private val ID_PATTERN = Regex("^[a-z0-9_가-힣]{1,32}$")

        /** 유저/관리자 입력 ID 검증 (맞춤 지침 7.5-21). */
        fun validId(id: String): Boolean = ID_PATTERN.matches(id)

        /** 입력값을 ID 규칙에 맞게 다듬는다. 공백은 밑줄로, 영문은 소문자로. */
        fun normalizeId(raw: String): String = raw.trim().replace(' ', '_').lowercase()
    }
}
