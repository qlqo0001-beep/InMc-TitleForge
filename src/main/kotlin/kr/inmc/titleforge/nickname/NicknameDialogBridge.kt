package kr.inmc.titleforge.nickname

import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.input.DialogTextInput
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 다른 플러그인의 **다이얼로그 입력창**에서도 닉네임을 쓰게 해 주는 다리.
 *
 * [NicknameCommandBridge] 는 명령어만 본다. Lands 의 "Add Player" 창처럼 입력값을 명령어가
 * 아니라 커스텀 클릭으로 받는 다이얼로그는 그 경로를 지나지 않아, 닉네임을 치면 대상을 찾지 못한다.
 *
 * ### 어떻게 끼어드는가
 * 버튼을 누르면 클라이언트가 입력값을 NBT 로 담아 보내고, Paper 는 그 **같은 객체**를
 *  1. [PlayerCustomClickEvent] 리스너에게 보여 준 뒤
 *  2. `DialogAction.customClick` 콜백에 넘긴다.
 *
 * 그래서 [EventPriority.LOWEST] 에서 값을 바꿔 두면 이벤트로 받든 콜백으로 받든 다른 플러그인은
 * 실제 아이디를 받는다. Paper API 는 이 값을 읽기만 하게 해 두어 쓰기는 리플렉션으로 한다([Access]).
 * 서버 내부가 달라 실패하면 한 번 경고하고 이후로는 손대지 않는다 — 다이얼로그는 원래대로 동작한다.
 *
 * ### 무엇을 바꾸는가
 * 입력칸 값 **전체**가 접속 중인 한 사람의 닉네임일 때만 바꾼다. 문장 안에 닉네임이 섞인 값은
 * 건드리지 않는다. 칸이 따로 나뉘어 있으므로 명령어처럼 "맨 앞 하나" 규칙은 두지 않는다.
 *
 * 이 플러그인 자신의 입력창([DialogTextInput])은 제외한다. 닉네임 변경 창에 남의 닉네임을 치면
 * 그 사람의 아이디로 바뀌어 버리기 때문이다.
 *
 * 패킷 처리 스레드에서 돈다. 하는 일은 [NicknameIndex] 스냅샷 조회뿐이다.
 */
class NicknameDialogBridge(private val plugin: TitleForgePlugin) : Listener {

    private val settings get() = plugin.settings.nickname

    @Volatile
    private var access: Access? = null

    /** 리플렉션이 한 번 실패하면 켜진다. 매 클릭마다 같은 경고를 남기지 않기 위해서다. */
    @Volatile
    private var broken = false

    @EventHandler(priority = EventPriority.LOWEST)
    fun onCustomClick(event: PlayerCustomClickEvent) {
        if (broken || !settings.enabled || !settings.commandBridge.resolveDialog) return
        // 다이얼로그 응답이 아니면(실린 NBT 가 compound 가 아니면) null 이다.
        // getTag() 는 부르지 않는다 — 처음 부를 때 인코딩한 값을 캐시해 두므로, 바꾸기 전에 부르면
        // 그 뒤에 이 이벤트를 받는 플러그인이 옛 값을 보게 된다.
        val view = event.dialogResponseView ?: return

        runCatching {
            val access = access ?: Access(view).also { access = it }
            val payload = access.payload(view)
            val keys = access.keys(payload)
            if (DialogTextInput.FIELD_KEY in keys) return

            val index = plugin.nicknameIndex
            for (key in keys) {
                val value = view.getText(key) ?: continue
                val realName = index.realNameOf(value.trim()) ?: continue
                access.putString(payload, key, realName)
            }
        }.onFailure {
            broken = true
            plugin.logger.warning("다이얼로그 닉네임 치환을 끕니다 (서버 내부 구조가 달라 입력값을 바꿀 수 없음): $it")
        }
    }

    /**
     * Paper 내부 접근. Paper 26.2 기준 `PaperDialogResponseView.payload`(`CompoundTag`) 와
     * `CompoundTag.keySet()` · `putString(String, String)` 을 쓴다. 서버는 Mojang 이름 그대로
     * 돌기 때문에 이 이름들이 실행 시점에도 있다.
     */
    private class Access(view: DialogResponseView) {
        private val payloadField: Field =
            view.javaClass.getDeclaredField("payload").apply { isAccessible = true }
        private val keySet: Method = payloadField.type.getMethod("keySet")
        private val putString: Method =
            payloadField.type.getMethod("putString", String::class.java, String::class.java)

        fun payload(view: DialogResponseView): Any = payloadField.get(view)

        /** 바꾸는 도중 원본을 돌지 않도록 복사해서 준다. */
        fun keys(payload: Any): List<String> = (keySet.invoke(payload) as Set<*>).filterIsInstance<String>()

        fun putString(payload: Any, key: String, value: String) {
            putString.invoke(payload, key, value)
        }
    }
}
