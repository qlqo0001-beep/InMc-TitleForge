package kr.inmc.titleforge.display

import kr.inmc.core.integration.Vanish
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicLong

/**
 * 접속·퇴장·첫 접속 메시지(2026-10-10 — CMI 접속 메시지를 옮김). 이벤트 처리는 `PlayerListener`(HIGH — inmc-discord 가
 * MONITOR 에서 바뀐 글을 읽는다), 여기는 글을 만든다.
 *
 * 투명(vanish)인 사람·`titleforge.join.silent`·이름 거르기·인원 자동 숨김이면 null(메시지 없음).
 */
class JoinMessages(private val plugin: TitleForgePlugin) {

    /** 이 서버에 온 사람 수(`tf_player` 행 수). 켤 때 워커에서 한 번 세고, 첫 접속마다 더한다. 아직 모르면 -1. */
    private val totalUsers = AtomicLong(-1L)

    /** 켤 때 — DB 는 워커에서만(규칙 1). */
    fun loadTotal() {
        Sched.async(plugin) {
            runCatching { totalUsers.set(plugin.storage.countPlayers().toLong()) }
                .onFailure { plugin.logger.warning("플레이어 수를 세지 못했습니다(첫 접속 메시지의 <total> 이 ? 로): ${it.message}") }
        }
    }

    /** 첫 접속 — 센 수에 더한다(못 셌으면 그대로 모름). */
    fun countNewUser() {
        totalUsers.updateAndGet { if (it < 0) it else it + 1 }
    }

    /**
     * [format] 으로 [player] 의 메시지를 만든다. 메인(그 사람) 스레드에서.
     *
     * @param leaving 퇴장 — 지금 접속자 수에서 본인을 뺀다(퇴장 이벤트 동안은 아직 접속자에 들어 있다).
     * @param reason 추방 사유(`<reason>`).
     * @param wasVanished 나가는 순간(퇴장 LOWEST) 투명이었다 — 투명 기능이 표식을 먼저 지웠어도 알리지 않는다.
     * @return 내보낼 글. 내보내지 않으면 null.
     */
    fun render(player: Player, format: String, leaving: Boolean, reason: Component? = null, wasVanished: Boolean = false): Component? {
        val s = plugin.settings.display.joinMessages
        // 방송이라 보는 사람을 모른다 — 투명인 사람은 수에서 뺀다.
        val online = onlineCount(Vanish.visibleOnline(null).map { it.uniqueId }, player.uniqueId, leaving)
        // 투명인 사람은 누구에게도 알리지 않는다 — 투명 기능이 LOWEST 에서 표식을 걸고 들어온 사람도 여기서 걸린다.
        val suppress = shouldSuppress(
            format = format,
            vanished = wasVanished || Vanish.isVanished(player),
            silent = player.hasPermission(SILENT_PERMISSION),
            name = player.name,
            filter = s.filter,
            online = online,
            autoHideFrom = s.autoHideFrom,
        )
        if (suppress) return null

        val profile = plugin.profiles.cached(player.uniqueId)
        val display = plugin.nameDisplay
        val total = totalUsers.get()
        return Text.mini(
            cmiTokens(format),
            "nameplate" to display.nameplate(profile, player.name, player),
            "nickname" to display.nicknameComponent(profile, player.name),
            "name" to player.name,
            "online" to online,
            "total" to (if (total < 0) "?" else total.toString()),
            "reason" to (reason ?: Component.empty()),
        )
    }

    internal companion object {
        /** 이 권한이 있으면 접속·퇴장 메시지를 내보내지 않는다(CMI `cmi.messages.disablelogin/disablequit` 자리). */
        const val SILENT_PERMISSION = "titleforge.join.silent"

        private val CMI_TOKENS = listOf(
            Regex("\\[playerName]", RegexOption.IGNORE_CASE) to "<name>",
            Regex("\\[totalUsers]", RegexOption.IGNORE_CASE) to "<total>",
            Regex("\\[onlinePlayers]", RegexOption.IGNORE_CASE) to "<online>",
        )

        /**
         * 메시지를 내보내지 않는가 — 형식이 비었거나, 투명·[SILENT_PERMISSION]·이름 거르기에 걸리거나,
         * [autoHideFrom] 이 0 이상이고 접속자([online])가 그 **이상**이면.
         */
        fun shouldSuppress(
            format: String,
            vanished: Boolean,
            silent: Boolean,
            name: String,
            filter: List<Regex>,
            online: Int,
            autoHideFrom: Int,
        ): Boolean =
            format.isBlank() || vanished || silent || filter.any { it.containsMatchIn(name) } ||
                (autoHideFrom >= 0 && online >= autoHideFrom)

        /** `<online>` — 보이는 접속자 수. 나가는 중이면([leaving]) 아직 목록에 든 본인을 뺀다. */
        fun onlineCount(visible: Collection<java.util.UUID>, self: java.util.UUID, leaving: Boolean): Int =
            visible.count { !leaving || it != self }

        /** CMI 식 `[playerName]`·`[totalUsers]`·`[onlinePlayers]` → MiniMessage 태그. CMI 문구를 그대로 옮겨 붙여도 되게. */
        fun cmiTokens(format: String): String =
            CMI_TOKENS.fold(format) { text, (pattern, tag) -> pattern.replace(text, tag) }
    }
}
