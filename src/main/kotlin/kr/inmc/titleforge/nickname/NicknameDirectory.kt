package kr.inmc.titleforge.nickname

import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 닉네임이 있는 **모든** 사람(오프라인 포함)의 실명·닉네임.
 *
 * 화면 속 머리 이름 바꾸기([kr.inmc.titleforge.hook.GuiHeadNames])가 **패킷 스레드**에서 읽는다. [NicknameIndex] 는 접속자만
 * 담고(명령어 경로), 이것은 랜드 멤버 목록처럼 오프라인인 사람도 보이는 화면용이다. 닉네임으로 사람 찾기([uuidOf] —
 * `TitleForgePlugin.uuidOfNickname`, 디스코드 `/정보`)도 이것을 본다.
 *
 * 켤 때 한 번 저장소에서 비동기로 읽고([fill]), 그 뒤로는 이름 갱신(`NameDisplayService.refresh` — 접속·닉네임 변경)과
 * 오프라인 강제 해제에서 고친다. 조회는 맵만 본다 — DB 를 보지 않는다(규칙 1·5).
 */
class NicknameDirectory {

    /** [compareKey] = [NicknameNormalizer] 의 비교 키(규칙 20 — 비교 기준은 그 한 곳). */
    class Entry(val realName: String, val nickname: Component, val compareKey: String?)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** 실명(소문자) → uuid. 랜드처럼 머리 프로필에 그 사람 uuid 가 없는 화면을 이름으로 알아보려고. */
    private val byName = ConcurrentHashMap<String, UUID>()

    fun isEmpty(): Boolean = entries.isEmpty()

    fun get(uuid: UUID): Entry? = entries[uuid]

    /** 실명으로(대소문자 무시). 마크 실명은 겹치지 않는다. */
    fun byRealName(name: String): Entry? = byName[name.lowercase()]?.let(entries::get)

    /** [nickname] 은 저장된 MiniMessage 원문(이미 안전해진 값 — 규칙 24). 비면 뺀다. [realName] 이 null 이면 아는 실명을 그대로. */
    fun put(uuid: UUID, realName: String?, nickname: String?) {
        if (nickname.isNullOrBlank()) {
            entries.remove(uuid)?.let { byName.remove(it.realName.lowercase(), uuid) }
            return
        }
        val name = realName?.takeIf { it.isNotBlank() } ?: entries[uuid]?.realName ?: return
        store(uuid, entry(name, nickname))
    }

    /** 켤 때 읽은 저장소 값. 그새 접속해 [put] 된 사람(더 최신)은 덮지 않는다. */
    fun fill(rows: List<Triple<UUID, String, String>>) {
        for ((uuid, name, nickname) in rows) {
            if (entries.containsKey(uuid) || name.isBlank()) continue
            store(uuid, entry(name, nickname))
        }
    }

    private fun store(uuid: UUID, entry: Entry) {
        entries.put(uuid, entry)?.let { old -> if (old.realName != entry.realName) byName.remove(old.realName.lowercase(), uuid) }
        byName[entry.realName.lowercase()] = uuid
    }

    /**
     * 닉네임으로 사람 찾기(디스코드 `/정보 파노` 등 — 오프라인도). 같은 비교 키를 두 명 이상이 쓰면 null —
     * 애매하면 고르지 않는다([NicknameIndex] 와 같은 원칙).
     */
    fun uuidOf(nickname: String): UUID? {
        val key = NicknameNormalizer.normalize(nickname) ?: return null
        return entries.entries.filter { it.value.compareKey == key }.singleOrNull()?.key
    }

    private fun entry(realName: String, nickname: String) = Entry(realName, Text.mini(nickname), NicknameNormalizer.normalize(nickname))
}
