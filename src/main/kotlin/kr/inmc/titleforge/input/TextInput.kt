package kr.inmc.titleforge.input

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
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.view.AnvilView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 범용 텍스트 입력 SPI. 닉네임 변경과 스텟 값 입력이 공유한다.
 *
 * 네이티브 Paper Dialog API 구현체를 추가할 경우 이 인터페이스만 구현해
 * [kr.inmc.titleforge.TitleForgePlugin] 에서 교체하면 된다.
 */
interface TextInput {

    /**
     * 입력창을 띄운다.
     *
     * @param prompt   안내 문구. 채팅 입력기는 이 문구를 그대로 보낸다.
     * @param onResult 입력 완료 시 문자열, 취소 시 null. **메인/엔티티 스레드에서 호출된다.**
     */
    fun prompt(player: Player, initial: String, prompt: Component, onResult: (String?) -> Unit)
}

/** 모루 이름 변경 칸을 팝업 입력창으로 사용한다. */
class AnvilTextInput(private val plugin: TitleForgePlugin) : TextInput, Listener {

    private class Session(val callback: (String?) -> Unit) {
        var completed = false
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    override fun prompt(player: Player, initial: String, prompt: Component, onResult: (String?) -> Unit) {
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
            plugin.chatInput.prompt(player, initial, prompt, onResult)
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
            plugin.messages.component("input.anvil-confirm").append(Component.text(": $typed")),
            listOf(Component.text(typed.ifBlank { "..." })),
        )
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.view.topInventory.type != InventoryType.ANVIL) return
        event.isCancelled = true
        if (event.rawSlot != ANVIL_RESULT_SLOT) return

        val view = event.view as? AnvilView ?: return
        val text = view.renameText.orEmpty().trim()
        if (text.isEmpty()) return

        session.completed = true
        sessions.remove(player.uniqueId)
        event.view.topInventory.clear()
        player.closeInventory()
        // 인벤토리 이벤트 처리 도중 다른 창을 열면 클라이언트가 어긋날 수 있어 다음 틱으로 미룬다.
        Sched.entity(plugin, player) { session.callback(text) }
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

/**
 * 채팅 입력.
 *
 * 입력한 문장은 **어디에도 기록되지 않는다.** 세션이 있으면 [EventPriority.LOWEST] 에서 즉시
 * 이벤트를 취소하므로 브로드캐스트 자체가 일어나지 않아 다른 플레이어 화면 · 본인 화면 · 서버
 * 콘솔 어디에도 남지 않는다. (클라이언트가 자체적으로 보관하는 입력 히스토리는 서버 영역 밖이다.)
 */
class ChatTextInput(private val plugin: TitleForgePlugin) : TextInput, Listener {

    private class Session(val id: Long, val callback: (String?) -> Unit)

    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val sequence = AtomicLong()

    override fun prompt(player: Player, initial: String, prompt: Component, onResult: (String?) -> Unit) {
        val session = Session(sequence.incrementAndGet(), onResult)
        sessions[player.uniqueId] = session
        player.closeInventory()
        player.sendMessage(prompt)

        val timeout = plugin.settings.gui.chatInputTimeoutSeconds
        if (timeout > 0) {
            Sched.asyncDelayed(plugin, timeout) {
                // 같은 세션이 아직 살아 있을 때만 만료 처리한다.
                val current = sessions[player.uniqueId] ?: return@asyncDelayed
                if (current.id != session.id) return@asyncDelayed
                sessions.remove(player.uniqueId, current)
                Sched.entity(plugin, player) {
                    plugin.messages.send(player, "input.timeout")
                    current.callback(null)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val session = sessions.remove(event.player.uniqueId) ?: return
        // 가장 먼저 취소해 로그·브로드캐스트 경로를 모두 차단한다.
        event.isCancelled = true

        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        val player = event.player
        Sched.entity(plugin, player) {
            if (isCancelKeyword(raw)) session.callback(null) else session.callback(raw)
        }
    }

    /** 입력 대기 중 명령어를 치면 입력을 취소하고 명령어는 그대로 실행한다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val session = sessions.remove(event.player.uniqueId) ?: return
        val player = event.player
        Sched.entity(plugin, player) {
            plugin.messages.send(player, "input.cancelled-by-command")
            session.callback(null)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
    }

    fun cancel(player: Player) {
        sessions.remove(player.uniqueId)
    }

    private companion object {
        fun isCancelKeyword(raw: String): Boolean =
            raw.equals("취소", true) || raw.equals("cancel", true) || raw.equals("c", true)
    }
}

private const val ANVIL_RESULT_SLOT = 2
