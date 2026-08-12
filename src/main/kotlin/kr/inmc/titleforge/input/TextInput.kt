package kr.inmc.titleforge.input

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 범용 텍스트 입력 SPI. 닉네임 변경과 스텟 값 입력이 공유한다.
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

/** Paper 네이티브 Dialog UI로 텍스트를 입력받는다. ESC 로는 닫히지 않고 확인/취소 버튼으로만 종료된다. */
class DialogTextInput(private val plugin: TitleForgePlugin) : TextInput {

    override fun prompt(player: Player, initial: String, prompt: Component, onResult: (String?) -> Unit) {
        var resolved = false
        fun resolve(value: String?) {
            if (resolved) return
            resolved = true
            // 클릭 콜백 처리 도중 다른 창을 열면 클라이언트가 어긋날 수 있어 다음 틱으로 미룬다.
            Sched.entity(plugin, player) { onResult(value) }
        }

        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()
        val confirmAction = DialogAction.customClick(
            DialogActionCallback { view, _ -> resolve(view.getText(FIELD_KEY)?.trim().orEmpty()) },
            options,
        )
        val cancelAction = DialogAction.customClick(
            DialogActionCallback { _, _ -> resolve(null) },
            options,
        )

        val confirmButton = ActionButton.builder(plugin.messages.component("gui.button.confirm.name"))
            .action(confirmAction)
            .build()
        val cancelButton = ActionButton.builder(plugin.messages.component("gui.button.cancel.name"))
            .action(cancelAction)
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(plugin.messages.component("input.dialog-title"))
                        .canCloseWithEscape(false)
                        .body(listOf(DialogBody.plainMessage(prompt)))
                        .inputs(
                            listOf(
                                DialogInput.text(FIELD_KEY, Component.empty())
                                    .labelVisible(false)
                                    .initial(initial)
                                    .maxLength(256)
                                    .build(),
                            ),
                        )
                        .build(),
                )
                .type(DialogType.multiAction(listOf(confirmButton), cancelButton, 2))
        }

        player.showDialog(dialog)
    }

    private companion object {
        const val FIELD_KEY = "value"
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
