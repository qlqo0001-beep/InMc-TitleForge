package kr.inmc.titleforge.display

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 텔레포트가 끝날 때까지 이름표 재생성을 막는 유예 창.
 *
 * ### 왜 필요한가
 * `PlayerTeleportEvent` 는 **실제 이동 전에** 온다. 그 시점에 이름표를 떼어내도, 표시 갱신
 * 티커가(기본 매 틱) 이동 직전에 다시 붙여 버리면 그 엔티티는 이동과 함께 분리되어
 * **떠나온 자리에 그대로 남는다.** 이동이 끝날 때까지 재생성을 미뤄 그 창을 없앤다.
 *
 * ### 왜 만료 시각까지 두는가
 * 정상 경로에서는 이동 직후 다음 틱에 [release] 가 불려 바로 풀린다. 그런데 그 해제 콜백은
 * 엔티티 스케줄러로 도는지라 대상이 사라지면 조용히 버려질 수 있다. 그때 유예가 영구히
 * 남으면 이름표가 영영 안 돌아오므로, 시간이 지나면 스스로 풀리게 한다.
 *
 * Bukkit 에 의존하지 않는 순수 로직이라 단위 테스트로 검증한다.
 */
class TeleportGrace(private val graceMillis: Long) {

    private val until = ConcurrentHashMap<UUID, Long>()

    /** 이동 시작. [graceMillis] 동안, 또는 [release] 가 불릴 때까지 재생성을 막는다. */
    fun mark(uuid: UUID, now: Long = System.currentTimeMillis()) {
        until[uuid] = now + graceMillis
    }

    /** 이동이 끝났다. 즉시 유예를 푼다. */
    fun release(uuid: UUID) {
        until.remove(uuid)
    }

    /** @return 아직 이동 중이라 이름표를 다시 만들면 안 되는가. */
    fun isActive(uuid: UUID, now: Long = System.currentTimeMillis()): Boolean {
        val deadline = until[uuid] ?: return false
        if (now < deadline) return true
        // 만료된 항목은 여기서 치운다. 조회만으로 스스로 정리되므로 따로 청소가 필요 없다.
        until.remove(uuid, deadline)
        return false
    }

    fun clear() = until.clear()

    /** 테스트용. 유예를 들고 있는 인원 수. */
    fun size(): Int = until.size
}
