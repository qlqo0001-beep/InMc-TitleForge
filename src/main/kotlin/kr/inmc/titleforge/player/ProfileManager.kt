package kr.inmc.titleforge.player

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.Stats
import kr.inmc.titleforge.util.Sched
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 프로필 캐시 + 비동기 영속화.
 *
 * - 로드는 [org.bukkit.event.player.AsyncPlayerPreLoginEvent](이미 비동기)에서 수행 → 접속 시 틱 지연 0
 * - 조회는 전부 메모리
 * - 저장은 dirty 플래그 기반 배치 (맞춤 지침 7.2-6)
 */
class ProfileManager(private val plugin: TitleForgePlugin) {

    private val cache = ConcurrentHashMap<UUID, PlayerProfile>()

    /** 퇴장 후 캐시 유지 만료 시각 (밀리초). 재접속하면 취소된다. */
    private val expiry = ConcurrentHashMap<UUID, Long>()

    fun cached(uuid: UUID): PlayerProfile? = cache[uuid]

    fun of(player: Player): PlayerProfile? = cache[player.uniqueId]

    fun cachedProfiles(): Collection<PlayerProfile> = cache.values

    // ── 수명 주기 ──────────────────────────────────────────────────────

    /** AsyncPlayerPreLoginEvent 에서 호출. 이미 비동기 스레드이므로 그대로 블로킹 조회한다. */
    fun loadOnPreLogin(uuid: UUID, name: String) {
        expiry.remove(uuid)
        val existing = cache[uuid]
        if (existing != null) {
            existing.name = name
            recalculate(existing)
            return
        }
        val profile = runCatching { plugin.storage.loadProfile(uuid, name) }
            .getOrElse {
                plugin.logger.severe("프로필 로드 실패 ($name): ${it.message}")
                PlayerProfile(uuid, name)
            }
        // 오프라인 동안 만료된 항목을 먼저 정리한 뒤 스텟을 계산한다.
        val expired = profile.expired()
        if (expired.isNotEmpty()) {
            expired.forEach { (type, id) -> profile.revoke(type, id) }
            profile.refreshExpiryCache()
        }
        recalculate(profile)
        cache[uuid] = profile
    }

    fun handleQuit(player: Player) {
        val profile = cache[player.uniqueId] ?: return
        save(profile)
        val keep = plugin.settings.storage.cacheKeepSeconds
        if (keep <= 0) {
            cache.remove(player.uniqueId)
        } else {
            expiry[player.uniqueId] = System.currentTimeMillis() + keep * 1000L
        }
    }

    /** 만료된 오프라인 캐시 정리. 자동 저장 태스크에서 함께 호출된다. */
    fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = expiry.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, deadline) = iterator.next()
            if (now < deadline) continue
            if (Bukkit.getPlayer(uuid) != null) {
                iterator.remove()
                continue
            }
            val profile = cache[uuid]
            if (profile != null && profile.isDirty) continue
            cache.remove(uuid)
            iterator.remove()
        }
    }

    // ── 저장 ───────────────────────────────────────────────────────────

    /** 변경분을 비동기로 저장한다. */
    fun save(profile: PlayerProfile) {
        if (!profile.consumeDirty()) return
        Sched.async(plugin) {
            runCatching { plugin.storage.saveProfile(profile) }
                .onFailure {
                    profile.markDirty()
                    plugin.logger.severe("프로필 저장 실패 (${profile.name}): ${it.message}")
                }
        }
    }

    fun saveAllDirty() {
        val dirty = cache.values.filter { it.consumeDirty() }
        if (dirty.isEmpty()) return
        Sched.async(plugin) {
            runCatching { plugin.storage.saveProfiles(dirty) }
                .onFailure { error ->
                    dirty.forEach { it.markDirty() }
                    plugin.logger.severe("일괄 저장 실패: ${error.message}")
                }
        }
    }

    /** 서버 종료 시 동기 저장 (이 시점에는 스케줄러가 더 이상 동작하지 않는다). */
    fun flushBlocking() {
        val dirty = cache.values.filter { it.consumeDirty() }
        if (dirty.isEmpty()) return
        runCatching { plugin.storage.saveProfiles(dirty) }
            .onFailure { plugin.logger.severe("종료 저장 실패: ${it.message}") }
    }

    // ── 오프라인 포함 조회 ────────────────────────────────────────────

    /**
     * 이름으로 프로필을 찾는다. **비동기 컨텍스트 전용.**
     * 온라인/캐시에 있으면 캐시를, 없으면 DB 에서 임시 로드한다(캐시에 넣지 않음).
     */
    fun resolveBlocking(name: String): PlayerProfile? {
        Bukkit.getPlayerExact(name)?.let { online -> cache[online.uniqueId]?.let { return it } }
        cache.values.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val uuid = runCatching { plugin.storage.findUuidByName(name) }.getOrNull() ?: return null
        cache[uuid]?.let { return it }
        return runCatching { plugin.storage.loadProfile(uuid, name) }.getOrNull()
    }

    /** [resolveBlocking] 로 얻은 프로필이 캐시에 없으면 즉시 저장해야 한다. */
    fun isCached(profile: PlayerProfile): Boolean = cache[profile.uuid] === profile

    fun persist(profile: PlayerProfile) {
        if (!profile.consumeDirty()) return
        runCatching { plugin.storage.saveProfile(profile) }
            .onFailure {
                profile.markDirty()
                plugin.logger.severe("프로필 저장 실패 (${profile.name}): ${it.message}")
            }
    }

    // ── 스텟 계산 ──────────────────────────────────────────────────────

    /**
     * 보유 스텟 + 마일스톤 + 장착 스텟을 다시 계산한다.
     * **변경 시점에만** 호출한다. 조회 경로에서는 절대 호출하지 않는다 (맞춤 지침 7.2-7).
     */
    fun recalculate(profile: PlayerProfile) {
        val registry = plugin.badges
        val ownTotals = LinkedHashMap<String, Double>()

        for (id in profile.owned(BadgeType.TITLE)) {
            val badge: Badge = registry.get(BadgeType.TITLE, id) ?: continue
            if (badge.ownStats.isNotEmpty()) Stats.mergeInto(ownTotals, badge.ownStats)
        }

        val ownedCount = profile.count(BadgeType.TITLE)
        for ((threshold, stats) in plugin.settings.title.milestones) {
            if (ownedCount >= threshold) Stats.mergeInto(ownTotals, stats)
        }

        val equipped = profile.statTitle?.let { registry.get(BadgeType.TITLE, it) }
        val equipStats = equipped?.equipStats ?: emptyMap()

        profile.ownStats = ownTotals
        profile.equipStats = equipStats
        profile.totalStats = Stats.merge(ownTotals, equipStats)
    }

    /** 스텟 재계산 후 온라인이면 Attribute 까지 반영한다. */
    fun refreshStats(profile: PlayerProfile) {
        recalculate(profile)
        val player = Bukkit.getPlayer(profile.uuid) ?: return
        Sched.entity(plugin, player) {
            plugin.statApplier.apply(player, profile.totalStats)
        }
    }

    /** 칭호 스텟이 바뀌었을 때 온라인 전원 재계산 + 표시 갱신. */
    fun refreshAllOnline() {
        for (player in Bukkit.getOnlinePlayers()) {
            val profile = cache[player.uniqueId] ?: continue
            refreshStats(profile)
            plugin.nameDisplay.refresh(player)
        }
    }

    /** 이름·아이콘 등 표시 요소만 바뀐 경우. 스텟 재계산을 건너뛴다. */
    fun refreshDisplayOnline() {
        for (player in Bukkit.getOnlinePlayers()) {
            plugin.nameDisplay.refresh(player)
        }
    }

    // ── 보유 기한 ──────────────────────────────────────────────────────

    /**
     * 캐시된 프로필의 만료 항목을 회수한다.
     *
     * [PlayerProfile.nextExpiry] 캐시 덕분에 만료 예정이 없는 프로필은 비교 한 번으로 끝난다.
     * 인원이 늘어도 비용이 늘지 않는다.
     *
     * @return 회수가 일어난 프로필 수
     */
    fun sweepExpired(): Int {
        val now = System.currentTimeMillis()
        var touched = 0
        for (profile in cache.values) {
            if (now < profile.nextExpiry) continue
            val expired = profile.expired(now)
            if (expired.isEmpty()) {
                profile.refreshExpiryCache()
                continue
            }
            touched++
            handleExpired(profile, expired)
        }
        return touched
    }

    /** 프로필 1개의 만료 항목을 즉시 정리한다. 로그인 직후에도 쓴다. */
    fun sweepExpired(profile: PlayerProfile) {
        val expired = profile.expired()
        if (expired.isNotEmpty()) handleExpired(profile, expired)
    }

    private fun handleExpired(profile: PlayerProfile, expired: List<Pair<BadgeType, String>>) {
        val player = Bukkit.getPlayer(profile.uuid)
        for ((type, id) in expired) {
            profile.revoke(type, id)
            if (player == null) continue
            val badge = plugin.badges.get(type, id)
            plugin.messages.send(
                player,
                "badge.expired",
                "type" to type.display,
                "name" to (badge?.nameComponent ?: net.kyori.adventure.text.Component.text(id)),
            )
        }
        profile.refreshExpiryCache()
        refreshStats(profile)
        if (player != null) plugin.nameDisplay.refresh(player)
        save(profile)
    }
}
