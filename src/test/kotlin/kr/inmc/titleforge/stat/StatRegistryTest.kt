package kr.inmc.titleforge.stat

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 스텟 레지스트리 검증.
 *
 * 핵심 계약: **바닐라 스텟은 설정과 무관하게 항상 살아 있어야 한다.**
 * MMOItems 를 쓰지 않는 서버도 이것만으로 운영이 가능해야 하기 때문이다.
 */
class StatRegistryTest {

    private fun registry() = StatRegistry(Logger.getLogger("test"))

    @Test
    fun `설정이 없어도 바닐라 스텟이 모두 존재한다`() {
        val registry = registry()
        val vanilla = registry.ofKind(StatKind.VANILLA)
        assertTrue(vanilla.isNotEmpty(), "바닐라 기본 스텟이 비어 있습니다")
        for (expected in listOf(
            "max_health", "attack_damage", "attack_speed", "armor", "armor_toughness",
            "knockback_resistance", "movement_speed", "max_absorption", "luck",
        )) {
            assertNotNull(registry.of(expected), "$expected 이(가) 없습니다")
        }
    }

    @Test
    fun `바닐라 스텟은 전부 attribute 키를 갖는다`() {
        for (stat in registry().ofKind(StatKind.VANILLA)) {
            assertNotNull(stat.attributeKey, "${stat.id} 에 attribute 키가 없습니다")
        }
    }

    @Test
    fun `모든 기본 스텟에 설명이 있다`() {
        for (stat in registry().all()) {
            assertTrue(stat.description.isNotEmpty(), "${stat.id} 에 설명이 없습니다")
        }
    }

    @Test
    fun `id 가 중복되지 않고 규칙에 맞는다`() {
        val ids = registry().all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "중복 id: $ids")
        assertTrue(ids.all { Stat.validId(it) }, "잘못된 id 가 있습니다: $ids")
    }

    @Test
    fun `분류별 조회가 전체와 일치한다`() {
        val registry = registry()
        val byCategory = StatCategory.entries.flatMap { registry.byCategory(it) }
        assertEquals(registry.all().toSet(), byCategory.toSet())
        assertEquals(registry.all().size, byCategory.size)
    }

    @Test
    fun `대소문자와 무관하게 조회된다`() {
        val registry = registry()
        assertNotNull(registry.of("MAX_HEALTH"))
        assertNotNull(registry.of("max_health"))
        assertNull(registry.of("없는스텟"))
        assertNull(registry.of(null))
    }

    @Test
    fun `동일성은 id 로만 판단한다`() {
        val first = StatRegistry.defaults().first { it.id == "armor" }
        val second = Stat(
            id = "armor",
            display = "다른 이름",
            description = emptyList(),
            category = StatCategory.COMBAT,
            icon = first.icon,
            kind = StatKind.VIRTUAL,
        )
        // 설정을 다시 읽어 인스턴스가 바뀌어도 기존 참조가 살아 있어야 한다.
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `표시 단위 변환이 왕복한다`() {
        val speed = registry().of("movement_speed")!!
        assertEquals(0.1, speed.toInternal(10.0))
        assertEquals(10.0, speed.toDisplay(0.1))
        assertEquals("+10%", speed.format(0.1))
    }

    @Test
    fun `권장 범위 판정이 동작한다`() {
        val health = registry().of("max_health")!!
        assertTrue(health.withinSoftRange(20.0))
        assertTrue(!health.withinSoftRange(500.0))
    }
}
