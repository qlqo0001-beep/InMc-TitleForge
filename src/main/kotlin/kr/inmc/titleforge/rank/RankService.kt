package kr.inmc.titleforge.rank

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.storage.RankEntry
import kr.inmc.titleforge.util.Sched
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 칭호·인장 **수집 개수 순위**.
 *
 * 집계는 DB 에서 하고 결과는 TTL 캐시에 담는다. 조회는 항상 캐시를 먼저 보므로
 * GUI 를 여러 명이 동시에 열어도 쿼리가 몰리지 않는다. 만료된 보유는 집계에서 제외된다.
 */
class RankService(private val plugin: TitleForgePlugin) {

    class Snapshot(
        val entries: List<RankEntry>,
        val total: Int,
        val takenAt: Long,
    )

    private val cache = EnumMap<BadgeType, Snapshot>(BadgeType::class.java)
    private val refreshing = EnumMap<BadgeType, AtomicBoolean>(BadgeType::class.java).apply {
        BadgeType.entries.forEach { put(it, AtomicBoolean(false)) }
    }

    /** 개인 순위 캐시. 순위 GUI 하단과 플레이스홀더가 쓴다. */
    private val personal = HashMap<UUID, EnumMap<BadgeType, RankEntry>>()

    private val settings get() = plugin.settings.rank

    @Synchronized
    fun cached(type: BadgeType): Snapshot? = cache[type]

    @Synchronized
    fun cachedPersonal(uuid: UUID, type: BadgeType): RankEntry? = personal[uuid]?.get(type)

    fun stale(type: BadgeType): Boolean {
        val snapshot = cached(type) ?: return true
        return System.currentTimeMillis() - snapshot.takenAt > settings.cacheSeconds * 1000L
    }

    /**
     * 필요하면 비동기로 갱신하고, 끝나면 [onReady] 를 메인 스레드에서 호출한다.
     * 이미 신선하면 즉시 호출한다.
     */
    fun request(type: BadgeType, force: Boolean = false, onReady: (Snapshot?) -> Unit) {
        if (!settings.enabled) {
            onReady(null)
            return
        }
        val current = cached(type)
        if (!force && current != null && !stale(type)) {
            onReady(current)
            return
        }
        val guard = refreshing.getValue(type)
        if (!guard.compareAndSet(false, true)) {
            // 이미 누군가 갱신 중이면 있는 값으로 응답한다.
            onReady(current)
            return
        }

        Sched.async(plugin) {
            val snapshot = runCatching {
                Snapshot(
                    entries = plugin.storage.topCollectors(type, settings.topSize),
                    total = plugin.storage.collectorCount(type),
                    takenAt = System.currentTimeMillis(),
                )
            }.getOrElse {
                plugin.logger.severe("순위 조회 실패 (${type.id}): ${it.message}")
                null
            }
            guard.set(false)
            if (snapshot != null) store(type, snapshot)
            Sched.global(plugin) { onReady(snapshot ?: current) }
        }
    }

    /**
     * 특정 플레이어의 순위를 비동기로 조회해 캐시에 넣는다.
     *
     * 플레이스홀더가 초당 여러 번 호출해도 쿼리가 몰리지 않도록 같은 대상은
     * 캐시 주기 안에서 한 번만 조회한다.
     */
    fun requestPersonal(uuid: UUID, type: BadgeType, onReady: (RankEntry?) -> Unit = {}) {
        if (!settings.enabled) {
            onReady(null)
            return
        }
        if (!markRequested(uuid, type)) {
            onReady(cachedPersonal(uuid, type))
            return
        }
        Sched.async(plugin) {
            val entry = runCatching { plugin.storage.rankOf(type, uuid) }.getOrElse {
                plugin.logger.warning("개인 순위 조회 실패: ${it.message}")
                null
            }
            storePersonal(uuid, type, entry)
            Sched.global(plugin) { onReady(entry) }
        }
    }

    /** 마지막 개인 순위 조회 시각. 플레이스홀더발 쿼리 폭주를 막는다. */
    private val requestedAt = HashMap<String, Long>()

    @Synchronized
    private fun markRequested(uuid: UUID, type: BadgeType): Boolean {
        val key = "$uuid:${type.id}"
        val now = System.currentTimeMillis()
        val last = requestedAt[key] ?: 0L
        if (now - last < settings.cacheSeconds * 1000L) return false
        requestedAt[key] = now
        return true
    }

    @Synchronized
    private fun store(type: BadgeType, snapshot: Snapshot) {
        cache[type] = snapshot
    }

    @Synchronized
    private fun storePersonal(uuid: UUID, type: BadgeType, entry: RankEntry?) {
        val map = personal.getOrPut(uuid) { EnumMap(BadgeType::class.java) }
        if (entry == null) map.remove(type) else map[type] = entry
    }

    @Synchronized
    fun forget(uuid: UUID) {
        personal.remove(uuid)
        requestedAt.keys.removeIf { it.startsWith("$uuid:") }
    }

    @Synchronized
    fun invalidate() {
        cache.clear()
        personal.clear()
        requestedAt.clear()
    }
}
