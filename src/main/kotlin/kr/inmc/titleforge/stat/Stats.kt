package kr.inmc.titleforge.stat

/**
 * 스텟 맵 유틸.
 *
 * 맵의 키는 **스텟 id 문자열**이다. 레지스트리 인스턴스가 아니라 id 로 들고 있으므로
 * `stats.yml` 을 다시 읽어도, 잠깐 정의가 빠져 있어도 저장된 값이 사라지지 않는다.
 *
 * 직렬화 형식: `max_health=2.0;attack_damage=1.5`
 */
object Stats {

    val EMPTY: Map<String, Double> = emptyMap()

    fun serialize(stats: Map<String, Double>): String =
        stats.entries
            .filter { it.value != 0.0 }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }

    /**
     * 알 수 없는 id 도 그대로 보존한다. 설정 실수로 스텟 정의가 잠깐 빠졌을 때
     * 저장된 값을 지워버리지 않기 위해서다.
     */
    fun deserialize(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return EMPTY
        val result = LinkedHashMap<String, Double>()
        for (token in raw.split(';')) {
            if (token.isBlank()) continue
            val index = token.indexOf('=')
            if (index <= 0) continue
            val id = token.substring(0, index).trim().lowercase()
            if (id.isEmpty()) continue
            val value = token.substring(index + 1).trim().toDoubleOrNull() ?: continue
            if (value != 0.0) result[id] = value
        }
        return result
    }

    fun merge(vararg maps: Map<String, Double>): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        for (map in maps) mergeInto(result, map)
        return result
    }

    fun mergeInto(target: MutableMap<String, Double>, source: Map<String, Double>) {
        for ((id, value) in source) {
            val merged = (target[id] ?: 0.0) + value
            if (merged == 0.0) target.remove(id) else target[id] = merged
        }
    }

    fun with(base: Map<String, Double>, id: String, value: Double): Map<String, Double> {
        val result = LinkedHashMap(base)
        if (value == 0.0) result.remove(id) else result[id] = value
        return result
    }

    fun with(base: Map<String, Double>, stat: Stat, value: Double): Map<String, Double> =
        with(base, stat.id, value)

    fun valueOf(stats: Map<String, Double>, stat: Stat): Double = stats[stat.id] ?: 0.0
}
