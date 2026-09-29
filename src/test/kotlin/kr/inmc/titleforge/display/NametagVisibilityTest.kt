package kr.inmc.titleforge.display

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 본체가 안 보일 때 이름표도 숨기는 판정 검증.
 *
 * 이름표는 플레이어와 별개의 엔티티라, 투명 물약이나 베니시로 몸이 사라져도 저절로
 * 없어지지 않는다. 그러면 이름표만 허공에 떠 위치가 그대로 새어 나간다.
 */
class NametagVisibilityTest {

    private fun concealed(
        spectator: Boolean = false,
        invisible: Boolean = false,
        potion: Boolean = false,
        vanishedMeta: Boolean = false,
        useVanishMeta: Boolean = false,
        enabled: Boolean = true,
    ) = NametagVisibility.concealedFromEveryone(
        spectator, invisible, potion, vanishedMeta, useVanishMeta, enabled,
    )

    private fun visible(
        concealed: Boolean = false,
        canSee: Boolean = true,
        lineOfSight: Boolean = true,
        useLineOfSight: Boolean = true,
    ) = NametagVisibility.visibleTo(concealed, canSee, lineOfSight, useLineOfSight)

    // ── 전원에게 숨기는 상태 ───────────────────────────────────────────

    @Test
    fun `관전자 모드는 전원에게 숨긴다`() {
        assertTrue(concealed(spectator = true))
    }

    @Test
    fun `투명 플래그는 전원에게 숨긴다`() {
        assertTrue(concealed(invisible = true))
    }

    @Test
    fun `투명 물약은 전원에게 숨긴다`() {
        assertTrue(concealed(potion = true))
    }

    @Test
    fun `평범한 상태는 숨기지 않는다`() {
        assertFalse(concealed())
    }

    @Test
    fun `기능을 끄면 어떤 상태에서도 숨기지 않는다`() {
        // 회귀 방지: hide-when-invisible: false 로 두면 예전 동작 그대로여야 한다.
        assertFalse(concealed(spectator = true, enabled = false))
        assertFalse(concealed(invisible = true, potion = true, enabled = false))
        assertFalse(concealed(vanishedMeta = true, useVanishMeta = true, enabled = false))
    }

    // ── vanished 메타데이터는 별도 스위치 ──────────────────────────────

    @Test
    fun `메타데이터 스위치가 꺼져 있으면 무시한다`() {
        // 기본값. 이걸 켜면 베니시를 볼 수 있는 관리자에게도 이름표가 사라진다.
        assertFalse(concealed(vanishedMeta = true, useVanishMeta = false))
    }

    @Test
    fun `메타데이터 스위치를 켜면 전원에게 숨긴다`() {
        assertTrue(concealed(vanishedMeta = true, useVanishMeta = true))
    }

    // ── 뷰어별 판정 ───────────────────────────────────────────────────

    @Test
    fun `본체를 못 보는 뷰어에게는 숨긴다`() {
        // 베니시 플러그인이 hidePlayer 로 숨긴 상대.
        assertFalse(visible(canSee = false))
    }

    @Test
    fun `베니시를 볼 수 있는 관리자에게는 그대로 보인다`() {
        // 확정 사항: 볼 권한이 있으면 몸도 이름표도 보인다.
        assertTrue(visible(canSee = true))
    }

    @Test
    fun `전역 은신이면 뷰어와 무관하게 숨긴다`() {
        assertFalse(visible(concealed = true, canSee = true))
    }

    // ── 시야 가림과의 조합 ────────────────────────────────────────────

    @Test
    fun `시야가 막히면 숨긴다`() {
        assertFalse(visible(lineOfSight = false, useLineOfSight = true))
    }

    @Test
    fun `시야 판정을 끄면 가려도 보인다`() {
        assertTrue(visible(lineOfSight = false, useLineOfSight = false))
    }

    @Test
    fun `시야 판정이 꺼져 있어도 베니시 판정은 동작한다`() {
        // 두 기능은 독립이다. hide-when-not-visible: false 인 서버에서도 베니시는 걸러야 한다.
        assertFalse(visible(canSee = false, useLineOfSight = false))
    }

    @Test
    fun `아무 문제 없으면 보인다`() {
        assertTrue(visible())
    }
}
