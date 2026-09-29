package kr.inmc.titleforge.nickname

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Text
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 접속 중인 사람의 "실제 아이디 ↔ 닉네임" 색인.
 *
 * [NicknameCommandBridge] 가 탭 완성과 인자 치환에서 읽는다. 둘 다 유저가 글자를 칠 때마다
 * 불리는 경로라 DB 는 물론 프로필 캐시 순회나 `Bukkit.getOnlinePlayers()` 조차 거치지 않고
 * **불변 스냅샷 하나**만 읽는다 (맞춤 지침 7.2-5).
 *
 * 저장된 닉네임은 MiniMessage 원문이라 색이 섞여 있을 수 있다. 명령어에 실제로 칠 수 있는
 * 값이어야 하므로 **서식을 걷어낸 평문**을 색인하고, 비교 키는 [NicknameNormalizer] 를 그대로
 * 쓴다 — 애플리케이션·DB·명령어가 서로 다른 기준을 쓰지 않게 하기 위해서다 (맞춤 지침 7.4-20).
 *
 * 오프라인 대상은 담지 않는다. 담으려면 조회 때마다 DB 를 봐야 하는데 그건 이 경로에서
 * 금지되어 있다 (맞춤 지침 7.1-1).
 */
class NicknameIndex(private val plugin: TitleForgePlugin) {

    /** 접속자 원본. 갱신은 여기에 하고 읽기는 [snapshot] 으로만 한다. */
    private val tracked = ConcurrentHashMap<UUID, Tracked>()

    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY

    private class Tracked(val realName: String, val nickname: String?)

    /**
     * 읽기 전용 스냅샷.
     *
     * 증분 갱신 대신 통째로 다시 만든다. 접속자 수만큼의 비용이지만 접속·닉네임 변경·퇴장
     * 때만 돌고, 대신 읽는 쪽이 부분 갱신된 중간 상태를 절대 보지 않는다
     * ([kr.inmc.titleforge.config.Settings] 의 스냅샷 교체와 같은 방식).
     */
    private class Snapshot(
        /** 접속자 실명(소문자). "이 자리가 플레이어 이름 칸인가" 판정에 쓴다. */
        val realNames: Set<String>,
        /**
         * 비교 키 → 실명.
         *
         * 같은 키를 두 명 이상이 쓰면 **아예 뺀다.** 치환은 대상을 한 명으로 확정하지 못하면
         * 엉뚱한 사람을 가리키게 되므로, 애매하면 손대지 않는 쪽이 안전하다
         * (`nickname.unique: false` 로 중복을 허용한 서버에서 일어날 수 있다).
         */
        val owners: Map<String, String>,
        /** 제안용 (닉네임 평문, 실명) 목록. 닉네임 순 정렬. */
        val suggestions: List<Pair<String, String>>,
    ) {
        companion object {
            val EMPTY = Snapshot(emptySet(), emptyMap(), emptyList())
        }
    }

    // ── 갱신 ───────────────────────────────────────────────────────────

    /**
     * 접속·닉네임 변경 시점에 부른다.
     *
     * 호출부를 여기저기 두지 않고 [NameDisplayService.refresh] 한 곳에 얹었다. 접속·닉네임
     * 변경·칭호 장착·리로드 복구가 전부 그 함수를 지나므로 색인이 저절로 따라온다.
     */
    fun track(player: Player) {
        val nickname = plugin.profiles.of(player)?.nickname
            ?.takeIf { it.isNotBlank() }
            ?.let { Text.plain(Text.mini(it)).trim() }
            // 명령어 인자는 공백으로 잘리므로 공백이 든 닉네임(`allow-space: true`)은 색인하지
            // 않는다. 한 토큰으로 칠 수 없어 제안해도 고를 수 없고 치환도 성립하지 않는다.
            ?.takeIf { it.isNotEmpty() && !it.contains(' ') }

        val previous = tracked.put(player.uniqueId, Tracked(player.name, nickname))
        if (previous != null && previous.realName == player.name && previous.nickname == nickname) return
        rebuild()
    }

    fun forget(uuid: UUID) {
        if (tracked.remove(uuid) != null) rebuild()
    }

    fun clear() {
        if (tracked.isEmpty()) return
        tracked.clear()
        rebuild()
    }

    /**
     * 마지막으로 끝난 재작성이 반드시 마지막 변경까지 반영하도록 직렬화한다.
     * 접속·닉네임 변경·퇴장 때만 돌아 경합이 사실상 없고, 없으면 퇴장한 사람이
     * 색인에 남는 창이 생긴다.
     */
    @Synchronized
    private fun rebuild() {
        val realNames = HashSet<String>()
        val owners = HashMap<String, String>()
        val duplicated = HashSet<String>()
        val suggestions = ArrayList<Pair<String, String>>()

        for (entry in tracked.values) {
            realNames.add(entry.realName.lowercase(Locale.ROOT))
            val nickname = entry.nickname ?: continue
            val key = NicknameNormalizer.normalize(nickname) ?: continue
            if (owners.put(key, entry.realName) != null) duplicated.add(key)
            suggestions.add(nickname to entry.realName)
        }
        for (key in duplicated) owners.remove(key)

        snapshot = Snapshot(
            realNames = realNames,
            owners = owners,
            suggestions = suggestions
                .distinctBy { it.first.lowercase(Locale.ROOT) }
                .sortedBy { it.first },
        )
    }

    // ── 조회 ───────────────────────────────────────────────────────────

    /** [value] 가 접속 중인 사람의 실제 아이디인가. */
    fun isRealName(value: String): Boolean =
        snapshot.realNames.contains(value.lowercase(Locale.ROOT))

    /** [prefix] 로 시작하는 (닉네임 평문, 실명) 제안. 빈 문자열이면 전부. */
    fun suggestions(prefix: String): List<Pair<String, String>> {
        val all = snapshot.suggestions
        if (prefix.isEmpty()) return all
        return all.filter { it.first.startsWith(prefix, ignoreCase = true) }
    }

    /**
     * 닉네임으로 입력된 토큰을 실제 아이디로 되돌린다.
     *
     * @return 실제 아이디. 실명이 그대로 들어왔거나, 닉네임이 아니거나,
     *   같은 닉네임을 쓰는 사람이 둘 이상이면 null.
     */
    fun realNameOf(token: String): String? {
        val current = snapshot
        // 실명이 우선이다. 남의 닉네임과 내 아이디가 겹쳐도 아이디로 지목한 쪽을 존중한다.
        if (current.realNames.contains(token.lowercase(Locale.ROOT))) return null
        val key = NicknameNormalizer.normalize(token) ?: return null
        return current.owners[key]
    }
}
