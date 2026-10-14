package kr.inmc.titleforge.listener

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

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

    /**
     * 추방 — 퇴장 메시지의 `<reason>` 과 원인. 추방되면 바로 뒤에 같은 스레드에서 [onQuitMessage] 가 온다.
     * 접속 중인 사람 수만큼만 쌓이고 퇴장 때 빠진다.
     */
    private val kicks = java.util.concurrent.ConcurrentHashMap<java.util.UUID, PlayerKickEvent>()

    /**
     * 나가는 순간 투명이었던 사람. 투명 기능이 퇴장 이벤트에서 표식을 먼저 지워도(NORMAL 등) [onQuitMessage](HIGH)가 알리지 않게
     * LOWEST 에서 적어 둔다. 퇴장 처리 동안만 들어 있다.
     */
    private val vanishedOnQuit: MutableSet<java.util.UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 접속 메시지(2026-10-10). HIGH — inmc-discord 가 MONITOR 에서 `joinMessage()` 를 읽어 디스코드로 보내므로 그보다 먼저,
     * 투명 기능이 LOWEST 에서 `vanished` 를 건 뒤.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onJoinMessage(event: PlayerJoinEvent) {
        val settings = plugin.settings.display.joinMessages
        if (!settings.enabled) return
        val player = event.player
        val first = !player.hasPlayedBefore()
        if (first) plugin.joinMessages.countNewUser()
        event.joinMessage(plugin.joinMessages.render(player, if (first) settings.firstJoin else settings.join, leaving = false))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        kicks[event.player.uniqueId] = event
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuitEarly(event: PlayerQuitEvent) {
        if (kr.inmc.core.integration.Vanish.isVanished(event.player)) vanishedOnQuit += event.player.uniqueId
    }

    /**
     * 퇴장·추방 메시지. 추방의 `leaveMessage` 는 Paper 가 이 이벤트의 `quitMessage` 로 넘겨 방송하므로, 추방도 여기서
     * (`QuitReason.KICKED`) 정한다 — 추방 이벤트에서 바꿔도 여기서 다시 덮인다.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onQuitMessage(event: PlayerQuitEvent) {
        val kick = kicks.remove(event.player.uniqueId)
        val wasVanished = vanishedOnQuit.remove(event.player.uniqueId)
        val settings = plugin.settings.display.joinMessages
        if (!settings.enabled) return
        // "추방" 문구는 사람이 내보낸 것(명령·플러그인)에만 — 서버 끄기(UNKNOWN·RESTART_COMMAND)·시간 초과(TIMEOUT)·접속 오류 따위로 끊긴 것은
        // 평범한 퇴장으로 적는다(2026-10-14: 리소스팩을 받다 keepalive 에 끊긴 사람이 "추방되었습니다" 로 나갔다).
        val kicked = event.reason == PlayerQuitEvent.QuitReason.KICKED && !org.bukkit.Bukkit.isStopping() &&
            (kick?.cause == PlayerKickEvent.Cause.KICK_COMMAND || kick?.cause == PlayerKickEvent.Cause.PLUGIN)
        event.quitMessage(
            plugin.joinMessages.render(
                event.player, if (kicked) settings.kick else settings.quit, leaving = true,
                reason = if (kicked) kick?.reason() else null, wasVanished = wasVanished,
            ),
        )
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

    /** 투명인 채로(기본 안 보임) 떠 있는 이름표를 막 들어온 사람에게, 볼 수 있으면 보여 준다. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoinRevealVanished(event: PlayerJoinEvent) {
        plugin.nametags.revealVanishedTo(event.player)
    }

    private fun applyState(player: org.bukkit.entity.Player) {
        val profile = plugin.profiles.cached(player.uniqueId) ?: return
        // 오프라인 동안 만료된 항목을 먼저 정리한다.
        plugin.profiles.sweepExpired(profile)
        plugin.statApplier.apply(player, profile.totalStats)

        // 오프라인 중에 닉네임이 강제 해제됐다면 지금 알린다.
        plugin.nicknames.deliverPendingNotice(player)
        // 내 실제 아이디를 닉네임으로 선점한 사람이 있으면 풀어 준다 (DB 조회라 비동기).
        Sched.async(plugin) { plugin.nicknames.enforceRealNameOwnership(player) }
        plugin.nameDisplay.refresh(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        plugin.nametags.rebuildFor(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        // 리스폰하면 엔티티가 남아도 승객 관계가 끊긴다. 확실히 지우고 다시 만들게 한다.
        plugin.nametags.rebuildFor(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.nameDisplay.cleanup(event.player)
        plugin.bubbles.handleQuit(event.player)
        plugin.tablist.handleQuit(event.player)
        plugin.tokens.handleQuit(event.player.uniqueId)
        plugin.nicknames.handleQuit(event.player.uniqueId)
        plugin.rank.forget(event.player.uniqueId)
        plugin.profiles.handleQuit(event.player)
    }
}
