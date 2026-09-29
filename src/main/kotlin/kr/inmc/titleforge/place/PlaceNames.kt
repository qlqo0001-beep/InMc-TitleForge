package kr.inmc.titleforge.place

import org.bukkit.configuration.file.YamlConfiguration

/**
 * 월드·생물군계를 보여줄 이름(`places.yml`). `%titleforge_world%` · `%titleforge_biome%` 가 쓴다.
 *
 * 값은 MiniMessage 원문이다. 조회는 TAB 같은 비동기 호출자에게서도 오므로, 고칠 때마다 맵을 통째로
 * 새로 만들어 바꿔 끼운다 — 읽는 쪽은 잠그지 않는다.
 *
 * **지운 이름은 빈 값(`''`)으로 남긴다.** 읽을 때마다 기본값에 새로 생긴 이름을 더하는데([load]),
 * 줄째 지우면 관리자가 일부러 지운 기본 이름이 다음 리로드에 되살아난다.
 *
 * **서버 없이 돈다.** YAML 을 읽고 쓰는 것까지 테스트한다.
 */
class PlaceNames {

    enum class Kind(val id: String, val display: String) {
        WORLD("worlds", "월드"),
        BIOME("biomes", "생물군계"),
    }

    @Volatile
    private var names: Map<Kind, Map<String, String>> = Kind.entries.associateWith { emptyMap() }

    /** 지운 열쇠. 파일에 `''` 로 남는다. */
    private var cleared: Map<Kind, Set<String>> = Kind.entries.associateWith { emptySet() }

    /** 파일 머리 주석. GUI 로 고쳐 다시 쓸 때도 남긴다. */
    private var header: List<String> = emptyList()

    /** 적힌 이름 전부(열쇠 → MiniMessage). 지운 것은 빠진다. */
    fun all(kind: Kind): Map<String, String> = names.getValue(kind)

    /** 월드 이름. 대소문자를 가리지 않는다 — 옛 스크립트(`world_name.js`)가 그랬다. */
    fun world(name: String): String? = all(Kind.WORLD)[name.lowercase()]

    /** 생물군계. 전체 id(`terralith:arid_highlands`)를 먼저, 없으면 네임스페이스를 뗀 이름. */
    fun biome(key: String): String? {
        val lower = key.lowercase()
        val biomes = all(Kind.BIOME)
        return biomes[lower] ?: biomes[lower.substringAfter(':')]
    }

    fun get(kind: Kind, id: String): String? = if (kind == Kind.WORLD) world(id) else biome(id)

    /**
     * 이 id 의 이름을 적을 열쇠. 이미 적힌(지운 것 포함) 열쇠가 있으면 그것 — 안 그러면 같은 것이 두 줄이 된다.
     * 새로 적는 생물군계는 네임스페이스를 뗀 이름에 적는다(기본값이 그 모양이다).
     */
    fun keyFor(kind: Kind, id: String): String {
        val lower = id.lowercase()
        if (kind == Kind.WORLD || lower in all(kind) || lower in cleared.getValue(kind)) return lower
        return lower.substringAfter(':')
    }

    /** 고친다. 비우면 지운다 — 원래 이름이 보이고, 기본 이름이어도 다시 더해지지 않는다. */
    fun set(kind: Kind, key: String, value: String?) {
        val lower = key.lowercase()
        val named = LinkedHashMap(all(kind))
        val gone = LinkedHashSet(cleared.getValue(kind))
        if (value.isNullOrBlank()) {
            named.remove(lower)
            gone += lower
        } else {
            named[lower] = value
            gone -= lower
        }
        cleared = cleared + (kind to gone)
        names = names + (kind to named)
    }

    /**
     * 파일을 읽고, [defaults](배포 기본값)에만 있는 이름을 더한다 — 업데이트로 늘어난 기본 이름이 이미 깔린
     * 서버에도 들어간다. 파일에 있는 것(고친 것·지운 것)은 건드리지 않는다.
     *
     * @return 더한 개수. 0 보다 크면 부르는 쪽이 파일을 다시 쓴다.
     */
    fun load(text: String, defaults: String? = null): Int {
        val config = yaml().apply { loadFromString(text) }
        val base = defaults?.let { yaml().apply { loadFromString(it) } }
        var added = 0
        val nextNames = HashMap<Kind, Map<String, String>>()
        val nextCleared = HashMap<Kind, Set<String>>()
        for (kind in Kind.entries) {
            val named = LinkedHashMap<String, String>()
            val gone = LinkedHashSet<String>()
            config.getConfigurationSection(kind.id)?.let { section ->
                for (key in section.getKeys(false)) {
                    val value = section.getString(key) ?: continue
                    if (value.isBlank()) gone += key.lowercase() else named[key.lowercase()] = value
                }
            }
            base?.getConfigurationSection(kind.id)?.let { section ->
                for (key in section.getKeys(false)) {
                    val lower = key.lowercase()
                    if (lower in named || lower in gone) continue
                    val value = section.getString(key)?.takeIf { it.isNotBlank() } ?: continue
                    named[lower] = value
                    added++
                }
            }
            nextNames[kind] = named
            nextCleared[kind] = gone
        }
        header = config.options().header
        cleared = nextCleared
        names = nextNames
        return added
    }

    fun save(): String {
        val config = yaml()
        config.options().setHeader(header)
        for (kind in Kind.entries) {
            val section = config.createSection(kind.id)
            for ((key, value) in all(kind)) section.set(key, value)
            for (key in cleared.getValue(kind)) section.set(key, "")
        }
        return config.saveToString()
    }

    private companion object {
        /**
         * 경로 구분자를 점에서 바꾼다. 월드 이름에 점이 들어가면 기본 구분자로는 그 줄이 하위 절로 쪼개져
         * **다음에 읽을 때 사라진다.** 생물군계 id 의 `:`·`/` 도 구분자가 되면 안 된다.
         */
        const val SEPARATOR = '|'

        fun yaml(): YamlConfiguration = YamlConfiguration().apply {
            options().pathSeparator(SEPARATOR)
            options().parseComments(true)
        }
    }
}
