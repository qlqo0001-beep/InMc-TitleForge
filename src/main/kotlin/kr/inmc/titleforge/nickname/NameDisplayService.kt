package kr.inmc.titleforge.nickname

import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
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

    /** 표시 칭호의 MiniMessage 원문. 이름표·탭리스트 줄 조립에 쓴다. */
    fun titleMini(profile: PlayerProfile?): String =
        profile?.displayTitle?.let { plugin.badges.get(BadgeType.TITLE, it) }?.displayName
            ?: plugin.settings.title.noneDisplay

    /** 장착 인장의 MiniMessage 원문. */
    fun sealMini(profile: PlayerProfile?): String =
        profile?.seal?.let { plugin.badges.get(BadgeType.SEAL, it) }?.displayName
            ?: plugin.settings.sealNoneDisplay

    fun nicknameText(profile: PlayerProfile?, realName: String): String =
        profile?.nickname?.takeIf { it.isNotBlank() } ?: realName

    /** 유저 입력이므로 반드시 이스케이프한다 (맞춤 지침 7.5-22). */
    fun nicknameComponent(profile: PlayerProfile?, realName: String): Component =
        Component.text(nicknameText(profile, realName))

    /**
     * 이름 조합 결과.
     *
     * `nameplate-format` 에는 외부 플레이스홀더(`%cmi_user_prefix%` 등)도 쓸 수 있다.
     * [player] 를 주면 치환하고, 없으면(오프라인 프로필 등) 치환 없이 조립한다.
     */
    fun nameplate(profile: PlayerProfile?, realName: String, player: Player? = null): Component {
        val format = plugin.settings.display.nameplateFormat
        val resolved = if (player == null) format else plugin.tokens.render(player, format)
        return Text.mini(
            resolved,
            "seal" to sealComponent(profile),
            "title" to titleComponent(profile),
            "nickname" to nicknameComponent(profile, realName),
        )
    }

    /** 온라인 플레이어의 표시 이름을 갱신한다. 메인/엔티티 스레드에서 호출할 것. */
    fun refresh(player: Player) {
        val settings = plugin.settings.display
        val profile = plugin.profiles.of(player)
        val plate = nameplate(profile, player.name, player)

        if (settings.displayName) player.displayName(plate)
        if (settings.tab) player.playerListName(plate)
        // 머리 위 여러 줄 이름표는 display.NametagService 가 담당한다.
        if (settings.nametag.enabled) plugin.nametags.refresh(player)
    }

    fun cleanup(player: Player) {
        plugin.nametags.handleQuit(player)
    }

    // ── 채팅 (기본 꺼짐) ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!plugin.settings.display.chatEnabled) return
        val profile = plugin.profiles.of(event.player)
        // 표시 갱신 티커가 계속 돌고 있어 토큰 캐시는 거의 항상 채워져 있다.
        // 캐시가 비어 있을 때만 외부 플레이스홀더를 부르는데, 그 경로는 예외를 삼킨다.
        val plate = nameplate(profile, event.player.name, event.player)
        val format = plugin.settings.display.chatFormat
        event.renderer { _, _, message, _ ->
            Text.mini(format, "nameplate" to plate, "message" to message)
        }
    }
}
