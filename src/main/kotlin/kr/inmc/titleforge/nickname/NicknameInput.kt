package kr.inmc.titleforge.nickname

import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Sched
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.view.AnvilView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 닉네임 입력 창 SPI.
 *
 * 네이티브 Paper Dialog API 구현체를 추가할 경우 이 인터페이스만 구현해
 * [kr.inmc.titleforge.TitleForgePlugin] 에서 교체하면 된다.
 */
interface NicknameInput {

    /**
     * @param onResult 입력 완료 시 문자열, 취소 시 null. **메인 스레드에서 호출된다.**
     */
    fun prompt(player: Player, initial: String, onResult: (String?) -> Unit)
}

/** 모루 이름 변경 칸을 팝업 입력창으로 사용한다. */
class AnvilNicknameInput(private val plugin: TitleForgePlugin) : NicknameInput, Listener {

    private class Session(val callback: (String?) -> Unit) {
        var completed = false
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    override fun prompt(player: Player, initial: String, onResult: (String?) -> Unit) {
        val opened = runCatching {
            val view = player.openAnvil(null, true) ?: return@runCatching false
            view.topInventory.setItem(
                0,
                Items.of(Material.NAME_TAG, Component.text(initial.ifBlank { player.name })),
            )
            true
        }.getOrElse { false }

        if (!opened) {
            plugin.logger.warning("모루 입력창을 열지 못해 채팅 입력으로 대체합니다.")
            plugin.chatInput.prompt(player, initial, onResult)
            return
        }
        sessions[player.uniqueId] = Session(onResult)
    }

    @EventHandler
    fun onPrepare(event: PrepareAnvilEvent) {
        val player = event.viewers.firstOrNull() as? Player ?: return
        if (!sessions.containsKey(player.uniqueId)) return
        val view = event.view as? AnvilView
        val typed = view?.renameText.orEmpty()
        view?.repairCost = 0
        event.result = Items.of(
            Material.NAME_TAG,
            plugin.messages.component("nickname.prompt-title").append(Component.text(": $typed")),
            listOf(Component.text(typed.ifBlank { "..." })),
        )
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.view.topInventory.type != InventoryType.ANVIL) return
        event.isCancelled = true
        if (event.rawSlot != RESULT_SLOT) return

        val view = event.view as? AnvilView ?: return
        val text = view.renameText.orEmpty().trim()
        if (text.isEmpty()) return

        session.completed = true
        sessions.remove(player.uniqueId)
        event.view.topInventory.clear()
        player.closeInventory()
        session.callback(text)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = sessions.remove(player.uniqueId) ?: return
        event.inventory.clear()
        if (session.completed) return
        Sched.entity(plugin, player) { session.callback(null) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
    }
}

/** 채팅 입력 폴백. */
class ChatNicknameInput(private val plugin: TitleForgePlugin) : NicknameInput, Listener {

    private val pending = ConcurrentHashMap<UUID, (String?) -> Unit>()

    override fun prompt(player: Player, initial: String, onResult: (String?) -> Unit) {
        pending[player.uniqueId] = onResult
        player.closeInventory()
        plugin.messages.send(player, "nickname.prompt-chat")
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val callback = pending.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        val player = event.player
        Sched.entity(plugin, player) {
            if (raw.equals("취소", true) || raw.equals("cancel", true)) {
                callback(null)
            } else {
                callback(raw)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)
    }
}

private const val RESULT_SLOT = 2
