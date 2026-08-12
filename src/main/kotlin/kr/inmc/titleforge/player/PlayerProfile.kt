package kr.inmc.titleforge.player

import kr.inmc.titleforge.badge.BadgeType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 장착 슬롯 3종. 서로 완전히 독립적이다 (맞춤 지침 7.4-15). */
enum class EquipSlot(val id: String, val display: String) {
    /** 능력치만 적용되는 칭호 슬롯. */
    STAT("stat", "능력치 칭호"),

    /** 이름 옆에 보이는 칭호 슬롯. */
    DISPLAY("show", "표시 칭호"),

    /** 인장 슬롯. 표시 전용. */
    SEAL("seal", "인장"),
    ;

    val type: BadgeType get() = if (this == SEAL) BadgeType.SEAL else BadgeType.TITLE

    companion object {
        fun of(raw: String): EquipSlot? = when (raw.lowercase()) {
            "stat", "능력치", "s" -> STAT
            "show", "display", "표시" -> DISPLAY
            "seal", "인장" -> SEAL
            else -> null
        }
    }
}

/**
 * 플레이어 상태 캐시.
 *
 * 조회는 전부 메모리에서 이뤄지므로 플레이스홀더가 초당 수십 회 호출돼도 안전하다.
 * 스텟 총합은 [ProfileManager.recalculate] 가 변경 시점에만 갱신한다 (맞춤 지침 7.2-7).
 *
 * 스텟 맵의 키는 스텟 id 문자열이다.
 */
class PlayerProfile(val uuid: UUID, name: String) {

    @Volatile
    var name: String = name

    private val ownedSets: Map<BadgeType, MutableSet<String>> = BadgeType.entries.associateWith {
        ConcurrentHashMap.newKeySet<String>()
    }

    /** "type:id" → 획득 시각 */
    val obtainedTimes: MutableMap<String, Long> = ConcurrentHashMap()

    /** "type:id" → 만료 시각(epoch ms). **0 이면 영구.** */
    val expiryTimes: MutableMap<String, Long> = ConcurrentHashMap()

    /** 가장 가까운 만료 시각. 없으면 [Long.MAX_VALUE]. 만료 검사를 싸게 만들기 위한 캐시. */
    @Volatile
    var nextExpiry: Long = Long.MAX_VALUE
        private set

    @Volatile
    var statTitle: String? = null

    @Volatile
    var displayTitle: String? = null

    @Volatile
    var seal: String? = null

    @Volatile
    var nickname: String? = null

    @Volatile
    var nicknameChangedAt: Long = 0L

    @Volatile
    var equipStats: Map<String, Double> = emptyMap()
        internal set

    @Volatile
    var ownStats: Map<String, Double> = emptyMap()
        internal set

    @Volatile
    var totalStats: Map<String, Double> = emptyMap()
        internal set

    private val dirty = AtomicBoolean(false)

    fun owned(type: BadgeType): MutableSet<String> = ownedSets.getValue(type)

    fun has(type: BadgeType, id: String): Boolean = ownedSets.getValue(type).contains(id)

    fun count(type: BadgeType): Int = ownedSets.getValue(type).size

    fun equipped(slot: EquipSlot): String? = when (slot) {
        EquipSlot.STAT -> statTitle
        EquipSlot.DISPLAY -> displayTitle
        EquipSlot.SEAL -> seal
    }

    fun setEquipped(slot: EquipSlot, id: String?) {
        when (slot) {
            EquipSlot.STAT -> statTitle = id
            EquipSlot.DISPLAY -> displayTitle = id
            EquipSlot.SEAL -> seal = id
        }
        markDirty()
    }

    /**
     * @param expiresAt 만료 시각(epoch ms). 0 이면 영구.
     */
    fun grant(
        type: BadgeType,
        id: String,
        timestamp: Long = System.currentTimeMillis(),
        expiresAt: Long = PERMANENT,
    ): Boolean {
        val key = key(type, id)
        val added = ownedSets.getValue(type).add(id)
        obtainedTimes.putIfAbsent(key, timestamp)
        setExpiry(type, id, expiresAt)
        if (added) markDirty()
        return added
    }

    fun revoke(type: BadgeType, id: String): Boolean {
        val removed = ownedSets.getValue(type).remove(id)
        if (removed) {
            val key = key(type, id)
            obtainedTimes.remove(key)
            expiryTimes.remove(key)
            if (type == BadgeType.SEAL && seal == id) setEquipped(EquipSlot.SEAL, null)
            if (type == BadgeType.TITLE) {
                if (statTitle == id) setEquipped(EquipSlot.STAT, null)
                if (displayTitle == id) setEquipped(EquipSlot.DISPLAY, null)
            }
            recomputeNextExpiry()
            markDirty()
        }
        return removed
    }

    fun obtainedAt(type: BadgeType, id: String): Long = obtainedTimes[key(type, id)] ?: 0L

    /** 만료 시각. 0 이면 영구. */
    fun expiryOf(type: BadgeType, id: String): Long = expiryTimes[key(type, id)] ?: PERMANENT

    fun setExpiry(type: BadgeType, id: String, expiresAt: Long) {
        val key = key(type, id)
        if (expiresAt <= PERMANENT) expiryTimes.remove(key) else expiryTimes[key] = expiresAt
        recomputeNextExpiry()
        markDirty()
    }

    /** 남은 시간(초). 영구면 null, 이미 지났으면 0. */
    fun remainingSeconds(type: BadgeType, id: String, now: Long = System.currentTimeMillis()): Long? {
        val expiry = expiryOf(type, id)
        if (expiry <= PERMANENT) return null
        return ((expiry - now) / 1000L).coerceAtLeast(0L)
    }

    /** 지금 시점에 만료된 항목들. (type, id) 목록. */
    fun expired(now: Long = System.currentTimeMillis()): List<Pair<BadgeType, String>> {
        if (now < nextExpiry) return emptyList()
        val result = ArrayList<Pair<BadgeType, String>>()
        for ((key, expiry) in expiryTimes) {
            if (expiry <= PERMANENT || expiry > now) continue
            val separator = key.indexOf(':')
            if (separator <= 0) continue
            val type = BadgeType.of(key.substring(0, separator)) ?: continue
            result += type to key.substring(separator + 1)
        }
        return result
    }

    private fun recomputeNextExpiry() {
        nextExpiry = expiryTimes.values.filter { it > PERMANENT }.minOrNull() ?: Long.MAX_VALUE
    }

    /** 저장소에서 불러온 뒤 한 번 호출해 만료 캐시를 맞춘다. */
    fun refreshExpiryCache() = recomputeNextExpiry()

    fun stat(id: String): Double = totalStats[id] ?: 0.0

    fun markDirty() {
        dirty.set(true)
    }

    fun consumeDirty(): Boolean = dirty.compareAndSet(true, false)

    val isDirty: Boolean get() = dirty.get()

    private fun key(type: BadgeType, id: String): String = "${type.id}:$id"

    companion object {
        /** 만료 없음. */
        const val PERMANENT = 0L
    }
}
