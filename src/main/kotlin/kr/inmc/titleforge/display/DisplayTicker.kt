package kr.inmc.titleforge.display

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import org.bukkit.Bukkit

/**
 * **이 플러그인의 유일한 반복 태스크.**
 *
 * 이름표 · 탭리스트 · 보유 기한 만료를 한 곳에서 처리한다 (맞춤 지침 7.1-4 개정).
 *
 * - 세 기능이 모두 필요 없으면 태스크를 **아예 만들지 않는다.**
 * - 매 주기 렌더 결과를 이전 값과 비교해 **변경분만** 전송한다.
 * - 서버 공통 값(TPS·인원·시간)은 주기마다 1회만 계산해 전 인원이 공유한다.
 * - PlaceholderAPI 파싱은 외부 확장이 스레드 안전하지 않을 수 있어 메인 스레드에서 수행한다.
 */
class DisplayTicker(private val plugin: TitleForgePlugin) {

    private var task: ScheduledTask? = null

    /**
     * 시야 가림(레이캐스트) 판정은 텍스트 갱신보다 훨씬 비싸서 별도 주기로 돈다.
     * `refresh-ticks` 가 얼마든 실제 경과 틱을 이걸로 누적해 독립적인 주기를 만든다.
     */
    private var ticksSinceVisibilityCheck = 0L

    fun start() {
        stop()
        val display = plugin.settings.display
        val needed = display.nametag.enabled || display.tablist.enabled
        if (!needed) {
            // 만료만 필요한 경우에도 주기 검사는 있어야 하지만, 자동 저장 주기에 얹으면 충분하다.
            plugin.logger.info("표시 갱신 티커: 사용 안 함 (이름표·탭리스트 모두 꺼짐)")
            return
        }

        ticksSinceVisibilityCheck = 0L
        val period = display.refreshTicks
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { tick() }, period, period)
        plugin.logger.info("표시 갱신 티커 시작 (${period}틱 주기)")
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val players = Bukkit.getOnlinePlayers()
        if (players.isEmpty()) {
            plugin.profiles.sweepExpired()
            return
        }

        val display = plugin.settings.display

        for (player in players) {
            if (display.tablist.enabled) {
                runCatching { plugin.tablist.refresh(player) }
                    .onFailure { plugin.logger.warning("탭리스트 갱신 실패 (${player.name}): ${it.message}") }
            }
            if (display.nametag.enabled) {
                Sched.entity(plugin, player) {
                    runCatching { plugin.nametags.refresh(player) }
                        .onFailure { plugin.logger.warning("이름표 갱신 실패 (${player.name}): ${it.message}") }
                }
            }
        }

        if (display.nametag.enabled && display.nametag.hideWhenNotVisible) {
            ticksSinceVisibilityCheck += display.refreshTicks
            if (ticksSinceVisibilityCheck >= display.nametag.visibilityCheckTicks) {
                ticksSinceVisibilityCheck = 0L
                runCatching { plugin.nametags.refreshVisibility() }
                    .onFailure { plugin.logger.warning("이름표 시야 판정 실패: ${it.message}") }
            }
        }

        plugin.profiles.sweepExpired()
    }
}
