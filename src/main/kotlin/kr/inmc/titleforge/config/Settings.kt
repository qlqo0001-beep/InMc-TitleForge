package kr.inmc.titleforge.config

import kr.inmc.titleforge.stat.StatType
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
        /** 보유 개수 → 추가 스텟. 개수 오름차순 정렬. */
        val milestones: List<Pair<Int, Map<StatType, Double>>>,
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
        val nametag: Boolean,
    )

    class GuiSettings(
        val pageSize: Int,
        val showUnownedByDefault: Boolean,
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
                nametag = config.getBoolean("display.nametag", false),
            )

            val gui = GuiSettings(
                pageSize = config.getInt("gui.page-size", 45).coerceIn(9, 45).let { it - it % 9 },
                showUnownedByDefault = config.getBoolean("gui.show-unowned-by-default", true),
                filler = material(config.getString("gui.filler"), Material.GRAY_STAINED_GLASS_PANE),
                iconTitle = material(config.getString("gui.icons.title"), Material.NAME_TAG),
                iconSeal = material(config.getString("gui.icons.seal"), Material.HEART_OF_THE_SEA),
                iconProfile = material(config.getString("gui.icons.profile"), Material.PLAYER_HEAD),
                iconAdmin = material(config.getString("gui.icons.admin"), Material.COMMAND_BLOCK),
                iconOwned = material(config.getString("gui.icons.owned"), Material.PAPER),
                iconLocked = material(config.getString("gui.icons.locked"), Material.GRAY_DYE),
                iconEquipped = material(config.getString("gui.icons.equipped"), Material.ENCHANTED_BOOK),
            )

            return Settings(
                storage = storage,
                title = title,
                sealNoneDisplay = config.getString("seal.none-display", "")!!,
                nickname = nickname,
                display = display,
                gui = gui,
                debug = config.getBoolean("debug", false),
            )
        }

        private fun readMilestones(section: ConfigurationSection?): List<Pair<Int, Map<StatType, Double>>> {
            if (section == null) return emptyList()
            val result = ArrayList<Pair<Int, Map<StatType, Double>>>()
            for (key in section.getKeys(false)) {
                val count = key.toIntOrNull() ?: continue
                val inner = section.getConfigurationSection(key) ?: continue
                val stats = HashMap<StatType, Double>()
                for (statKey in inner.getKeys(false)) {
                    val stat = StatType.of(statKey) ?: continue
                    stats[stat] = inner.getDouble(statKey)
                }
                if (stats.isNotEmpty()) result += count to Stats.merge(stats)
            }
            return result.sortedBy { it.first }
        }

        private fun material(name: String?, fallback: Material): Material =
            name?.let { Material.matchMaterial(it.uppercase()) } ?: fallback
    }
}
