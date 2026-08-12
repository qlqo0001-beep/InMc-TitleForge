package kr.inmc.titleforge.hook

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID
import java.util.function.Predicate
import java.util.logging.Logger

/**
 * MMOItems(MythicLib) 스텟 연동.
 *
 * 실제 API (MythicLib `io.lumine.mythic.lib.api.stat`):
 * - `MMOPlayerData.get(uuid).getStatMap().getInstance(statId)` → `StatInstance`
 * - `StatInstance#registerModifier(StatModifier)` 로 부착
 * - `StatInstance#removeIf(Predicate<String>)` 로 키 기준 회수
 * - `StatModifier(String key, String stat, double value)`
 *
 * **전부 리플렉션으로 호출한다.** MythicLib 을 컴파일 의존성으로 잡지 않으므로
 * 해당 플러그인이 없거나 버전이 달라도 빌드·구동에 영향이 없고, 연동에 실패하면
 * 경고 한 줄만 남기고 조용히 꺼진다. (스텟 값 자체는 계속 저장되고 플레이스홀더로 노출된다.)
 *
 * 적용 경로:
 * `MMOPlayerData.get(uuid).getStatMap().getInstance(statId)` 에
 * `addModifier(StatModifier, key)` 또는 `registerModifier(StatModifier)` 로 부착.
 */
class MythicLibHook private constructor(
    private val logger: Logger,
    private val playerDataGet: Method,
    private val getStatMap: Method,
    private val getInstance: Method,
    private val statModifierCtor: java.lang.reflect.Constructor<*>,
    private val registerModifier: Method,
    private val removeIf: Method,
) {

    @Volatile
    private var broken = false

    val available: Boolean get() = !broken

    /**
     * 플레이어의 MMO 스텟을 통째로 다시 적용한다.
     *
     * @param values MMOItems 스텟 ID → 수치
     */
    fun apply(player: Player, values: Map<String, Double>, knownStats: Collection<String>) {
        if (broken) return
        runCatching {
            val statMap = statMapOf(player.uniqueId) ?: return
            // 우리 키로 붙인 것만 회수한 뒤 다시 부착한다.
            for (statId in knownStats) {
                val instance = getInstance.invoke(statMap, statId) ?: continue
                runCatching { removeIf.invoke(instance, ownModifiers) }
            }
            for ((statId, value) in values) {
                if (value == 0.0) continue
                val instance = getInstance.invoke(statMap, statId) ?: continue
                val modifier = statModifierCtor.newInstance(MODIFIER_KEY, statId, value)
                registerModifier.invoke(instance, modifier)
            }
        }.onFailure { error ->
            broken = true
            logger.warning("MMOItems 스텟 적용에 실패해 연동을 비활성화합니다: ${error.message}")
        }
    }

    fun clear(player: Player, knownStats: Collection<String>) {
        if (broken) return
        runCatching {
            val statMap = statMapOf(player.uniqueId) ?: return
            for (statId in knownStats) {
                val instance = getInstance.invoke(statMap, statId) ?: continue
                runCatching { removeIf.invoke(instance, ownModifiers) }
            }
        }.onFailure {
            broken = true
            logger.warning("MMOItems 스텟 회수에 실패해 연동을 비활성화합니다: ${it.message}")
        }
    }

    /** `StatInstance#removeIf(Predicate<String>)` 에 넘길 조건. 우리 키만 지운다. */
    private val ownModifiers = Predicate<String> { key -> key == MODIFIER_KEY }

    private fun statMapOf(uuid: UUID): Any? {
        val data = playerDataGet.invoke(null, uuid) ?: return null
        return getStatMap.invoke(data)
    }

    companion object {
        /** 우리가 부착한 모디파이어만 골라내기 위한 키. */
        const val MODIFIER_KEY = "titleforge"

        private const val PLUGIN_NAME = "MythicLib"

        private val PLAYER_DATA_CLASSES = listOf(
            "io.lumine.mythic.lib.player.MMOPlayerData",
            "io.lumine.mythic.lib.api.player.MMOPlayerData",
        )

        private val STAT_MODIFIER_CLASSES = listOf(
            "io.lumine.mythic.lib.api.stat.modifier.StatModifier",
            "io.lumine.mythic.lib.player.modifier.StatModifier",
        )

        /**
         * 연동 준비. 필요한 클래스·메서드를 미리 찾아 캐시한다.
         * 하나라도 없으면 null 을 돌려주고 호출자는 연동을 끈다.
         */
        fun setup(logger: Logger): MythicLibHook? {
            if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return null

            return runCatching {
                val playerDataClass = PLAYER_DATA_CLASSES.firstNotNullOfOrNull { name ->
                    runCatching { Class.forName(name) }.getOrNull()
                } ?: error("MMOPlayerData 클래스를 찾지 못했습니다")

                val playerDataGet = playerDataClass.getMethod("get", UUID::class.java)
                val getStatMap = playerDataClass.getMethod("getStatMap")

                val statMapClass = getStatMap.returnType
                val getInstance = statMapClass.getMethod("getInstance", String::class.java)

                val statInstanceClass = getInstance.returnType
                val statModifierClass = STAT_MODIFIER_CLASSES.firstNotNullOfOrNull { name ->
                    runCatching { Class.forName(name) }.getOrNull()
                } ?: error("StatModifier 클래스를 찾지 못했습니다")

                // (key, stat, value) 생성자를 우선 찾고, 없으면 (key, value) 형태를 받아들인다.
                val statModifierCtor = statModifierClass.constructors.firstOrNull { ctor ->
                    ctor.parameterCount == 3 &&
                        ctor.parameterTypes[0] == String::class.java &&
                        ctor.parameterTypes[1] == String::class.java &&
                        ctor.parameterTypes[2] == java.lang.Double.TYPE
                } ?: error("StatModifier(String, String, double) 생성자를 찾지 못했습니다")

                // StatInstance#registerModifier(StatModifier)
                val registerModifier = statInstanceClass.methods.firstOrNull {
                    (it.name == "registerModifier" || it.name == "addModifier") &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(statModifierClass)
                } ?: error("스텟 모디파이어 등록 메서드를 찾지 못했습니다")

                // StatInstance#removeIf(Predicate<String>) — 키 기준 일괄 회수
                val removeIf = statInstanceClass.methods.firstOrNull {
                    it.name == "removeIf" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == Predicate::class.java
                } ?: error("스텟 모디파이어 회수 메서드(removeIf)를 찾지 못했습니다")

                MythicLibHook(
                    logger = logger,
                    playerDataGet = playerDataGet,
                    getStatMap = getStatMap,
                    getInstance = getInstance,
                    statModifierCtor = statModifierCtor,
                    registerModifier = registerModifier,
                    removeIf = removeIf,
                )
            }.getOrElse { error ->
                logger.warning(
                    "MythicLib 연동에 실패했습니다 (${error.message}). " +
                        "MMOItems 스텟은 값만 보관되며 바닐라 스텟은 정상 동작합니다.",
                )
                null
            }
        }
    }
}
