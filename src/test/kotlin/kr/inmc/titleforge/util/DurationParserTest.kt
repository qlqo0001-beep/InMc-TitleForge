package kr.inmc.titleforge.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 보유 기한 표기 해석 검증. 잘못 읽으면 칭호가 엉뚱한 시점에 사라진다. */
class DurationParserTest {

    @Test
    fun `비었거나 perm 이면 영구`() {
        for (input in listOf(null, "", "  ", "perm", "PERM", "permanent", "영구", "무기한", "0")) {
            assertIs<DurationParser.Result.Permanent>(DurationParser.parse(input), "입력: $input")
        }
    }

    @Test
    fun `단위별로 해석한다`() {
        assertEquals(30 * DurationParser.DAY, limited("30d"))
        assertEquals(12 * DurationParser.HOUR, limited("12h"))
        assertEquals(90 * DurationParser.MINUTE, limited("90m"))
        assertEquals(2 * DurationParser.WEEK, limited("2w"))
        assertEquals(45 * DurationParser.SECOND, limited("45s"))
        assertEquals(3 * DurationParser.MONTH, limited("3mo"))
        assertEquals(DurationParser.YEAR, limited("1y"))
    }

    @Test
    fun `한글 단위도 받는다`() {
        assertEquals(7 * DurationParser.DAY, limited("7일"))
        assertEquals(3 * DurationParser.HOUR, limited("3시간"))
        assertEquals(10 * DurationParser.MINUTE, limited("10분"))
        assertEquals(2 * DurationParser.WEEK, limited("2주"))
    }

    @Test
    fun `여러 토큰을 더한다`() {
        assertEquals(DurationParser.DAY + 12 * DurationParser.HOUR, limited("1d12h"))
        assertEquals(2 * DurationParser.WEEK + 3 * DurationParser.DAY, limited("2w 3d"))
    }

    @Test
    fun `단위 없는 숫자는 일로 본다`() {
        assertEquals(14 * DurationParser.DAY, limited("14"))
    }

    @Test
    fun `대소문자를 가리지 않는다`() {
        assertEquals(30 * DurationParser.DAY, limited("30D"))
    }

    @Test
    fun `해석할 수 없으면 Invalid`() {
        for (input in listOf("abc", "30x", "d30", "1d!", "--")) {
            assertIs<DurationParser.Result.Invalid>(DurationParser.parse(input), "입력: $input")
        }
    }

    private fun limited(input: String): Long {
        val result = DurationParser.parse(input)
        assertIs<DurationParser.Result.Limited>(result, "입력: $input")
        return result.millis
    }
}
