package kr.inmc.titleforge.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 텔레포트 유예 창 검증.
 *
 * 이름표가 떠나온 자리에 남던 버그의 재발을 막는다. 실제 이동 전에 유예를 걸어 두지 않으면
 * 표시 갱신 티커가 이동 직전에 이름표를 다시 붙이고, 그 엔티티가 옛 좌표에 버려진다.
 */
class TeleportGraceTest {

    private val grace = TeleportGrace(graceMillis = 1000L)
    private val player = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun `표시하지 않은 플레이어는 유예가 없다`() {
        assertFalse(grace.isActive(player, now = 0L))
    }

    @Test
    fun `이동을 시작하면 재생성이 막힌다`() {
        grace.mark(player, now = 0L)

        assertTrue(grace.isActive(player, now = 0L))
        assertTrue(grace.isActive(player, now = 999L))
    }

    @Test
    fun `이동이 끝나면 곧바로 풀린다`() {
        grace.mark(player, now = 0L)
        grace.release(player)

        // 정상 경로에서는 만료를 기다리지 않고 다음 틱에 바로 이름표가 돌아와야 한다.
        assertFalse(grace.isActive(player, now = 1L))
    }

    @Test
    fun `해제 콜백이 유실돼도 시간이 지나면 스스로 풀린다`() {
        grace.mark(player, now = 0L)

        // release 를 부르지 않은 채 유예 시간이 지난 상황 (엔티티 스케줄러 콜백 유실).
        assertFalse(grace.isActive(player, now = 1000L))
        assertFalse(grace.isActive(player, now = 5000L))
    }

    @Test
    fun `만료된 항목은 조회만으로 정리된다`() {
        grace.mark(player, now = 0L)
        assertEquals(1, grace.size())

        grace.isActive(player, now = 2000L)

        // 따로 청소 태스크 없이도 남지 않아야 한다 (누수 방지).
        assertEquals(0, grace.size())
    }

    @Test
    fun `한 사람의 이동이 다른 사람에게 영향을 주지 않는다`() {
        grace.mark(player, now = 0L)

        assertTrue(grace.isActive(player, now = 10L))
        assertFalse(grace.isActive(other, now = 10L))
    }

    @Test
    fun `연속 이동은 유예가 갱신된다`() {
        grace.mark(player, now = 0L)
        grace.mark(player, now = 900L)

        // 첫 이동 기준(1000)으로는 끝났지만, 두 번째 이동이 유예를 연장했다.
        assertTrue(grace.isActive(player, now = 1500L))
        assertFalse(grace.isActive(player, now = 1900L))
    }

    @Test
    fun `퇴장 정리 후에는 유예가 남지 않는다`() {
        grace.mark(player, now = 0L)
        grace.mark(other, now = 0L)

        grace.clear()

        assertEquals(0, grace.size())
        assertFalse(grace.isActive(player, now = 10L))
    }
}
