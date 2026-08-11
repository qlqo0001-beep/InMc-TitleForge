package kr.inmc.titleforge.stat

import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier

/**
 * 칭호가 제공할 수 있는 스텟 목록.
 *
 * 새 스텟은 이 enum 에만 추가하면 저장 · GUI · 명령어 · 플레이스홀더가 모두 따라온다
 * (맞춤 지침 7.4-16). 어디에도 스텟별 하드코딩 분기를 만들지 않는다.
 *
 * @param attributeKey 바닐라 Attribute 키. null 이면 커스텀 스텟(플레이스홀더/API 로만 노출).
 * @param displayScale 표시용 배율. 이동속도처럼 0.1 = 10% 인 값은 100 을 준다.
 */
enum class StatType(
    val id: String,
    val display: String,
    val attributeKey: String?,
    val operation: AttributeModifier.Operation = AttributeModifier.Operation.ADD_NUMBER,
    val displayScale: Double = 1.0,
    val suffix: String = "",
    val decimals: Int = 1,
) {
    MAX_HEALTH("max_health", "최대 체력", "max_health"),
    ATTACK_DAMAGE("attack_damage", "공격력", "attack_damage"),
    ATTACK_SPEED("attack_speed", "공격 속도", "attack_speed", decimals = 2),
    ARMOR("armor", "방어력", "armor"),
    ARMOR_TOUGHNESS("armor_toughness", "방어 강도", "armor_toughness"),
    KNOCKBACK_RESISTANCE(
        "knockback_resistance", "넉백 저항", "knockback_resistance",
        displayScale = 100.0, suffix = "%", decimals = 0,
    ),
    MOVEMENT_SPEED(
        "movement_speed", "이동 속도", "movement_speed",
        operation = AttributeModifier.Operation.ADD_SCALAR, displayScale = 100.0, suffix = "%", decimals = 0,
    ),
    MAX_ABSORPTION("max_absorption", "흡수 체력", "max_absorption"),
    LUCK("luck", "행운", "luck"),

    // ── 커스텀 스텟: 바닐라에 대응이 없어 API/플레이스홀더로만 노출된다 ──
    CRIT_CHANCE("crit_chance", "치명타 확률", null, suffix = "%", decimals = 1),
    CRIT_DAMAGE("crit_damage", "치명타 피해", null, suffix = "%", decimals = 1),
    EXP_BONUS("exp_bonus", "경험치 보너스", null, suffix = "%", decimals = 1),
    DROP_BONUS("drop_bonus", "드랍률 보너스", null, suffix = "%", decimals = 1),
    ;

    val vanilla: Boolean get() = attributeKey != null

    /** AttributeModifier 식별 키. 항상 titleforge 네임스페이스 (맞춤 지침 7.4-17). */
    val modifierKey: NamespacedKey by lazy {
        requireNotNull(NamespacedKey.fromString("$NAMESPACE:stat_$id")) { "invalid key for $id" }
    }

    /** 표시용 문자열. 예) "+2", "+10%" */
    fun format(value: Double): String {
        val scaled = value * displayScale
        val sign = if (scaled >= 0) "+" else ""
        return sign + trimNumber(scaled) + suffix
    }

    private fun trimNumber(value: Double): String {
        val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    companion object {
        const val NAMESPACE = "titleforge"

        private val BY_ID: Map<String, StatType> = StatType.entries.associateBy { it.id }

        val VANILLA: List<StatType> = StatType.entries.filter { it.vanilla }

        fun of(id: String): StatType? = BY_ID[id.lowercase()]
    }
}
