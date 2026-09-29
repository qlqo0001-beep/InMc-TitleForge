package kr.inmc.titleforge.place

import kr.inmc.titleforge.place.PlaceNames.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 월드·생물군계 이름(`places.yml`). 옛 PlaceholderAPI 스크립트(`world_name.js` · `biome.js`)를 대신한다. */
class PlaceNamesTest {

    private fun resource(): String =
        javaClass.classLoader.getResourceAsStream("places.yml")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun defaults(): PlaceNames = PlaceNames().apply { load(resource()) }

    @Test
    fun `월드는 대소문자를 가리지 않는다`() {
        val names = defaults()
        assertEquals(names.world("world_nether"), names.world("World_Nether"))
        assertTrue(names.world("world_nether")!!.contains("지옥"))
        assertNull(names.world("없는_월드"))
    }

    @Test
    fun `생물군계는 전체 id 로도 네임스페이스를 뗀 이름으로도 찾는다`() {
        val names = defaults()
        // 기본값은 네임스페이스 없이 적혀 있어 어느 데이터팩의 것이든 맞는다(옛 스크립트와 같다).
        assertTrue(names.biome("minecraft:plains")!!.contains("평원"))
        assertTrue(names.biome("terralith:arid_highlands")!!.contains("건조한 고원"))
        assertTrue(names.biome("terralith:cave/andesite_caves")!!.contains("안산암 동굴"))
        assertNull(names.biome("minecraft:없는_곳"))
    }

    @Test
    fun `전체 id 로 적은 것이 네임스페이스 없는 것보다 먼저다`() {
        val names = PlaceNames()
        names.set(Kind.BIOME, "plains", "평원")
        names.set(Kind.BIOME, "terralith:plains", "테랄리스 평원")
        assertEquals("테랄리스 평원", names.biome("terralith:plains"))
        assertEquals("평원", names.biome("minecraft:plains"))
    }

    @Test
    fun `이미 적힌 열쇠를 고친다 — 같은 것이 두 줄이 되지 않는다`() {
        val names = defaults()
        assertEquals("plains", names.keyFor(Kind.BIOME, "minecraft:plains"))
        names.set(Kind.BIOME, "terralith:plains", "x")
        assertEquals("terralith:plains", names.keyFor(Kind.BIOME, "terralith:plains"))
        assertEquals("newbi_world", names.keyFor(Kind.WORLD, "Newbi_World"))
    }

    @Test
    fun `비우면 지워지고 원래 이름이 보인다`() {
        val names = defaults()
        names.set(Kind.WORLD, "world", "")
        assertNull(names.world("world"))
    }

    @Test
    fun `점·콜론·빗금이 든 이름도 저장했다 읽으면 그대로다`() {
        // 기본 경로 구분자(점)로는 "my.world" 가 my 아래 world 로 쪼개져 다음에 읽을 때 사라진다.
        val names = defaults()
        names.set(Kind.WORLD, "my.world", "<red>점 월드")
        names.set(Kind.BIOME, "terralith:cave/crystal_caves", "<aqua>수정")
        val again = PlaceNames().apply { load(names.save()) }
        assertEquals("<red>점 월드", again.world("my.world"))
        assertEquals("<aqua>수정", again.biome("terralith:cave/crystal_caves"))
        assertEquals(names.all(Kind.BIOME), again.all(Kind.BIOME))
        assertEquals(names.all(Kind.WORLD), again.all(Kind.WORLD))
    }

    @Test
    fun `다시 써도 머리 주석이 남는다`() {
        val saved = defaults().save()
        assertTrue(saved.contains("%titleforge_biome%"), "GUI 로 한 번 고치면 파일 설명이 사라진다")
    }

    @Test
    fun `기본값은 옛 스크립트의 월드 7개와 생물군계 185개에 빠졌던 4개를 더한 것이다`() {
        val names = defaults()
        assertEquals(7, names.all(Kind.WORLD).size)
        assertEquals(189, names.all(Kind.BIOME).size)
    }

    @Test
    fun `운영 서버 데이터팩에서 옛 스크립트가 빠뜨린 생물군계도 이름이 있다`() {
        // Terralith 2.6.2 · 바닐라 26.2 기준. 옛 biome.js 에는 없어서 id 가 그대로 찍혔다.
        val names = defaults()
        for (key in listOf("terralith:alpha_islands", "terralith:alpha_islands_winter", "terralith:deep_warm_ocean", "minecraft:sulfur_caves")) {
            assertTrue(names.biome(key) != null, "$key 의 이름이 없습니다")
        }
    }

    @Test
    fun `바닐라 생물군계는 전부 이름이 있다`() {
        // 클래스를 초기화하지 않고 상수 이름만 읽는다(초기화하면 서버의 레지스트리를 찾는다).
        // paper-api 를 올리면 새 바닐라 생물군계가 여기서 걸린다.
        val biome = Class.forName("org.bukkit.block.Biome", false, javaClass.classLoader)
        val vanilla = biome.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == biome && it.name != "CUSTOM" }
            .map { "minecraft:" + it.name.lowercase() }
        assertTrue(vanilla.size > 50)
        val names = defaults()
        assertEquals(emptyList(), vanilla.filter { names.biome(it) == null })
    }

    @Test
    fun `기본값에 새로 생긴 이름은 이미 깔린 파일에도 읽을 때 더해진다`() {
        val base = resource()
        val old = listOf("worlds:", "  world: '<red>내가 고친 이름'", "biomes:", "  plains: '평원'").joinToString(System.lineSeparator())
        val names = PlaceNames()
        val added = names.load(old, base)
        assertEquals(7 - 1 + 189 - 1, added)
        assertEquals("<red>내가 고친 이름", names.world("world"), "고친 이름을 기본값으로 덮으면 안 된다")
        assertTrue(names.biome("terralith:alpha_islands") != null)
        assertEquals(0, PlaceNames().load(names.save(), base), "한 번 더한 뒤에는 더할 것이 없다")
    }

    @Test
    fun `지운 기본 이름은 다시 읽어도 되살아나지 않는다`() {
        val names = defaults()
        names.set(Kind.BIOME, "plains", null)
        val again = PlaceNames()
        assertEquals(0, again.load(names.save(), resource()))
        assertNull(again.biome("minecraft:plains"))
        again.set(Kind.BIOME, again.keyFor(Kind.BIOME, "minecraft:plains"), "다시 평원")
        assertEquals("다시 평원", again.biome("minecraft:plains"))
    }

    @Test
    fun `기본값의 그라데이션 색이 전부 여섯 자리다`() {
        // 옛 biome.js 의 산악 스텝이 #AABAA(다섯 자리)라 그 이름만 태그가 글자로 찍혔다.
        val color = Regex("#([0-9A-Fa-f]+)")
        for (kind in Kind.entries) {
            for ((id, value) in defaults().all(kind)) {
                for (match in color.findAll(value)) {
                    assertEquals(6, match.groupValues[1].length, "$id 의 색 ${match.value}")
                }
            }
        }
    }
}
