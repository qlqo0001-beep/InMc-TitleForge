package kr.inmc.titleforge.hook

import kr.inmc.titleforge.TitleForgePlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%titleforge_...%` 플레이스홀더를 PlaceholderAPI 에 내준다. 값은 [TitleForgeValues] 가 정한다 —
 * 이 플러그인의 탭리스트·이름표는 PAPI 없이도 같은 값을 쓴다.
 */
class PlaceholderHook(plugin: TitleForgePlugin) : PlaceholderExpansion() {

    private val values = TitleForgeValues(plugin)

    private val version = plugin.pluginMeta.version

    override fun getIdentifier(): String = "titleforge"

    override fun getAuthor(): String = "InMc"

    override fun getVersion(): String = version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = values.resolve(player, params)
}
