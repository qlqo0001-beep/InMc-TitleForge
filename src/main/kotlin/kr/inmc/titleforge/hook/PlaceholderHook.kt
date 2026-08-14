package kr.inmc.titleforge.hook

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Text
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
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
            // 받아 가는 쪽(TAB·채팅 플러그인)은 MiniMessage 를 모르므로 레거시(§) 로 내보낸다.
            // 색이 없는 평범한 닉네임은 그대로 나가므로 기존 동작과 같다.
            params.equals("nickname", true) ->
                Text.toLegacy(Text.mini(display.nicknameText(profile, realName)))
            params.equals("nickname_mini", true) -> display.nicknameText(profile, realName)
            params.equals("realname", true) -> realName
            params.equals("has_nickname", true) -> yesNo(profile?.nickname != null)

            params.equals("title_display", true) -> Text.plain(display.titleComponent(profile))
            params.equals("title_display_mini", true) -> miniOf(profile?.displayTitle, BadgeType.TITLE)
            params.equals("title_stat", true) -> Text.plain(display.statTitleComponent(profile))
            params.equals("title_stat_mini", true) -> miniOf(profile?.statTitle, BadgeType.TITLE)
            params.equals("seal", true) -> Text.plain(display.sealComponent(profile))
            params.equals("seal_mini", true) -> miniOf(profile?.seal, BadgeType.SEAL)
            // <seal>/<title>/<nickname> 은 항상 반영되지만, nameplate-format 안의 %...% 토큰은
            // 온라인일 때만 반영된다(치환기가 실제 Player 를 필요로 함). 다른 플러그인이
            // titleforge_nameplate 를 오프라인 대상으로 요청하는 드문 경우에만 영향이 있다.
            params.equals("nameplate", true) ->
                Text.plain(display.nameplate(profile, realName, Bukkit.getPlayer(uuid)))

            params.equals("title_count", true) -> (profile?.count(BadgeType.TITLE) ?: 0).toString()
            params.equals("title_total", true) -> plugin.badges.count(BadgeType.TITLE).toString()
            params.equals("title_percent", true) -> percent(profile, BadgeType.TITLE)
            params.equals("seal_count", true) -> (profile?.count(BadgeType.SEAL) ?: 0).toString()
            params.equals("seal_total", true) -> plugin.badges.count(BadgeType.SEAL).toString()
            params.equals("seal_percent", true) -> percent(profile, BadgeType.SEAL)

            params.startsWith("stat_equip_", true) -> statValue(profile?.equipStats, params.removePrefix("stat_equip_"))
            params.startsWith("stat_own_", true) -> statValue(profile?.ownStats, params.removePrefix("stat_own_"))
            params.startsWith("stat_", true) -> statValue(profile?.totalStats, params.removePrefix("stat_"))

            params.startsWith("expiry_seconds_", true) ->
                expirySeconds(profile, params.removePrefix("expiry_seconds_"))

            params.startsWith("expiry_", true) -> expiryText(profile, params.removePrefix("expiry_"))

            params.equals("rank_title", true) -> rankOf(uuid, BadgeType.TITLE)
            params.equals("rank_seal", true) -> rankOf(uuid, BadgeType.SEAL)
            params.startsWith("rank_top_", true) -> topEntry(params.removePrefix("rank_top_"))

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

    private fun statValue(stats: Map<String, Double>?, rawId: String): String {
        val stat = plugin.stats.of(rawId) ?: return ""
        val value = stats?.get(stat.id) ?: 0.0
        return Text.number(value * stat.displayScale, stat.decimals)
    }

    /** `expiry_title_<id>` → "6일 3시간" / 영구면 설정된 문구. */
    private fun expiryText(profile: PlayerProfile?, raw: String): String {
        val (type, id) = splitTypeAndId(raw) ?: return ""
        if (profile?.has(type, id) != true) return ""
        val remaining = profile.remainingSeconds(type, id) ?: return plugin.messages.raw("placeholder.permanent")
        return Text.duration(remaining)
    }

    /** `expiry_seconds_title_<id>` → 남은 초. 영구면 -1, 미보유면 빈 문자열. */
    private fun expirySeconds(profile: PlayerProfile?, raw: String): String {
        val (type, id) = splitTypeAndId(raw) ?: return ""
        if (profile?.has(type, id) != true) return ""
        return (profile.remainingSeconds(type, id) ?: -1L).toString()
    }

    private fun splitTypeAndId(raw: String): Pair<BadgeType, String>? {
        val separator = raw.indexOf('_')
        if (separator <= 0) return null
        val type = BadgeType.of(raw.substring(0, separator)) ?: return null
        val id = raw.substring(separator + 1)
        return if (id.isEmpty()) null else type to id
    }

    private fun rankOf(uuid: java.util.UUID, type: BadgeType): String {
        val entry = plugin.rank.cachedPersonal(uuid, type)
        if (entry == null) {
            // 캐시가 없으면 비동기로 채워두고 이번 호출은 빈 값으로 돌려준다.
            plugin.rank.requestPersonal(uuid, type)
            return ""
        }
        return entry.rank.toString()
    }

    /** `rank_top_title_1` → 이름, `rank_top_title_1_count` → 보유 수. */
    private fun topEntry(raw: String): String {
        val parts = raw.split('_')
        if (parts.size < 2) return ""
        val type = BadgeType.of(parts[0]) ?: return ""
        val position = parts[1].toIntOrNull() ?: return ""
        val snapshot = plugin.rank.cached(type) ?: return ""
        val entry = snapshot.entries.getOrNull(position - 1) ?: return ""
        return if (parts.size >= 3 && parts[2].equals("count", true)) entry.count.toString() else entry.name
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
