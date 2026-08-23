package kr.inmc.titleforge.nickname

import kr.inmc.titleforge.TitleForgePlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import java.util.Collections
import java.util.Locale

/**
 * 다른 플러그인의 명령어에서도 닉네임을 쓰게 해 주는 다리.
 *
 * 아이디가 `ninesik` 인 사람이 닉네임을 `나인` 으로 바꿨다면,
 *
 *  1. 다른 플러그인이 플레이어 이름을 제안하는 자리에 `나인` 도 함께 뜨고 ([onTabComplete])
 *  2. `나인` 으로 친 인자는 명령어가 실행되기 직전에 `ninesik` 으로 되돌아간다 ([rewrite])
 *
 * 2번이 없으면 1번은 보기용에 그친다. 다른 플러그인은 `Bukkit.getPlayer("나인")` 을 부를 텐데
 * 서버가 아는 이름은 여전히 `ninesik` 이라 대상을 찾지 못하기 때문이다.
 *
 * ### 바닐라 명령어는 대상이 아니다
 * `/msg`, `/tp` 같은 바닐라 명령어의 플레이어 이름 제안은 **서버가 아니라 클라이언트**가
 * 자기 플레이어 목록에서 직접 만든다. 서버로 요청 자체가 오지 않아 [TabCompleteEvent] 가
 * 뜨지 않으므로 제안에 끼어들 수 없다. 인자 치환([rewrite])은 명령어 종류와 무관하게
 * 동작하므로, 닉네임을 **직접 쳐서** 바닐라 명령어를 쓰는 것은 된다.
 *
 * ### 왜 아무 자리에나 닉네임을 끼워 넣지 않는가
 * `/it create <종류>` 처럼 플레이어 이름이 아닌 자리에 닉네임이 섞이면 안 된다. 그래서
 * **서버가 그 자리에 이미 실제 아이디를 제안하고 있는지**를 보고 판단한다. 제안 목록에
 * 접속자 아이디가 하나라도 들어 있으면 플레이어 이름 칸이고, 아니면 손대지 않는다.
 */
class NicknameCommandBridge(private val plugin: TitleForgePlugin) : Listener {

    private val settings get() = plugin.settings.nickname

    /**
     * "이 자리가 플레이어 이름 칸인가" 기억.
     *
     * 판단 근거는 서버가 이미 만들어 둔 제안 목록인데, `/파티 초대 나<TAB>` 처럼 실제 아이디와
     * 한 글자도 안 겹치는 값을 치면 목록이 비어 근거가 사라진다. 그래서 같은 자리에서 한 번이라도
     * 아이디가 떴는지를 기억해 둔다. 클라이언트는 글자를 칠 때마다 제안을 요청하므로
     * `/파티 초대 ` 를 친 시점에 이미 기록된다.
     *
     * 키는 커서 앞부분 전체다. `/foo set` 과 `/foo get` 이 서로 다른 칸으로 구분된다.
     * 접근 순서 LRU 로 상한을 둬, 오타로 만들어진 키가 쌓여도 늘어나지 않는다.
     */
    private val playerSlots: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > MEMO_LIMIT
        },
    )

    fun clear() = playerSlots.clear()

    // ── 탭 완성 ────────────────────────────────────────────────────────

    /**
     * 이미 만들어진 제안 목록에 닉네임을 더한다.
     *
     * 제안이 **다 계산된 뒤에** 도는 [TabCompleteEvent] 를 쓴다. Paper 의
     * `AsyncTabCompleteEvent` 는 이보다 먼저, 목록이 아직 비어 있을 때 돌기 때문에
     * "이 자리에 아이디가 떠 있는가"를 볼 수 없다. 여기서 하는 일은 전부 메모리 조회라
     * 메인 스레드에서 돌아도 부담이 없다 (맞춤 지침 7.1-1 은 I/O 를 금지한 것이다).
     */
    // 다른 플러그인이 채워 넣은 제안까지 보고 판단해야 하므로 늦게 받는다.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        if (!settings.enabled || !settings.commandBridge.suggest) return

        val buffer = event.buffer
        val lastSpace = buffer.lastIndexOf(' ')
        // 아직 명령어 이름을 치는 중이면 인자 자리가 아니다.
        if (lastSpace < 0) return
        val token = buffer.substring(lastSpace + 1)
        val slotKey = buffer.substring(0, lastSpace).trim().lowercase(Locale.ROOT)

        val index = plugin.nicknameIndex
        val completions = event.completions

        val decided: Boolean? = when {
            completions.any { index.isRealName(it) } -> true
            completions.isNotEmpty() -> false
            // 목록이 비어 근거가 없다. 기억해 둔 게 있으면 그걸 따르고, 없으면 손대지 않는다.
            else -> null
        }
        if (decided != null) playerSlots[slotKey] = decided
        val playerSlot = decided ?: playerSlots[slotKey] ?: return
        if (!playerSlot) return

        val additions = index.suggestions(token)
            .map { it.first }
            .filter { nickname -> completions.none { it.equals(nickname, ignoreCase = true) } }
        if (additions.isEmpty()) return

        // 실제 아이디를 앞에 두고 닉네임을 뒤에 붙인다. 아이디로 대상을 지정하던 기존 습관이
        // 목록 순서 때문에 밀리지 않게 하기 위해서다.
        event.completions = completions + additions
    }

    // ── 인자 치환 ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        event.message = rewrite(event.message) ?: return
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onConsoleCommand(event: ServerCommandEvent) {
        event.command = rewrite(event.command) ?: return
    }

    /**
     * 닉네임으로 입력된 인자를 실제 아이디로 되돌린다.
     *
     * **맨 처음 일치하는 토큰 하나만** 바꾼다. `/msg 나인 나인아 안녕` 처럼 대상 뒤에 본문이
     * 오는 명령어에서 본문에 든 같은 단어까지 바꿔 버리면 대화 내용이 망가지기 때문이다.
     * 플레이어를 지목하는 인자는 사실상 항상 앞쪽에 온다.
     *
     * @param raw 플레이어는 `/` 로 시작하고 콘솔은 아니다. 0번 토큰은 명령어 이름이라 건드리지 않으므로
     *   양쪽 모두 같은 코드로 처리된다.
     * @return 바뀐 명령어. 바꿀 게 없으면 null.
     */
    private fun rewrite(raw: String): String? {
        if (!settings.enabled || !settings.commandBridge.resolve) return null
        if (raw.isEmpty()) return null

        val parts = raw.split(' ')
        if (parts.size < 2) return null

        // `essentials:msg` 처럼 네임스페이스가 붙어 들어와도 같은 명령어로 본다.
        val label = parts[0].removePrefix("/").substringAfterLast(':').lowercase(Locale.ROOT)
        if (label.isEmpty() || label in settings.commandBridge.excludedCommands) return null

        // 자기 자신은 건드리지 않는다. `/it create title 나인` 의 칭호 ID 나
        // `/it setnick ninesik 나인` 의 닉네임 인자처럼, 플레이어가 아닌 자리에도 닉네임과
        // 똑같은 값이 정상적으로 들어오기 때문이다. 대신 [TitleForgeCommand] 가 대상 자리에서만
        // 닉네임을 직접 알아듣는다 (`ProfileManager.resolveBlocking`).
        if (plugin.getCommand(label) != null) return null

        val index = plugin.nicknameIndex
        for (position in 1 until parts.size) {
            val realName = index.realNameOf(parts[position]) ?: continue
            val result = ArrayList(parts)
            result[position] = realName
            return result.joinToString(" ")
        }
        return null
    }

    private companion object {
        /** 기억해 두는 명령어 자리 수 상한. 초과하면 오래 안 쓴 것부터 버린다. */
        const val MEMO_LIMIT = 512
    }
}
