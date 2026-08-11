package kr.inmc.titleforge.stat

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlotGroup
import java.util.EnumMap
import java.util.logging.Logger

/**
 * 계산된 스텟 총합을 바닐라 AttributeModifier 로 반영한다.
 *
 * 반드시 해당 플레이어를 소유한 스레드에서 호출할 것 (맞춤 지침 7.1-2).
 * 재적용 시 titleforge 네임스페이스의 모디파이어만 회수하므로 타 플러그인 값은 건드리지 않는다.
 */
class StatApplier(private val logger: Logger) {

    private val attributes: Map<StatType, Attribute> = resolveAttributes()

    private fun resolveAttributes(): Map<StatType, Attribute> {
        val map = EnumMap<StatType, Attribute>(StatType::class.java)
        val registry = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)
        }.getOrElse {
            logger.warning("Attribute 레지스트리를 찾지 못했습니다. 바닐라 스텟이 적용되지 않습니다.")
            return map
        }
        for (stat in StatType.VANILLA) {
            val key = NamespacedKey.minecraft(stat.attributeKey!!)
            val attribute = runCatching { registry.get(key) }.getOrNull()
            if (attribute == null) {
                logger.warning("알 수 없는 Attribute: $key (스텟 ${stat.id} 은(는) 비활성화됩니다)")
            } else {
                map[stat] = attribute
            }
        }
        return map
    }

    fun apply(player: Player, stats: Map<StatType, Double>) {
        for ((stat, attribute) in attributes) {
            val instance = player.getAttribute(attribute) ?: continue
            removeOwn(instance, stat.modifierKey)

            val amount = stats[stat] ?: 0.0
            if (amount == 0.0) continue
            instance.addModifier(
                AttributeModifier(stat.modifierKey, amount, stat.operation, EquipmentSlotGroup.ANY),
            )
        }
        clampHealth(player)
    }

    fun clear(player: Player) {
        for ((stat, attribute) in attributes) {
            val instance = player.getAttribute(attribute) ?: continue
            removeOwn(instance, stat.modifierKey)
        }
        clampHealth(player)
    }

    private fun removeOwn(instance: org.bukkit.attribute.AttributeInstance, key: NamespacedKey) {
        val owned = instance.modifiers.filter { it.key == key }
        for (modifier in owned) {
            runCatching { instance.removeModifier(modifier) }
        }
    }

    /** 최대 체력이 줄어든 경우 현재 체력을 잘라 즉사를 막는다 (맞춤 지침 7.4-18). */
    private fun clampHealth(player: Player) {
        val maxHealthAttribute = attributes[StatType.MAX_HEALTH] ?: return
        val max = player.getAttribute(maxHealthAttribute)?.value ?: return
        if (max > 0 && player.health > max) {
            player.health = max
        }
    }
}
