package kr.inmc.titleforge.listener

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 접속/퇴장 처리.
 *
 * 데이터 로드는 이미 비동기인 [AsyncPlayerPreLoginEvent] 에서 끝내므로
 * 접속 순간 메인 스레드에서 발생하는 작업은 Attribute 적용과 표시 이름 갱신뿐이다.
 */
class PlayerListener(private val plugin: TitleForgePlugin) : Listener {

    @EventHandler(priority = EventPriority.LOW)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        plugin.profiles.loadOnPreLogin(event.uniqueId, event.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val profile = plugin.profiles.cached(player.uniqueId)
        if (profile == null) {
            // PreLogin 이 실패했거나 캐시가 비었으면 여기서 비동기로 복구한다.
            Sched.async(plugin) {
                plugin.profiles.loadOnPreLogin(player.uniqueId, player.name)
                Sched.entity(plugin, player) { applyState(player) }
            }
            return
        }
        profile.name = player.name
        profile.markDirty()
        applyState(player)
    }

    private fun applyState(player: org.bukkit.entity.Player) {
        val profile = plugin.profiles.cached(player.uniqueId) ?: return
        // 오프라인 동안 만료된 항목을 먼저 정리한다.
        plugin.profiles.sweepExpired(profile)
        plugin.statApplier.apply(player, profile.totalStats)
        plugin.nameDisplay.refresh(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.nameDisplay.cleanup(event.player)
        plugin.tablist.handleQuit(event.player)
        plugin.rank.forget(event.player.uniqueId)
        plugin.profiles.handleQuit(event.player)
    }
}
