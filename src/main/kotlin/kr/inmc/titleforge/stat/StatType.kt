package kr.inmc.titleforge.stat

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier

/**
 * 칭호가 제공할 수 있는 스텟 목록.
 *
 * 새 스텟은 이 enum 에만 추가하면 저장 · GUI · 명령어 · 플레이스홀더가 모두 따라온다
 * (맞춤 지침 7.4-16). 어디에도 스텟별 하드코딩 분기를 만들지 않는다.
 *
 * @param attributeKey 바닐라 Attribute 키. null 이면 커스텀 스텟(플레이스홀더/API 로만 노출).
 * @param displayScale 표시용 배율. 이동속도처럼 내부 0.1 = 표시 10% 인 값은 100 을 준다.
 *                     **관리자는 항상 표시 단위로 입력한다.**
 * @param vanillaBase  플레이어의 바닐라 기본값. GUI 로어에 그대로 안내된다.
 * @param softMin      권장 입력 하한 (표시 단위). 벗어나면 경고만 하고 적용은 허용한다.
 * @param softMax      권장 입력 상한 (표시 단위).
 */
enum class StatType(
    val id: String,
    val display: String,
    val attributeKey: String?,
    val category: StatCategory,
    val icon: Material,
    val operation: AttributeModifier.Operation = AttributeModifier.Operation.ADD_NUMBER,
    val displayScale: Double = 1.0,
    val suffix: String = "",
    val decimals: Int = 1,
    val vanillaBase: Double? = null,
    val softMin: Double = -100.0,
    val softMax: Double = 100.0,
) {
    // ── 전투 ──────────────────────────────────────────────────────────
    ATTACK_DAMAGE(
        "attack_damage", "공격력", "attack_damage", StatCategory.COMBAT, Material.IRON_SWORD,
        vanillaBase = 1.0, softMin = -20.0, softMax = 20.0,
    ),
    ATTACK_SPEED(
        "attack_speed", "공격 속도", "attack_speed", StatCategory.COMBAT, Material.GOLDEN_SWORD,
        decimals = 2, vanillaBase = 4.0, softMin = -4.0, softMax = 4.0,
    ),
    CRIT_CHANCE(
        "crit_chance", "치명타 확률", null, StatCategory.COMBAT, Material.QUARTZ,
        suffix = "%", softMin = -100.0, softMax = 100.0,
    ),
    CRIT_DAMAGE(
        "crit_damage", "치명타 피해", null, StatCategory.COMBAT, Material.BLAZE_POWDER,
        suffix = "%", softMin = -100.0, softMax = 500.0,
    ),

    // ── 방어 ──────────────────────────────────────────────────────────
    MAX_HEALTH(
        "max_health", "최대 체력", "max_health", StatCategory.DEFENSE, Material.GOLDEN_APPLE,
        vanillaBase = 20.0, softMin = -18.0, softMax = 100.0,
    ),
    ARMOR(
        "armor", "방어력", "armor", StatCategory.DEFENSE, Material.IRON_CHESTPLATE,
        vanillaBase = 0.0, softMin = -20.0, softMax = 30.0,
    ),
    ARMOR_TOUGHNESS(
        "armor_toughness", "방어 강도", "armor_toughness", StatCategory.DEFENSE, Material.DIAMOND_CHESTPLATE,
        vanillaBase = 0.0, softMin = -20.0, softMax = 20.0,
    ),
    KNOCKBACK_RESISTANCE(
        "knockback_resistance", "넉백 저항", "knockback_resistance", StatCategory.DEFENSE, Material.NETHERITE_INGOT,
        displayScale = 100.0, suffix = "%", decimals = 0,
        vanillaBase = 0.0, softMin = -100.0, softMax = 100.0,
    ),
    MAX_ABSORPTION(
        "max_absorption", "흡수 체력", "max_absorption", StatCategory.DEFENSE, Material.GOLDEN_CARROT,
        vanillaBase = 0.0, softMin = 0.0, softMax = 40.0,
    ),

    // ── 이동 ──────────────────────────────────────────────────────────
    MOVEMENT_SPEED(
        "movement_speed", "이동 속도", "movement_speed", StatCategory.MOBILITY, Material.FEATHER,
        operation = AttributeModifier.Operation.ADD_SCALAR,
        displayScale = 100.0, suffix = "%", decimals = 0,
        vanillaBase = 0.1, softMin = -50.0, softMax = 100.0,
    ),

    // ── 유틸리티 ──────────────────────────────────────────────────────
    LUCK(
        "luck", "행운", "luck", StatCategory.UTILITY, Material.RABBIT_FOOT,
        vanillaBase = 0.0, softMin = -10.0, softMax = 10.0,
    ),
    EXP_BONUS(
        "exp_bonus", "경험치 보너스", null, StatCategory.UTILITY, Material.EXPERIENCE_BOTTLE,
        suffix = "%", softMin = -100.0, softMax = 500.0,
    ),
    DROP_BONUS(
        "drop_bonus", "드랍률 보너스", null, StatCategory.UTILITY, Material.DIAMOND,
        suffix = "%", softMin = -100.0, softMax = 500.0,
    ),
    ;

    val vanilla: Boolean get() = attributeKey != null

    /** 관리자에게 보여줄 적용 방식 설명. */
    val operationDisplay: String
        get() = when (operation) {
            AttributeModifier.Operation.ADD_NUMBER -> "합연산 (ADD_NUMBER)"
            AttributeModifier.Operation.ADD_SCALAR -> "기본값 비례 (ADD_SCALAR)"
            AttributeModifier.Operation.MULTIPLY_SCALAR_1 -> "최종값 비례 (MULTIPLY_SCALAR_1)"
        }

    /** AttributeModifier 식별 키. 항상 titleforge 네임스페이스 (맞춤 지침 7.4-17). */
    val modifierKey: NamespacedKey by lazy {
        requireNotNull(NamespacedKey.fromString("$NAMESPACE:stat_$id")) { "invalid key for $id" }
    }

    /** 내부값 → 표시 단위. */
    fun toDisplay(internal: Double): Double = internal * displayScale

    /** 표시 단위 → 내부값. */
    fun toInternal(display: Double): Double = display / displayScale

    /** 표시용 문자열. 예) "+2", "+10%" */
    fun format(internalValue: Double): String {
        val scaled = toDisplay(internalValue)
        val sign = if (scaled >= 0) "+" else ""
        return sign + trimNumber(scaled) + suffix
    }

    /** 권장 범위 표기. 예) "-20 ~ +20" */
    fun softRangeDisplay(): String =
        "${trimNumber(softMin)}$suffix ~ ${if (softMax >= 0) "+" else ""}${trimNumber(softMax)}$suffix"

    fun withinSoftRange(displayValue: Double): Boolean = displayValue in softMin..softMax

    private fun trimNumber(value: Double): String {
        val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    companion object {
        const val NAMESPACE = "titleforge"

        /** 내부 저장값의 방어적 상한. 이 값을 넘는 입력은 거부한다. */
        const val HARD_LIMIT = 100_000.0

        private val BY_ID: Map<String, StatType> = StatType.entries.associateBy { it.id }

        val VANILLA: List<StatType> = StatType.entries.filter { it.vanilla }

        fun of(id: String): StatType? = BY_ID[id.lowercase()]
    }
}
