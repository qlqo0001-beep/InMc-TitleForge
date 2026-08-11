package kr.inmc.titleforge.hook

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.stat.StatType
import kr.inmc.titleforge.util.Text
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%titleforge_...%` 플레이스홀더.
 *
 * TAB 등 고빈도 호출자를 고려해 **메모리 캐시만** 조회한다. DB·계산 없음 (맞춤 지침 7.2-5, 7.2-7).
 */
class PlaceholderHook(private val plugin: TitleForgePlugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "titleforge"

    override fun getAuthor(): String = "InMc"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val uuid = player?.uniqueId ?: return null
        val profile: PlayerProfile? = plugin.profiles.cached(uuid)
        val realName = player.name ?: ""
        val display = plugin.nameDisplay

        return when {
            params.equals("nickname", true) -> display.nicknameText(profile, realName)
            params.equals("realname", true) -> realName
            params.equals("has_nickname", true) -> yesNo(profile?.nickname != null)

            params.equals("title_display", true) -> Text.plain(display.titleComponent(profile))
            params.equals("title_display_mini", true) -> miniOf(profile?.displayTitle, BadgeType.TITLE)
            params.equals("title_stat", true) -> Text.plain(display.statTitleComponent(profile))
            params.equals("title_stat_mini", true) -> miniOf(profile?.statTitle, BadgeType.TITLE)
            params.equals("seal", true) -> Text.plain(display.sealComponent(profile))
            params.equals("seal_mini", true) -> miniOf(profile?.seal, BadgeType.SEAL)
            params.equals("nameplate", true) -> Text.plain(display.nameplate(profile, realName))

            params.equals("title_count", true) -> (profile?.count(BadgeType.TITLE) ?: 0).toString()
            params.equals("title_total", true) -> plugin.badges.count(BadgeType.TITLE).toString()
            params.equals("title_percent", true) -> percent(profile, BadgeType.TITLE)
            params.equals("seal_count", true) -> (profile?.count(BadgeType.SEAL) ?: 0).toString()
            params.equals("seal_total", true) -> plugin.badges.count(BadgeType.SEAL).toString()
            params.equals("seal_percent", true) -> percent(profile, BadgeType.SEAL)

            params.startsWith("stat_equip_", true) -> statValue(profile?.equipStats, params.removePrefix("stat_equip_"))
            params.startsWith("stat_own_", true) -> statValue(profile?.ownStats, params.removePrefix("stat_own_"))
            params.startsWith("stat_", true) -> statValue(profile?.totalStats, params.removePrefix("stat_"))

            params.startsWith("has_title_", true) ->
                yesNo(profile?.has(BadgeType.TITLE, params.removePrefix("has_title_")) == true)

            params.startsWith("has_seal_", true) ->
                yesNo(profile?.has(BadgeType.SEAL, params.removePrefix("has_seal_")) == true)

            else -> null
        }
    }

    private fun miniOf(id: String?, type: BadgeType): String {
        val badge = id?.let { plugin.badges.get(type, it) } ?: return ""
        return badge.displayName
    }

    private fun percent(profile: PlayerProfile?, type: BadgeType): String {
        val total = plugin.badges.count(type)
        if (total == 0) return "0"
        val owned = profile?.count(type) ?: 0
        return Text.number(owned * 100.0 / total, 1)
    }

    private fun statValue(stats: Map<StatType, Double>?, rawId: String): String {
        val stat = StatType.of(rawId) ?: return ""
        val value = stats?.get(stat) ?: 0.0
        return Text.number(value * stat.displayScale, stat.decimals)
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
