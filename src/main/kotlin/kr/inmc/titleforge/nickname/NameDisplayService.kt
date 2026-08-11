package kr.inmc.titleforge.nickname

import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * 표시 이름(칭호 + 인장 + 닉네임) 렌더링과 적용.
 *
 * 탭/채팅/네임태그는 config 로 개별 on-off 되며 **기본값은 꺼짐**이다.
 * 전용 채팅·탭 플러그인이 있는 서버에서는 플레이스홀더만 쓰면 된다.
 */
class NameDisplayService(private val plugin: TitleForgePlugin) : Listener {

    /** 표시 칭호 컴포넌트 (미장착 시 config 기본값). */
    fun titleComponent(profile: PlayerProfile?): Component {
        val badge = profile?.displayTitle?.let { plugin.badges.get(BadgeType.TITLE, it) }
            ?: return Text.mini(plugin.settings.title.noneDisplay)
        return badge.nameComponent.append(Text.mini(plugin.settings.display.titleSuffix))
    }

    /** 능력치 칭호 컴포넌트 (미장착 시 config 기본값). */
    fun statTitleComponent(profile: PlayerProfile?): Component {
        val badge = profile?.statTitle?.let { plugin.badges.get(BadgeType.TITLE, it) }
            ?: return Text.mini(plugin.settings.title.noneStat)
        return badge.nameComponent
    }

    fun sealComponent(profile: PlayerProfile?): Component {
        val badge = profile?.seal?.let { plugin.badges.get(BadgeType.SEAL, it) }
            ?: return Text.mini(plugin.settings.sealNoneDisplay)
        return badge.nameComponent.append(Text.mini(plugin.settings.display.sealSuffix))
    }

    fun nicknameText(profile: PlayerProfile?, realName: String): String =
        profile?.nickname?.takeIf { it.isNotBlank() } ?: realName

    /** 유저 입력이므로 반드시 이스케이프한다 (맞춤 지침 7.5-22). */
    fun nicknameComponent(profile: PlayerProfile?, realName: String): Component =
        Component.text(nicknameText(profile, realName))

    fun nameplate(profile: PlayerProfile?, realName: String): Component =
        Text.mini(
            plugin.settings.display.nameplateFormat,
            "seal" to sealComponent(profile),
            "title" to titleComponent(profile),
            "nickname" to nicknameComponent(profile, realName),
        )

    /** 온라인 플레이어의 표시 이름을 갱신한다. 메인/엔티티 스레드에서 호출할 것. */
    fun refresh(player: Player) {
        val settings = plugin.settings.display
        val profile = plugin.profiles.of(player)
        val plate = nameplate(profile, player.name)

        if (settings.displayName) player.displayName(plate)
        if (settings.tab) player.playerListName(plate)
        if (settings.nametag) applyNametag(player, profile)
    }

    fun cleanup(player: Player) {
        if (!plugin.settings.display.nametag) return
        runCatching {
            Bukkit.getScoreboardManager().mainScoreboard.getTeam(teamName(player))?.unregister()
        }
    }

    private fun applyNametag(player: Player, profile: PlayerProfile?) {
        runCatching {
            val board = Bukkit.getScoreboardManager().mainScoreboard
            val name = teamName(player)
            val team = board.getTeam(name) ?: board.registerNewTeam(name)
            team.prefix(
                Component.empty()
                    .append(sealComponent(profile))
                    .append(titleComponent(profile)),
            )
            if (!team.hasEntry(player.name)) team.addEntry(player.name)
        }.onFailure {
            plugin.logger.warning("네임태그 적용 실패 (${player.name}): ${it.message}")
        }
    }

    private fun teamName(player: Player): String =
        "tf_" + player.uniqueId.toString().replace("-", "").substring(0, 12)

    // ── 채팅 (기본 꺼짐) ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!plugin.settings.display.chatEnabled) return
        val profile = plugin.profiles.of(event.player)
        val plate = nameplate(profile, event.player.name)
        val format = plugin.settings.display.chatFormat
        event.renderer { _, _, message, _ ->
            Text.mini(format, "nameplate" to plate, "message" to message)
        }
    }
}
