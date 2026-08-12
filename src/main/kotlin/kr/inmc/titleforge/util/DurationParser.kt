package kr.inmc.titleforge.util

/**
 * `30d`, `12h`, `90m`, `2w`, `perm` 같은 기간 표기를 밀리초로 바꾼다.
 *
 * Bukkit 에 의존하지 않는 순수 로직이라 단위 테스트로 검증한다.
 */
object DurationParser {

    /** 영구를 뜻하는 표기. */
    private val PERMANENT_WORDS = setOf("perm", "permanent", "영구", "무기한", "0")

    private val TOKEN = Regex("(\\d+)\\s*([a-z가-힣]+)")

    sealed interface Result {
        /** 영구 보유. */
        data object Permanent : Result

        /** [millis] 만큼 유지. */
        data class Limited(val millis: Long) : Result

        /** 해석 실패. */
        data object Invalid : Result
    }

    /**
     * `30d`, `1d12h`, `2w 3d` 처럼 여러 토큰을 이어 쓸 수 있다.
     * 단위가 없는 숫자는 **일(day)** 로 본다.
     */
    fun parse(raw: String?): Result {
        val input = raw?.trim()?.lowercase() ?: return Result.Permanent
        if (input.isEmpty()) return Result.Permanent
        if (input in PERMANENT_WORDS) return Result.Permanent

        input.toLongOrNull()?.let { days ->
            return if (days <= 0) Result.Permanent else Result.Limited(days * DAY)
        }

        val matches = TOKEN.findAll(input).toList()
        if (matches.isEmpty()) return Result.Invalid

        var total = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return Result.Invalid
            val unit = unitOf(match.groupValues[2]) ?: return Result.Invalid
            total += amount * unit
        }
        // 토큰 사이의 공백을 제외하고 남는 문자가 있으면 오타로 본다.
        val consumed = matches.sumOf { it.value.length }
        if (consumed != input.replace(" ", "").length) return Result.Invalid

        return if (total <= 0L) Result.Permanent else Result.Limited(total)
    }

    private fun unitOf(raw: String): Long? = when (raw) {
        "s", "sec", "secs", "second", "seconds", "초" -> SECOND
        "m", "min", "mins", "minute", "minutes", "분" -> MINUTE
        "h", "hour", "hours", "시간" -> HOUR
        "d", "day", "days", "일" -> DAY
        "w", "week", "weeks", "주" -> WEEK
        "mo", "month", "months", "개월", "달" -> MONTH
        "y", "year", "years", "년" -> YEAR
        else -> null
    }

    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY
    const val MONTH = 30 * DAY
    const val YEAR = 365 * DAY
}
