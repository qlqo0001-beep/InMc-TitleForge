package kr.inmc.titleforge.stat

/**
 * 관리자가 채팅으로 입력한 스텟 값을 해석한다.
 *
 * 입력은 **항상 표시 단위**다. 이동 속도에 `10` 을 넣으면 `+10%`(내부 0.1)로 저장된다.
 * Bukkit 에 의존하지 않는 순수 로직이라 단위 테스트로 검증한다.
 */
object StatValueParser {

    sealed interface Result {
        /** 값 설정. [internal] 은 저장할 내부값, [display] 는 입력한 표시값. */
        data class Set(val internal: Double, val display: Double, val outOfSoftRange: Boolean) : Result

        /** 스텟 제거 (0 처리). */
        data object Remove : Result

        /** 입력 취소. */
        data object Cancel : Result

        /** 숫자로 해석할 수 없음. */
        data object Invalid : Result

        /** 방어적 상한을 넘음. */
        data class TooLarge(val limit: Double) : Result
    }

    private val CANCEL_WORDS = setOf("취소", "cancel", "c")
    private val REMOVE_WORDS = setOf("제거", "삭제", "remove", "reset", "delete", "none", "-")

    fun parse(stat: Stat, rawInput: String): Result {
        val input = rawInput.trim().replace(",", "").replace("%", "")
        if (input.isEmpty()) return Result.Invalid
        if (input.lowercase() in CANCEL_WORDS) return Result.Cancel
        if (input.lowercase() in REMOVE_WORDS) return Result.Remove

        val display = input.removePrefix("+").toDoubleOrNull() ?: return Result.Invalid
        if (display.isNaN() || display.isInfinite()) return Result.Invalid
        if (display == 0.0) return Result.Remove

        val internal = round(stat.toInternal(display), INTERNAL_DECIMALS)
        if (kotlin.math.abs(internal) > Stat.HARD_LIMIT) return Result.TooLarge(Stat.HARD_LIMIT)
        if (internal == 0.0) return Result.Remove

        return Result.Set(
            internal = internal,
            display = display,
            outOfSoftRange = !stat.withinSoftRange(display),
        )
    }

    private const val INTERNAL_DECIMALS = 4

    private fun round(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return Math.round(value * factor) / factor
    }
}
