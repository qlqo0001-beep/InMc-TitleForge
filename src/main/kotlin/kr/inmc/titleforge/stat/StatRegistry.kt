package kr.inmc.titleforge.stat

import org.bukkit.Material
import org.bukkit.attribute.AttributeModifier
import org.bukkit.configuration.ConfigurationSection
import java.util.EnumMap
import java.util.logging.Logger

/**
 * 스텟 레지스트리.
 *
 * - **바닐라 스텟은 코드에 내장**되어 있어 설정 파일이 비어 있어도, MMOItems 가 없어도 항상 동작한다.
 * - MMOItems 스텟은 `stats.yml` 에서 정의한다. 같은 파일에서 바닐라 스텟의 설명·아이콘·권장 범위도
 *   덮어쓸 수 있다.
 *
 * 조회는 전부 메모리이며 리로드 시 통째로 교체된다.
 */
class StatRegistry(private val logger: Logger) {

    @Volatile
    private var stats: Map<String, Stat> = defaults().associateBy { it.id }

    @Volatile
    private var ordered: List<Stat> = sortStats(stats.values)

    @Volatile
    private var byCategory: Map<StatCategory, List<Stat>> = groupByCategory(ordered)

    fun all(): List<Stat> = ordered

    fun of(id: String?): Stat? = id?.let { stats[it.lowercase()] }

    fun exists(id: String): Boolean = stats.containsKey(id.lowercase())

    fun byCategory(category: StatCategory): List<Stat> = byCategory[category] ?: emptyList()

    fun ofKind(kind: StatKind): List<Stat> = ordered.filter { it.kind == kind }

    fun ids(): Collection<String> = stats.keys

    fun size(): Int = stats.size

    /**
     * `stats.yml` 을 반영해 레지스트리를 다시 만든다.
     *
     * 바닐라 기본 정의를 먼저 깔고 그 위에 설정을 덮어쓰므로, 설정 파일이 잘못돼도
     * 바닐라 스텟은 살아남는다.
     */
    fun reload(section: ConfigurationSection?) {
        val result = LinkedHashMap<String, Stat>()
        defaults().forEach { result[it.id] = it }

        if (section != null) {
            for (key in section.getKeys(false)) {
                val id = key.lowercase()
                if (!Stat.validId(id)) {
                    logger.warning("[스텟] 잘못된 id 라 건너뜁니다: $key (소문자 영문/숫자/밑줄 1~48자)")
                    continue
                }
                val node = section.getConfigurationSection(key) ?: continue
                val parsed = parse(id, node, result[id])
                if (parsed != null) result[id] = parsed
            }
        }

        stats = result
        ordered = sortStats(result.values)
        byCategory = groupByCategory(ordered)

        val vanilla = ordered.count { it.kind == StatKind.VANILLA }
        val mmo = ordered.count { it.kind == StatKind.MMO }
        val virtual = ordered.count { it.kind == StatKind.VIRTUAL }
        logger.info("스텟 ${ordered.size}개 로드 (바닐라 $vanilla · MMOItems $mmo · 가상 $virtual)")
    }

    private fun parse(id: String, node: ConfigurationSection, base: Stat?): Stat? {
        val kind = StatKind.of(node.getString("kind")) ?: base?.kind ?: StatKind.VIRTUAL
        val category = StatCategory.of(node.getString("category")) ?: base?.category ?: StatCategory.UTILITY

        val attributeKey = node.getString("attribute") ?: base?.attributeKey
        if (kind == StatKind.VANILLA && attributeKey.isNullOrBlank()) {
            logger.warning("[스텟] $id: kind 가 vanilla 인데 attribute 가 없습니다. 건너뜁니다.")
            return null
        }

        val mmoStat = node.getString("mmo-stat") ?: base?.mmoStat
        if (kind == StatKind.MMO && mmoStat.isNullOrBlank()) {
            logger.warning("[스텟] $id: kind 가 mmo 인데 mmo-stat 이 없습니다. 건너뜁니다.")
            return null
        }

        val operation = node.getString("operation")?.let { raw ->
            runCatching { AttributeModifier.Operation.valueOf(raw.uppercase()) }.getOrElse {
                logger.warning("[스텟] $id: 알 수 없는 operation '$raw' — 기본값을 씁니다.")
                null
            }
        } ?: base?.operation ?: AttributeModifier.Operation.ADD_NUMBER

        val icon = node.getString("icon")?.let { Material.matchMaterial(it.uppercase()) }
            ?: base?.icon
            ?: Material.PAPER

        val description = when {
            node.isList("description") -> node.getStringList("description")
            node.isString("description") -> listOf(node.getString("description")!!)
            else -> base?.description ?: emptyList()
        }

        return Stat(
            id = id,
            display = node.getString("display") ?: base?.display ?: id,
            description = description,
            category = category,
            icon = icon,
            kind = kind,
            attributeKey = attributeKey,
            mmoStat = mmoStat,
            operation = operation,
            displayScale = node.getDouble("display-scale", base?.displayScale ?: 1.0),
            suffix = node.getString("suffix") ?: base?.suffix ?: "",
            decimals = node.getInt("decimals", base?.decimals ?: 1).coerceIn(0, 4),
            vanillaBase = if (node.isSet("base")) node.getDouble("base") else base?.vanillaBase,
            softMin = node.getDouble("soft-min", base?.softMin ?: -100.0),
            softMax = node.getDouble("soft-max", base?.softMax ?: 100.0),
            order = node.getInt("order", base?.order ?: 0),
        )
    }

    private fun sortStats(values: Collection<Stat>): List<Stat> =
        values.sortedWith(compareBy({ it.category.ordinal }, { -it.order }, { it.id }))

    private fun groupByCategory(values: List<Stat>): Map<StatCategory, List<Stat>> {
        val map = EnumMap<StatCategory, MutableList<Stat>>(StatCategory::class.java)
        StatCategory.entries.forEach { map[it] = ArrayList() }
        values.forEach { map.getValue(it.category).add(it) }
        return map
    }

    companion object {

        /**
         * 바닐라 기본 스텟.
         *
         * 이 목록은 설정으로 지울 수 없다. MMOItems 를 쓰지 않는 서버도 이것만으로 운영이 가능하다.
         */
        fun defaults(): List<Stat> = listOf(
            // ── 전투 ──
            Stat(
                id = "attack_damage", display = "공격력",
                description = listOf("근접 공격이 주는 피해량이 늘어납니다.", "무기 피해량에 더해집니다."),
                category = StatCategory.COMBAT, icon = Material.IRON_SWORD, kind = StatKind.VANILLA,
                attributeKey = "attack_damage", vanillaBase = 1.0, softMin = -20.0, softMax = 20.0,
                order = 100,
            ),
            Stat(
                id = "attack_speed", display = "공격 속도",
                description = listOf("초당 휘두를 수 있는 횟수입니다.", "높을수록 쿨다운이 빨리 찹니다."),
                category = StatCategory.COMBAT, icon = Material.GOLDEN_SWORD, kind = StatKind.VANILLA,
                attributeKey = "attack_speed", decimals = 2, vanillaBase = 4.0,
                softMin = -4.0, softMax = 4.0, order = 90,
            ),

            // ── 방어 ──
            Stat(
                id = "max_health", display = "최대 체력",
                description = listOf("체력 상한이 늘어납니다.", "하트 1개 = 2입니다."),
                category = StatCategory.DEFENSE, icon = Material.GOLDEN_APPLE, kind = StatKind.VANILLA,
                attributeKey = "max_health", vanillaBase = 20.0, softMin = -18.0, softMax = 100.0,
                order = 100,
            ),
            Stat(
                id = "armor", display = "방어력",
                description = listOf("받는 피해를 줄입니다.", "갑옷 아이콘 1개 = 2입니다."),
                category = StatCategory.DEFENSE, icon = Material.IRON_CHESTPLATE, kind = StatKind.VANILLA,
                attributeKey = "armor", vanillaBase = 0.0, softMin = -20.0, softMax = 30.0, order = 90,
            ),
            Stat(
                id = "armor_toughness", display = "방어 강도",
                description = listOf("강한 공격에 대해 방어력이 덜 깎이게 합니다.", "고데미지 피격에 유리합니다."),
                category = StatCategory.DEFENSE, icon = Material.DIAMOND_CHESTPLATE, kind = StatKind.VANILLA,
                attributeKey = "armor_toughness", vanillaBase = 0.0, softMin = -20.0, softMax = 20.0, order = 80,
            ),
            Stat(
                id = "knockback_resistance", display = "넉백 저항",
                description = listOf("공격에 밀려나는 정도를 줄입니다.", "100%면 전혀 밀리지 않습니다."),
                category = StatCategory.DEFENSE, icon = Material.NETHERITE_INGOT, kind = StatKind.VANILLA,
                attributeKey = "knockback_resistance", displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = -100.0, softMax = 100.0, order = 70,
            ),
            Stat(
                id = "max_absorption", display = "흡수 체력",
                description = listOf("체력 위에 얹히는 노란 하트의 상한입니다."),
                category = StatCategory.DEFENSE, icon = Material.GOLDEN_CARROT, kind = StatKind.VANILLA,
                attributeKey = "max_absorption", vanillaBase = 0.0, softMin = 0.0, softMax = 40.0, order = 60,
            ),

            // ── 이동 ──
            Stat(
                id = "movement_speed", display = "이동 속도",
                description = listOf("걷기·달리기 속도가 빨라집니다.", "기본 속도에 비례해 적용됩니다."),
                category = StatCategory.MOBILITY, icon = Material.FEATHER, kind = StatKind.VANILLA,
                attributeKey = "movement_speed", operation = AttributeModifier.Operation.ADD_SCALAR,
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.1, softMin = -50.0, softMax = 100.0, order = 100,
            ),

            // ── 유틸리티 ──
            Stat(
                id = "luck", display = "행운",
                description = listOf("낚시 등 전리품 표의 품질에 영향을 줍니다."),
                category = StatCategory.UTILITY, icon = Material.RABBIT_FOOT, kind = StatKind.VANILLA,
                attributeKey = "luck", vanillaBase = 0.0, softMin = -10.0, softMax = 10.0, order = 100,
            ),
        )
    }
}
