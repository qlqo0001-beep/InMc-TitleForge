package kr.inmc.titleforge.stat

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import java.util.Locale

/**
 * 스텟의 종류.
 *
 * 바닐라 스텟은 **외부 플러그인 없이도 항상 동작**한다. MMOItems 를 쓰지 않는 서버도
 * 바닐라 스텟만으로 칭호를 완전히 운영할 수 있으며, GUI 에도 그대로 노출된다.
 */
enum class StatKind(val id: String, val display: String) {
    /** 바닐라 Attribute 로 실제 적용. 외부 의존 없음. */
    VANILLA("vanilla", "바닐라"),

    /** MMOItems(MythicLib) 스텟으로 적용. 해당 플러그인이 없으면 값만 보관한다. */
    MMO("mmo", "MMOItems"),

    /** 값만 보관하고 API·플레이스홀더로만 노출. */
    VIRTUAL("virtual", "가상"),
    ;

    companion object {
        fun of(raw: String?): StatKind? = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
    }
}

/**
 * 스텟 정의 1개.
 *
 * 바닐라 스텟은 코드에 기본값이 들어 있고, MMOItems 스텟은 `stats.yml` 에서 정의된다.
 * 동일성은 [id] 로만 판단하므로 설정을 다시 읽어도 기존 참조가 깨지지 않는다.
 */
class Stat(
    val id: String,
    val display: String,
    /** GUI 로어에 표시할 설명. 여러 줄 가능. */
    val description: List<String>,
    val category: StatCategory,
    val icon: Material,
    val kind: StatKind,
    /** VANILLA 전용. `minecraft:` 네임스페이스의 Attribute 키. */
    val attributeKey: String? = null,
    /** MMO 전용. MMOItems 스텟 ID (예: CRITICAL_STRIKE_CHANCE). */
    val mmoStat: String? = null,
    val operation: AttributeModifier.Operation = AttributeModifier.Operation.ADD_NUMBER,
    /** 표시 배율. 내부 0.1 = 표시 10% 인 값은 100 을 준다. 관리자는 항상 표시 단위로 입력한다. */
    val displayScale: Double = 1.0,
    val suffix: String = "",
    val decimals: Int = 1,
    /** 플레이어 기본값. GUI 로어에 안내된다. */
    val vanillaBase: Double? = null,
    val softMin: Double = -100.0,
    val softMax: Double = 100.0,
    val order: Int = 0,
) {

    val modifierKey: NamespacedKey by lazy {
        requireNotNull(NamespacedKey.fromString("$NAMESPACE:stat_${id.lowercase()}")) {
            "스텟 id 로 NamespacedKey 를 만들 수 없습니다: $id"
        }
    }

    /** 관리자에게 보여줄 적용 방식 설명. */
    val operationDisplay: String
        get() = when (operation) {
            AttributeModifier.Operation.ADD_NUMBER -> "합연산 (ADD_NUMBER)"
            AttributeModifier.Operation.ADD_SCALAR -> "기본값 비례 (ADD_SCALAR)"
            AttributeModifier.Operation.MULTIPLY_SCALAR_1 -> "최종값 비례 (MULTIPLY_SCALAR_1)"
        }

    fun toDisplay(internal: Double): Double = internal * displayScale

    fun toInternal(display: Double): Double = display / displayScale

    /** 표시용 문자열. 예) "+2", "+10%" */
    fun format(internalValue: Double): String {
        val scaled = toDisplay(internalValue)
        val sign = if (scaled >= 0) "+" else ""
        return sign + trimNumber(scaled) + suffix
    }

    fun softRangeDisplay(): String =
        "${trimNumber(softMin)}$suffix ~ ${if (softMax >= 0) "+" else ""}${trimNumber(softMax)}$suffix"

    fun withinSoftRange(displayValue: Double): Boolean = displayValue in softMin..softMax

    private fun trimNumber(value: Double): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", value)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    /** 동일성은 id 로만 판단한다. 설정 리로드로 인스턴스가 바뀌어도 참조가 유지된다. */
    override fun equals(other: Any?): Boolean = this === other || (other is Stat && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Stat($id, ${kind.id})"

    companion object {
        const val NAMESPACE = "titleforge"

        /** 내부 저장값의 방어적 상한. 이 값을 넘는 입력은 거부한다. */
        const val HARD_LIMIT = 100_000.0

        private val ID_PATTERN = Regex("^[a-z0-9_]{1,48}$")

        fun validId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
