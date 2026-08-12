package kr.inmc.titleforge.storage

import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import java.util.UUID

/**
 * 저장소 계약. **모든 구현은 블로킹**이며 호출자는 반드시 비동기 컨텍스트여야 한다
 * (맞춤 지침 7.1-1). 유일한 예외는 서버 기동 시 [init] 이다.
 */
interface Storage {

    fun init()

    fun close()

    fun loadBadges(): List<Badge>

    fun saveBadge(badge: Badge)

    /** 여러 개를 한 트랜잭션으로 저장. 연타 편집을 배치로 묶을 때 쓴다. */
    fun saveBadges(badges: Collection<Badge>)

    /**
     * 정의를 지우고, 그 칭호/인장을 참조하는 보유 기록·장착 슬롯까지 **한 트랜잭션에서**
     * 함께 정리한다. 나눠서 실행하면 중간에 실패했을 때 이미 지운 정의를 여전히
     * 누군가 "보유"하거나 "장착"한 것처럼 DB 에 남을 수 있다.
     */
    fun deleteBadge(type: BadgeType, id: String)

    /**
     * ID 를 바꾼다. 정의·보유 기록·장착 슬롯을 한 트랜잭션에서 전부 옮긴다.
     * 호출 전 [oldId] 가 존재하고 [newId] 가 비어 있음을 호출자가 보장해야 한다.
     */
    fun renameBadge(type: BadgeType, oldId: String, newId: String)

    fun loadProfile(uuid: UUID, name: String): PlayerProfile

    fun saveProfile(profile: PlayerProfile)

    fun saveProfiles(profiles: Collection<PlayerProfile>)

    fun findUuidByName(name: String): UUID?

    fun isNicknameTaken(nickname: String, except: UUID?): Boolean

    /** 저장된 모든 플레이어에게 지급. 지급된 행 수를 반환. */
    fun grantToAll(type: BadgeType, id: String, expiresAt: Long): Int

    /** 닉네임 변경 쿨타임을 초기화한다(오프라인 포함). */
    fun resetNicknameCooldown(uuid: UUID)

    /** 전체 플레이어의 닉네임 변경 쿨타임을 초기화한다. 대상 행 수를 반환. */
    fun resetNicknameCooldownAll(): Int

    /** 만료되지 않은 보유 개수 기준 상위 목록. */
    fun topCollectors(type: BadgeType, limit: Int): List<RankEntry>

    /** 특정 플레이어의 순위(1부터). 보유가 없으면 null. */
    fun rankOf(type: BadgeType, uuid: UUID): RankEntry?

    /** 해당 분류에서 1개 이상 보유한 인원 수. */
    fun collectorCount(type: BadgeType): Int
}

/** 순위 1줄. */
data class RankEntry(
    val uuid: UUID,
    val name: String,
    val count: Int,
    val rank: Int,
)
