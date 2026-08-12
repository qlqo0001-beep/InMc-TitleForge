package kr.inmc.titleforge.config

import kr.inmc.titleforge.stat.Stats
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

/**
 * 불변 설정 스냅샷. reload 시 새 인스턴스로 통째 교체한다 (맞춤 지침 7.2-8).
 */
class Settings private constructor(
    val storage: StorageSettings,
    val title: TitleSettings,
    val sealNoneDisplay: String,
    val nickname: NicknameSettings,
    val display: DisplaySettings,
    val gui: GuiSettings,
    val rank: RankSettings,
    val debug: Boolean,
) {

    enum class StorageType { SQLITE, MYSQL }

    enum class InputMode { ANVIL, CHAT }

    enum class LengthMode { CHARS, WIDTH }

    class StorageSettings(
        val type: StorageType,
        val sqliteFile: String,
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String,
        val properties: String,
        val poolSize: Int,
        val autosaveSeconds: Long,
        val cacheKeepSeconds: Long,
    )

    class TitleSettings(
        val noneDisplay: String,
        val noneStat: String,
        /** 보유 개수 → 추가 스텟(스텟 id 기준). 개수 오름차순 정렬. */
        val milestones: List<Pair<Int, Map<String, Double>>>,
    )

    class NicknameSettings(
        val enabled: Boolean,
        val inputMode: InputMode,
        val lengthMode: LengthMode,
        val minLength: Int,
        val maxLength: Int,
        val allowKorean: Boolean,
        val allowEnglish: Boolean,
        val allowDigits: Boolean,
        val allowSpace: Boolean,
        val extraAllowedChars: String,
        val koreanCompleteOnly: Boolean,
        val unique: Boolean,
        val blacklist: List<String>,
        val cooldownSeconds: Long,
        val economyEnabled: Boolean,
        val economyAmount: Double,
        val itemEnabled: Boolean,
        val itemMaterial: Material,
        val itemAmount: Int,
        val itemName: String,
    ) {
        /** 허용 문자 정규식 (전체 문자열 매칭). */
        val pattern: Regex = buildPattern()

        /** 유저 안내용 허용 문자 설명. */
        val allowedDescription: String = buildDescription()

        private fun buildPattern(): Regex {
            val cls = StringBuilder()
            if (allowKorean) cls.append(if (koreanCompleteOnly) "가-힣" else "가-힣ㄱ-ㅎㅏ-ㅣ")
            if (allowEnglish) cls.append("a-zA-Z")
            if (allowDigits) cls.append("0-9")
            if (allowSpace) cls.append(" ")
            extraAllowedChars.forEach { cls.append(Regex.escape(it.toString())) }
            if (cls.isEmpty()) cls.append("a-zA-Z0-9")
            return Regex("^[$cls]+$")
        }

        private fun buildDescription(): String {
            val parts = buildList {
                if (allowKorean) add(if (koreanCompleteOnly) "한글(완성형)" else "한글")
                if (allowEnglish) add("영문")
                if (allowDigits) add("숫자")
                if (allowSpace) add("공백")
                if (extraAllowedChars.isNotEmpty()) add(extraAllowedChars)
            }
            return if (parts.isEmpty()) "영문, 숫자" else parts.joinToString(", ")
        }

        /** 설정된 계산 방식으로 길이를 잰다. */
        fun lengthOf(value: String): Int = when (lengthMode) {
            LengthMode.CHARS -> value.codePointCount(0, value.length)
            LengthMode.WIDTH -> value.sumOf { if (it.code in 0x1100..0x11FF || it.code in 0x3130..0x318F || it.code in 0xAC00..0xD7A3) 2 else 1 }
        }
    }

    class DisplaySettings(
        val nameplateFormat: String,
        val sealSuffix: String,
        val titleSuffix: String,
        val displayName: Boolean,
        val tab: Boolean,
        val chatEnabled: Boolean,
        val chatFormat: String,
        val nametag: NametagSettings,
        val tablist: TablistSettings,
        /** 표시 갱신 티커 주기(틱). */
        val refreshTicks: Long,
    )

    /**
     * 머리 위 이름표.
     *
     * 여러 줄이 필요하므로 TextDisplay 1개에 줄바꿈으로 렌더링한다.
     * 줄 수만큼 엔티티를 띄우지 않으므로 인원이 많아도 부담이 적다.
     */
    class NametagSettings(
        val enabled: Boolean,
        /** 위에서부터 한 줄씩. 빈 줄(치환 결과가 비면)은 자동 생략. */
        val lines: List<String>,
        val heightOffset: Double,
        val viewRange: Double,
        val seeThrough: Boolean,
        val textShadow: Boolean,
        val background: Boolean,
        val backgroundColor: Int,
        /** 본인에게도 보일지. 인장을 스스로 확인할 수 있어야 하므로 기본 true. */
        val showToSelf: Boolean,
        /** 바닐라 이름표를 숨길지. */
        val hideVanillaNametag: Boolean,
    )

    class TablistSettings(
        val enabled: Boolean,
        val header: List<String>,
        val footer: List<String>,
        val tpsGood: Double,
        val tpsWarn: Double,
        val msptGood: Double,
        val msptWarn: Double,
    )

    class RankSettings(
        val enabled: Boolean,
        val cacheSeconds: Long,
        val topSize: Int,
    )

    class GuiSettings(
        val pageSize: Int,
        val showUnownedByDefault: Boolean,
        /** 채팅 값 입력 대기 시간(초). 0 이면 무제한. */
        val chatInputTimeoutSeconds: Long,
        val filler: Material,
        val iconTitle: Material,
        val iconSeal: Material,
        val iconProfile: Material,
        val iconAdmin: Material,
        val iconOwned: Material,
        val iconLocked: Material,
        val iconEquipped: Material,
    )

    companion object {

        fun load(config: FileConfiguration): Settings {
            val storage = StorageSettings(
                type = runCatching {
                    StorageType.valueOf(config.getString("storage.type", "SQLITE")!!.uppercase())
                }.getOrDefault(StorageType.SQLITE),
                sqliteFile = config.getString("storage.sqlite.file", "data.db")!!,
                host = config.getString("storage.mysql.host", "localhost")!!,
                port = config.getInt("storage.mysql.port", 3306),
                database = config.getString("storage.mysql.database", "titleforge")!!,
                username = config.getString("storage.mysql.username", "root")!!,
                password = config.getString("storage.mysql.password", "")!!,
                properties = config.getString("storage.mysql.properties", "")!!,
                poolSize = config.getInt("storage.mysql.pool-size", 6).coerceIn(1, 30),
                autosaveSeconds = config.getLong("storage.autosave-seconds", 300L).coerceAtLeast(0L),
                cacheKeepSeconds = config.getLong("storage.cache-keep-seconds", 60L).coerceAtLeast(0L),
            )

            val title = TitleSettings(
                noneDisplay = config.getString("title.none-display", "")!!,
                noneStat = config.getString("title.none-stat", "<gray>없음</gray>")!!,
                milestones = readMilestones(config.getConfigurationSection("title.collection-milestones")),
            )

            val nickname = NicknameSettings(
                enabled = config.getBoolean("nickname.enabled", true),
                inputMode = runCatching {
                    InputMode.valueOf(config.getString("nickname.input-mode", "ANVIL")!!.uppercase())
                }.getOrDefault(InputMode.ANVIL),
                lengthMode = runCatching {
                    LengthMode.valueOf(config.getString("nickname.length-mode", "CHARS")!!.uppercase())
                }.getOrDefault(LengthMode.CHARS),
                minLength = config.getInt("nickname.min-length", 2).coerceAtLeast(1),
                maxLength = config.getInt("nickname.max-length", 12).coerceAtLeast(1),
                allowKorean = config.getBoolean("nickname.allow-korean", true),
                allowEnglish = config.getBoolean("nickname.allow-english", true),
                allowDigits = config.getBoolean("nickname.allow-digits", true),
                allowSpace = config.getBoolean("nickname.allow-space", false),
                extraAllowedChars = config.getString("nickname.extra-allowed-chars", "")!!,
                koreanCompleteOnly = config.getBoolean("nickname.korean-complete-only", true),
                unique = config.getBoolean("nickname.unique", true),
                blacklist = config.getStringList("nickname.blacklist").map { it.lowercase() },
                cooldownSeconds = config.getLong("nickname.cooldown-seconds", 0L).coerceAtLeast(0L),
                economyEnabled = config.getBoolean("nickname.cost.economy.enabled", false),
                economyAmount = config.getDouble("nickname.cost.economy.amount", 0.0).coerceAtLeast(0.0),
                itemEnabled = config.getBoolean("nickname.cost.item.enabled", false),
                itemMaterial = material(config.getString("nickname.cost.item.material"), Material.PAPER),
                itemAmount = config.getInt("nickname.cost.item.amount", 1).coerceAtLeast(1),
                itemName = config.getString("nickname.cost.item.name", "")!!,
            )

            val display = DisplaySettings(
                nameplateFormat = config.getString("display.nameplate-format", "<seal><title><nickname>")!!,
                sealSuffix = config.getString("display.seal-suffix", " ")!!,
                titleSuffix = config.getString("display.title-suffix", " ")!!,
                displayName = config.getBoolean("display.display-name", true),
                tab = config.getBoolean("display.tab", false),
                chatEnabled = config.getBoolean("display.chat.enabled", false),
                chatFormat = config.getString(
                    "display.chat.format",
                    "<nameplate><dark_gray> » </dark_gray><white><message></white>",
                )!!,
                nametag = NametagSettings(
                    enabled = config.getBoolean("display.nametag.enabled", false),
                    lines = config.getStringList("display.nametag.lines")
                        .ifEmpty { listOf("<seal>", "<title>", "<nickname>") },
                    heightOffset = config.getDouble("display.nametag.height-offset", 0.4),
                    viewRange = config.getDouble("display.nametag.view-range", 32.0).coerceAtLeast(1.0),
                    seeThrough = config.getBoolean("display.nametag.see-through", false),
                    textShadow = config.getBoolean("display.nametag.text-shadow", true),
                    background = config.getBoolean("display.nametag.background", false),
                    backgroundColor = config.getInt("display.nametag.background-color", 0x40000000),
                    showToSelf = config.getBoolean("display.nametag.show-to-self", true),
                    hideVanillaNametag = config.getBoolean("display.nametag.hide-vanilla", true),
                ),
                tablist = TablistSettings(
                    enabled = config.getBoolean("display.tablist.enabled", false),
                    header = config.getStringList("display.tablist.header"),
                    footer = config.getStringList("display.tablist.footer"),
                    tpsGood = config.getDouble("display.tablist.tps-good", 19.0),
                    tpsWarn = config.getDouble("display.tablist.tps-warn", 15.0),
                    msptGood = config.getDouble("display.tablist.mspt-good", 25.0),
                    msptWarn = config.getDouble("display.tablist.mspt-warn", 45.0),
                ),
                refreshTicks = config.getLong("display.refresh-ticks", 20L).coerceAtLeast(5L),
            )

            val gui = GuiSettings(
                pageSize = config.getInt("gui.page-size", 45).coerceIn(9, 45).let { it - it % 9 },
                showUnownedByDefault = config.getBoolean("gui.show-unowned-by-default", true),
                chatInputTimeoutSeconds = config.getLong("gui.chat-input-timeout-seconds", 60L)
                    .coerceAtLeast(0L),
                filler = material(config.getString("gui.filler"), Material.GRAY_STAINED_GLASS_PANE),
                iconTitle = material(config.getString("gui.icons.title"), Material.NAME_TAG),
                iconSeal = material(config.getString("gui.icons.seal"), Material.HEART_OF_THE_SEA),
                iconProfile = material(config.getString("gui.icons.profile"), Material.PLAYER_HEAD),
                iconAdmin = material(config.getString("gui.icons.admin"), Material.COMMAND_BLOCK),
                iconOwned = material(config.getString("gui.icons.owned"), Material.PAPER),
                iconLocked = material(config.getString("gui.icons.locked"), Material.GRAY_DYE),
                iconEquipped = material(config.getString("gui.icons.equipped"), Material.ENCHANTED_BOOK),
            )

            val rank = RankSettings(
                enabled = config.getBoolean("rank.enabled", true),
                cacheSeconds = config.getLong("rank.cache-seconds", 300L).coerceAtLeast(10L),
                topSize = config.getInt("rank.top-size", 45).coerceIn(1, 45),
            )

            return Settings(
                storage = storage,
                title = title,
                sealNoneDisplay = config.getString("seal.none-display", "")!!,
                nickname = nickname,
                display = display,
                gui = gui,
                rank = rank,
                debug = config.getBoolean("debug", false),
            )
        }

        private fun readMilestones(section: ConfigurationSection?): List<Pair<Int, Map<String, Double>>> {
            if (section == null) return emptyList()
            val result = ArrayList<Pair<Int, Map<String, Double>>>()
            for (key in section.getKeys(false)) {
                val count = key.toIntOrNull() ?: continue
                val inner = section.getConfigurationSection(key) ?: continue
                val stats = LinkedHashMap<String, Double>()
                for (statKey in inner.getKeys(false)) {
                    val value = inner.getDouble(statKey)
                    if (value != 0.0) stats[statKey.lowercase()] = value
                }
                if (stats.isNotEmpty()) result += count to Stats.merge(stats)
            }
            return result.sortedBy { it.first }
        }

        private fun material(name: String?, fallback: Material): Material =
            name?.let { Material.matchMaterial(it.uppercase()) } ?: fallback
    }
}
