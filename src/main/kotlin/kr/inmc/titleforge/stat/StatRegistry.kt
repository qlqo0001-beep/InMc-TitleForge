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
            // MythicLib SharedStat 중 **바닐라 Attribute 로 적용되는 것 전부**.
            // 외부 플러그인 없이 이 목록만으로 서버를 운영할 수 있다.
            Stat(
                id = "attack_damage", display = "공격력",
                description = listOf(
                    "근접 공격이 주는 피해량이 늘어납니다.",
                    "무기 피해량에 더해집니다.",
                ),
                category = StatCategory.COMBAT, icon = Material.IRON_SWORD, kind = StatKind.VANILLA,
                attributeKey = "attack_damage",
                vanillaBase = 1.0, softMin = -20.0, softMax = 20.0, order = 100,
            ),
            Stat(
                id = "attack_speed", display = "공격 속도",
                description = listOf(
                    "초당 휘두를 수 있는 횟수입니다.",
                    "높을수록 쿨다운이 빨리 찹니다.",
                ),
                category = StatCategory.COMBAT, icon = Material.GOLDEN_SWORD, kind = StatKind.VANILLA,
                attributeKey = "attack_speed",
                decimals = 2,
                vanillaBase = 4.0, softMin = -4.0, softMax = 4.0, order = 95,
            ),
            Stat(
                id = "attack_knockback", display = "공격 넉백",
                description = listOf(
                    "내 공격이 상대를 밀어내는 정도입니다.",
                ),
                category = StatCategory.COMBAT, icon = Material.PISTON, kind = StatKind.VANILLA,
                attributeKey = "attack_knockback",
                vanillaBase = 0.0, softMin = -5.0, softMax = 5.0, order = 90,
            ),
            Stat(
                id = "sweeping_damage_ratio", display = "휩쓸기 피해",
                description = listOf(
                    "휩쓸기 공격이 주변 적에게 주는 피해 비율입니다.",
                ),
                category = StatCategory.COMBAT, icon = Material.IRON_HOE, kind = StatKind.VANILLA,
                attributeKey = "sweeping_damage_ratio",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = -100.0, softMax = 100.0, order = 85,
            ),
            Stat(
                id = "max_health", display = "최대 체력",
                description = listOf(
                    "체력 상한이 늘어납니다.",
                    "하트 1개 = 2입니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.GOLDEN_APPLE, kind = StatKind.VANILLA,
                attributeKey = "max_health",
                vanillaBase = 20.0, softMin = -18.0, softMax = 100.0, order = 100,
            ),
            Stat(
                id = "armor", display = "방어력",
                description = listOf(
                    "받는 피해를 줄입니다.",
                    "갑옷 아이콘 1개 = 2입니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.IRON_CHESTPLATE, kind = StatKind.VANILLA,
                attributeKey = "armor",
                vanillaBase = 0.0, softMin = -20.0, softMax = 30.0, order = 95,
            ),
            Stat(
                id = "armor_toughness", display = "방어 강도",
                description = listOf(
                    "강한 공격에 대해 방어력이 덜 깎이게 합니다.",
                    "고데미지 피격에 유리합니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.DIAMOND_CHESTPLATE, kind = StatKind.VANILLA,
                attributeKey = "armor_toughness",
                vanillaBase = 0.0, softMin = -20.0, softMax = 20.0, order = 90,
            ),
            Stat(
                id = "knockback_resistance", display = "넉백 저항",
                description = listOf(
                    "공격에 밀려나는 정도를 줄입니다.",
                    "100%면 전혀 밀리지 않습니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.NETHERITE_INGOT, kind = StatKind.VANILLA,
                attributeKey = "knockback_resistance",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = -100.0, softMax = 100.0, order = 85,
            ),
            Stat(
                id = "max_absorption", display = "흡수 체력",
                description = listOf(
                    "체력 위에 얹히는 노란 하트의 상한입니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.GOLDEN_CARROT, kind = StatKind.VANILLA,
                attributeKey = "max_absorption",
                vanillaBase = 0.0, softMin = 0.0, softMax = 40.0, order = 80,
            ),
            Stat(
                id = "explosion_knockback_resistance", display = "폭발 넉백 저항",
                description = listOf(
                    "폭발에 밀려나는 정도를 줄입니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.TNT, kind = StatKind.VANILLA,
                attributeKey = "explosion_knockback_resistance",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = -100.0, softMax = 100.0, order = 75,
            ),
            Stat(
                id = "fall_damage_multiplier", display = "낙하 피해 배율",
                description = listOf(
                    "낙하로 받는 피해에 곱해지는 값입니다.",
                    "0이면 낙하 피해를 받지 않습니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.HAY_BLOCK, kind = StatKind.VANILLA,
                attributeKey = "fall_damage_multiplier",
                decimals = 2,
                vanillaBase = 1.0, softMin = -1.0, softMax = 1.0, order = 70,
            ),
            Stat(
                id = "burning_time", display = "화상 지속시간",
                description = listOf(
                    "불에 탈 때 지속 시간에 곱해지는 값입니다.",
                    "낮을수록 빨리 꺼집니다.",
                ),
                category = StatCategory.DEFENSE, icon = Material.BLAZE_POWDER, kind = StatKind.VANILLA,
                attributeKey = "burning_time",
                decimals = 2,
                vanillaBase = 1.0, softMin = -1.0, softMax = 1.0, order = 65,
            ),
            Stat(
                id = "movement_speed", display = "이동 속도",
                description = listOf(
                    "걷기·달리기 속도가 빨라집니다.",
                    "기본 속도에 비례해 적용됩니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.FEATHER, kind = StatKind.VANILLA,
                attributeKey = "movement_speed",
                operation = AttributeModifier.Operation.ADD_SCALAR,
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.1, softMin = -50.0, softMax = 100.0, order = 100,
            ),
            Stat(
                id = "jump_strength", display = "점프력",
                description = listOf(
                    "점프 높이가 올라갑니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.RABBIT_FOOT, kind = StatKind.VANILLA,
                attributeKey = "jump_strength",
                decimals = 2,
                vanillaBase = 0.42, softMin = -0.4, softMax = 1.0, order = 95,
            ),
            Stat(
                id = "safe_fall_distance", display = "안전 낙하 거리",
                description = listOf(
                    "이 거리까지는 낙하 피해를 받지 않습니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.SLIME_BLOCK, kind = StatKind.VANILLA,
                attributeKey = "safe_fall_distance",
                vanillaBase = 3.0, softMin = -3.0, softMax = 50.0, order = 90,
            ),
            Stat(
                id = "gravity", display = "중력",
                description = listOf(
                    "떨어지는 가속도입니다. 낮으면 천천히 떨어집니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.ANVIL, kind = StatKind.VANILLA,
                attributeKey = "gravity",
                decimals = 3,
                vanillaBase = 0.08, softMin = -0.08, softMax = 0.5, order = 85,
            ),
            Stat(
                id = "step_height", display = "계단 높이",
                description = listOf(
                    "점프 없이 오를 수 있는 블록 높이입니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.STONE_BRICK_STAIRS, kind = StatKind.VANILLA,
                attributeKey = "step_height",
                decimals = 2,
                vanillaBase = 0.6, softMin = -0.6, softMax = 3.0, order = 80,
            ),
            Stat(
                id = "sneaking_speed", display = "웅크리기 속도",
                description = listOf(
                    "웅크린 상태의 이동 속도 배율입니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.LEATHER_BOOTS, kind = StatKind.VANILLA,
                attributeKey = "sneaking_speed",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.3, softMin = -100.0, softMax = 200.0, order = 75,
            ),
            Stat(
                id = "movement_efficiency", display = "지형 이동 효율",
                description = listOf(
                    "끈적한 블록 위에서 감속을 덜 받습니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.POWDER_SNOW_BUCKET, kind = StatKind.VANILLA,
                attributeKey = "movement_efficiency",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = 0.0, softMax = 100.0, order = 70,
            ),
            Stat(
                id = "water_movement_efficiency", display = "수중 이동 효율",
                description = listOf(
                    "물속에서 감속을 덜 받습니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.HEART_OF_THE_SEA, kind = StatKind.VANILLA,
                attributeKey = "water_movement_efficiency",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.0, softMin = 0.0, softMax = 100.0, order = 65,
            ),
            Stat(
                id = "scale", display = "크기",
                description = listOf(
                    "플레이어 몸 크기 배율입니다.",
                    "닿는 거리와 시야 높이에도 영향을 줍니다.",
                ),
                category = StatCategory.MOBILITY, icon = Material.SCAFFOLDING, kind = StatKind.VANILLA,
                attributeKey = "scale",
                decimals = 2,
                vanillaBase = 1.0, softMin = -0.9, softMax = 5.0, order = 60,
            ),
            Stat(
                id = "block_break_speed", display = "채굴 속도",
                description = listOf(
                    "블록을 부수는 속도 배율입니다.",
                ),
                category = StatCategory.WORLD, icon = Material.IRON_PICKAXE, kind = StatKind.VANILLA,
                attributeKey = "block_break_speed",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 1.0, softMin = -100.0, softMax = 500.0, order = 100,
            ),
            Stat(
                id = "mining_efficiency", display = "채굴 효율",
                description = listOf(
                    "적합한 도구로 캘 때의 추가 효율입니다.",
                ),
                category = StatCategory.WORLD, icon = Material.DIAMOND_PICKAXE, kind = StatKind.VANILLA,
                attributeKey = "mining_efficiency",
                vanillaBase = 0.0, softMin = -10.0, softMax = 50.0, order = 95,
            ),
            Stat(
                id = "submerged_mining_speed", display = "수중 채굴 속도",
                description = listOf(
                    "물속에서의 채굴 속도 배율입니다.",
                ),
                category = StatCategory.WORLD, icon = Material.PRISMARINE_SHARD, kind = StatKind.VANILLA,
                attributeKey = "submerged_mining_speed",
                displayScale = 100.0, suffix = "%", decimals = 0,
                vanillaBase = 0.2, softMin = -100.0, softMax = 500.0, order = 90,
            ),
            Stat(
                id = "block_interaction_range", display = "블록 도달 거리",
                description = listOf(
                    "블록을 설치·파괴할 수 있는 거리입니다.",
                ),
                category = StatCategory.WORLD, icon = Material.STICK, kind = StatKind.VANILLA,
                attributeKey = "block_interaction_range",
                decimals = 1,
                vanillaBase = 4.5, softMin = -4.0, softMax = 10.0, order = 85,
            ),
            Stat(
                id = "entity_interaction_range", display = "개체 도달 거리",
                description = listOf(
                    "몹·플레이어와 상호작용할 수 있는 거리입니다.",
                ),
                category = StatCategory.WORLD, icon = Material.FISHING_ROD, kind = StatKind.VANILLA,
                attributeKey = "entity_interaction_range",
                decimals = 1,
                vanillaBase = 3.0, softMin = -2.5, softMax = 10.0, order = 80,
            ),
            Stat(
                id = "oxygen_bonus", display = "산소 보너스",
                description = listOf(
                    "물속에서 숨이 더 오래 갑니다.",
                ),
                category = StatCategory.WORLD, icon = Material.TURTLE_HELMET, kind = StatKind.VANILLA,
                attributeKey = "oxygen_bonus",
                vanillaBase = 0.0, softMin = 0.0, softMax = 20.0, order = 75,
            ),
            Stat(
                id = "luck", display = "행운",
                description = listOf(
                    "낚시 등 전리품 표의 품질에 영향을 줍니다.",
                ),
                category = StatCategory.UTILITY, icon = Material.RABBIT_FOOT, kind = StatKind.VANILLA,
                attributeKey = "luck",
                vanillaBase = 0.0, softMin = -10.0, softMax = 10.0, order = 100,
            ),
        )
    }
}
