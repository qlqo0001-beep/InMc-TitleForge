package kr.inmc.titleforge.stat

import java.util.EnumMap

/** 스텟 맵 관련 유틸. 직렬화 형식: `max_health=2.0;attack_damage=1.5` */
object Stats {

    val EMPTY: Map<StatType, Double> = emptyMap()

    fun serialize(stats: Map<StatType, Double>): String =
        stats.entries
            .filter { it.value != 0.0 }
            .joinToString(";") { "${it.key.id}=${it.value}" }

    fun deserialize(raw: String?): Map<StatType, Double> {
        if (raw.isNullOrBlank()) return EMPTY
        val result = EnumMap<StatType, Double>(StatType::class.java)
        for (token in raw.split(';')) {
            if (token.isBlank()) continue
            val index = token.indexOf('=')
            if (index <= 0) continue
            val stat = StatType.of(token.substring(0, index).trim()) ?: continue
            val value = token.substring(index + 1).trim().toDoubleOrNull() ?: continue
            if (value != 0.0) result[stat] = value
        }
        return result
    }

    fun merge(vararg maps: Map<StatType, Double>): Map<StatType, Double> {
        val result = EnumMap<StatType, Double>(StatType::class.java)
        for (map in maps) {
            for ((stat, value) in map) {
                val merged = (result[stat] ?: 0.0) + value
                if (merged == 0.0) result.remove(stat) else result[stat] = merged
            }
        }
        return result
    }

    fun mergeInto(target: EnumMap<StatType, Double>, source: Map<StatType, Double>) {
        for ((stat, value) in source) {
            val merged = (target[stat] ?: 0.0) + value
            if (merged == 0.0) target.remove(stat) else target[stat] = merged
        }
    }

    fun with(base: Map<StatType, Double>, stat: StatType, value: Double): Map<StatType, Double> {
        val result = EnumMap<StatType, Double>(StatType::class.java)
        result.putAll(base)
        if (value == 0.0) result.remove(stat) else result[stat] = value
        return result
    }
}
