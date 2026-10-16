package kr.inmc.titleforge.display

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 탭리스트 머리말/꼬리말.
 *
 * 토큰 치환과 재계산 주기는 [TokenRenderer] 가 담당한다. 여기서는 **렌더 결과가 바뀐
 * 경우에만** 패킷을 보내는 일만 한다.
 */
class TablistService(private val plugin: TitleForgePlugin) {

    private val lastSent = ConcurrentHashMap<UUID, String>()

    private val settings get() = plugin.settings.display.tablist

    fun refresh(player: Player) {
        if (!settings.enabled) {
            if (lastSent.remove(player.uniqueId) != null) {
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty())
            }
            return
        }

        // 머리말·꼬리말이 자리표시자 이름을 공유하도록 한 세션에서 처리한다.
        val session = plugin.tokens.session(perViewer = true)
        val header = render(session, player, settings.header)
        val footer = render(session, player, settings.footer)

        // 값이 자리표시자로 빠져 있으므로 변경 감지에는 값(signature)까지 포함한다.
        val combined = "$header $footer ${session.signature()}"
        if (lastSent[player.uniqueId] == combined) return
        lastSent[player.uniqueId] = combined

        // 파싱은 바뀐 것을 확인한 뒤에만 — 매 틱 사람마다 도는 자리다.
        val placeholders = session.placeholders()
        player.sendPlayerListHeaderAndFooter(
            Text.mini(header, *placeholders),
            Text.mini(footer, *placeholders),
        )
    }

    fun handleQuit(player: Player) {
        lastSent.remove(player.uniqueId)
    }

    fun clear() {
        lastSent.clear()
    }

    private fun render(session: TokenRenderer.Session, player: Player, lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return lines.joinToString("<newline>") { line -> session.render(player, line) }
    }
}
