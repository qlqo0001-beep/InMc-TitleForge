package kr.inmc.titleforge.badge

import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 메모리 칭호/인장 레지스트리.
 *
 * 조회는 항상 이곳에서만 이뤄진다 (맞춤 지침 7.2-5). DB 는 로드/저장 경로에만 등장.
 * 정렬 결과는 캐시하며 변경 시에만 무효화한다.
 */
class BadgeRegistry {

    private val storage: Map<BadgeType, ConcurrentHashMap<String, Badge>> =
        EnumMap<BadgeType, ConcurrentHashMap<String, Badge>>(BadgeType::class.java).apply {
            BadgeType.entries.forEach { put(it, ConcurrentHashMap()) }
        }

    @Volatile
    private var sorted: Map<BadgeType, List<Badge>> = emptyMap()

    fun replaceAll(badges: Collection<Badge>) {
        storage.values.forEach { it.clear() }
        badges.forEach { storage.getValue(it.type)[it.id] = it }
        BadgeType.entries.forEach { invalidate(it) }
    }

    fun put(badge: Badge) {
        storage.getValue(badge.type)[badge.id] = badge
        invalidate(badge.type)
    }

    fun remove(type: BadgeType, id: String): Badge? {
        val removed = storage.getValue(type).remove(id)
        if (removed != null) invalidate(type)
        return removed
    }

    fun get(type: BadgeType, id: String): Badge? = storage.getValue(type)[id]

    fun exists(type: BadgeType, id: String): Boolean = storage.getValue(type).containsKey(id)

    fun all(type: BadgeType): List<Badge> = sorted[type] ?: emptyList()

    fun count(type: BadgeType): Int = storage.getValue(type).size

    fun ids(type: BadgeType): Collection<String> = storage.getValue(type).keys

    fun total(): Int = BadgeType.entries.sumOf { count(it) }

    /**
     * 바뀐 분류만 다시 정렬한다. 편집 1회에 양쪽을 모두 정렬할 이유가 없다.
     *
     * 읽고(putAll) 수정하고 통째로 다시 쓰는 구조라 동기화가 없으면, 서로 다른 분류를
     * 동시에 편집할 때(Folia 에서는 관리자마다 리전 스레드가 다를 수 있다) 나중에 쓰는
     * 쪽이 먼저 쓴 쪽의 갱신을 스냅샷째로 덮어써 잃어버릴 수 있다.
     */
    @Synchronized
    private fun invalidate(type: BadgeType) {
        val snapshot = EnumMap<BadgeType, List<Badge>>(BadgeType::class.java)
        snapshot.putAll(sorted)
        snapshot[type] = storage.getValue(type).values
            .sortedWith(compareBy({ -it.order }, { -it.rarity.weight }, { it.id }))
        sorted = snapshot
    }
}
