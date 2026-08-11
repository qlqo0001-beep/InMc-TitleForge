package kr.inmc.titleforge.player

import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.StatType
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
 */
class PlayerProfile(val uuid: UUID, name: String) {

    @Volatile
    var name: String = name

    private val ownedSets: Map<BadgeType, MutableSet<String>> = BadgeType.entries.associateWith {
        ConcurrentHashMap.newKeySet<String>()
    }

    /** "type:id" → 획득 시각 */
    val obtainedTimes: MutableMap<String, Long> = ConcurrentHashMap()

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
    var equipStats: Map<StatType, Double> = emptyMap()
        internal set

    @Volatile
    var ownStats: Map<StatType, Double> = emptyMap()
        internal set

    @Volatile
    var totalStats: Map<StatType, Double> = emptyMap()
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

    fun grant(type: BadgeType, id: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val added = ownedSets.getValue(type).add(id)
        if (added) {
            obtainedTimes["${type.id}:$id"] = timestamp
            markDirty()
        }
        return added
    }

    fun revoke(type: BadgeType, id: String): Boolean {
        val removed = ownedSets.getValue(type).remove(id)
        if (removed) {
            obtainedTimes.remove("${type.id}:$id")
            if (equipped(EquipSlot.SEAL) == id && type == BadgeType.SEAL) setEquipped(EquipSlot.SEAL, null)
            if (type == BadgeType.TITLE) {
                if (statTitle == id) setEquipped(EquipSlot.STAT, null)
                if (displayTitle == id) setEquipped(EquipSlot.DISPLAY, null)
            }
            markDirty()
        }
        return removed
    }

    fun obtainedAt(type: BadgeType, id: String): Long = obtainedTimes["${type.id}:$id"] ?: 0L

    fun stat(stat: StatType): Double = totalStats[stat] ?: 0.0

    fun markDirty() {
        dirty.set(true)
    }

    fun consumeDirty(): Boolean = dirty.compareAndSet(true, false)

    val isDirty: Boolean get() = dirty.get()
}
