package kr.inmc.titleforge.stat

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.titleforge.hook.MythicLibHook
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlotGroup
import java.util.logging.Logger

/**
 * 계산된 스텟 총합을 실제 서버에 반영한다.
 *
 * - **바닐라 스텟**: AttributeModifier. 외부 플러그인 없이 항상 동작한다.
 * - **MMO 스텟**: MythicLib 연동이 있을 때만. 없으면 값만 보관된다.
 * - **가상 스텟**: 적용하지 않고 API·플레이스홀더로만 노출.
 *
 * 반드시 해당 플레이어를 소유한 스레드에서 호출할 것 (맞춤 지침 7.1-2).
 * 재적용 시 titleforge 네임스페이스의 모디파이어만 회수하므로 타 플러그인 값은 건드리지 않는다.
 */
class StatApplier(
    private val logger: Logger,
    private val registry: StatRegistry,
) {

    @Volatile
    var mythicLib: MythicLibHook? = null

    private val attributes = HashMap<String, Attribute>()

    /**
     * 지금까지 한 번이라도 등장한 MMO 스텟 식별자의 누적 집합(리로드로 줄지 않음).
     *
     * `/it reload` 로 `stats.yml` 에서 매핑을 빼거나 이름을 바꾸면, 그 순간 온라인이던
     * 플레이어에게는 예전 식별자로 이미 모디파이어가 붙어 있다. 회수 대상을 **현재**
     * 레지스트리에서만 뽑으면 그 예전 모디파이어는 영원히 빠지지 않고 남는다.
     * 이 집합은 계속 누적해서, 회수(removeIf) 대상만큼은 예전 것도 놓치지 않는다.
     */
    private val knownMmoStats = HashSet<String>()

    private var attributeRegistryFailed = false

    /** 레지스트리가 바뀌면 Attribute 조회 캐시도 다시 만든다. */
    fun refreshDefinitions() {
        attributes.clear()
        val access = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)
        }.getOrElse {
            if (!attributeRegistryFailed) {
                attributeRegistryFailed = true
                logger.severe("Attribute 레지스트리를 찾지 못했습니다. 바닐라 스텟이 적용되지 않습니다.")
            }
            return
        }

        for (stat in registry.ofKind(StatKind.VANILLA)) {
            val key = NamespacedKey.minecraft(stat.attributeKey ?: continue)
            val attribute = runCatching { access.get(key) }.getOrNull()
            if (attribute == null) {
                logger.warning("알 수 없는 Attribute: $key (스텟 ${stat.id} 은(는) 적용되지 않습니다)")
            } else {
                attributes[stat.id] = attribute
            }
        }

        knownMmoStats.addAll(registry.ofKind(StatKind.MMO).mapNotNull { it.mmoStat })
    }

    fun apply(player: Player, stats: Map<String, Double>) {
        applyVanilla(player, stats)
        applyMmo(player, stats)
    }

    fun clear(player: Player) {
        applyVanilla(player, emptyMap())
        mythicLib?.clear(player, mmoStatIds())
    }

    private fun applyVanilla(player: Player, stats: Map<String, Double>) {
        for (stat in registry.ofKind(StatKind.VANILLA)) {
            val attribute = attributes[stat.id] ?: continue
            val instance = player.getAttribute(attribute) ?: continue
            removeOwn(instance, stat.modifierKey)

            val amount = stats[stat.id] ?: 0.0
            if (amount == 0.0) continue
            instance.addModifier(
                AttributeModifier(stat.modifierKey, amount, stat.operation, EquipmentSlotGroup.ANY),
            )
        }
        clampHealth(player)
    }

    private fun applyMmo(player: Player, stats: Map<String, Double>) {
        val hook = mythicLib ?: return
        val values = HashMap<String, Double>()
        for (stat in registry.ofKind(StatKind.MMO)) {
            val mmoStat = stat.mmoStat ?: continue
            val amount = stats[stat.id] ?: 0.0
            if (amount != 0.0) values[mmoStat] = (values[mmoStat] ?: 0.0) + amount
        }
        hook.apply(player, values, mmoStatIds())
    }

    /** 회수 대상. 현재 등록분이 아니라 [knownMmoStats] 누적 집합을 쓴다(위 설명 참고). */
    private fun mmoStatIds(): Collection<String> = knownMmoStats

    private fun removeOwn(instance: AttributeInstance, key: NamespacedKey) {
        val owned = instance.modifiers.filter { it.key == key }
        for (modifier in owned) {
            runCatching { instance.removeModifier(modifier) }
        }
    }

    /** 최대 체력이 줄어든 경우 현재 체력을 잘라 즉사를 막는다 (맞춤 지침 7.4-18). */
    private fun clampHealth(player: Player) {
        val attribute = attributes["max_health"] ?: return
        val max = player.getAttribute(attribute)?.value ?: return
        if (max > 0 && player.health > max) {
            player.health = max
        }
    }
}
