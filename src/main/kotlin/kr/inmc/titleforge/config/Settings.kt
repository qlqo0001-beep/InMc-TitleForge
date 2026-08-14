package kr.inmc.titleforge.config

import kr.inmc.titleforge.stat.Stats
import org.bukkit.Bukkit
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

    enum class InputMode { DIALOG, CHAT }

    enum class LengthMode { CHARS, WIDTH }

    /** 비용 아이템 판별 방식. */
    enum class ItemSourceType { VANILLA, MMOITEMS }

    /**
     * 비용 아이템 한 종류.
     *
     * [label] 은 설정 파일의 키(`vanilla_nickitem` 등)로, 문제가 생겼을 때 어느 항목인지
     * 바로 알 수 있도록 경고 메시지에 그대로 쓴다.
     */
    class CostItem(
        val label: String,
        val type: ItemSourceType,
        val amount: Int,
        /** VANILLA 전용. */
        val material: Material,
        /** VANILLA 전용. 비우면 이름 검사를 하지 않는다. */
        val displayName: String,
        /** MMOITEMS 전용. */
        val mmoType: String,
        /** MMOITEMS 전용. */
        val mmoId: String,
    )

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
        /** 비용 아이템 정의. 여러 개를 켜면 **하나만 만족해도** 지불로 인정한다. */
        val costItems: List<CostItem>,
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
        val placeholderIntervals: PlaceholderIntervals,
        /** 표시 갱신 티커 주기(틱). */
        val refreshTicks: Long,
        /**
         * 플레이스홀더가 남긴 색이 뒤 내용까지 번지게 둘지.
         *
         * `false`(기본)면 각 `%토큰%` 값을 격리해, 닫히지 않은 색이 뒤따르는 칭호·닉네임에
         * 묻지 않는다. `true` 면 예전처럼 그대로 흘려보낸다.
         */
        val colorBleed: Boolean,
    )

    /**
     * 토큰별 재계산 주기(밀리초).
     *
     * 티커가 아무리 자주 돌아도 여기 지정된 주기보다 자주 계산되지 않는다.
     * 키는 `%player_x%` 처럼 퍼센트를 포함한 원문 그대로 적는다.
     */
    class PlaceholderIntervals(
        private val defaultMillis: Long,
        private val byToken: Map<String, Long>,
    ) {
        fun intervalOf(token: String): Long = byToken[token] ?: defaultMillis
    }

    /**
     * 머리 위 이름표.
     *
     * 여러 줄이 필요하므로 TextDisplay 1개에 줄바꿈으로 렌더링한다.
     * 줄 수만큼 엔티티를 띄우지 않으므로 인원이 많아도 부담이 적다.
     */
    /**
     * 머리 위 이름표.
     *
     * 줄을 **두 묶음**으로 나눈다. 본인이 자기 인장은 보되 자기 닉네임·칭호는 가리고 싶은
     * 요구를 한 엔티티로는 표현할 수 없기 때문이다. 묶음별로 TextDisplay 를 하나씩 띄우고,
     * [othersLines] 쪽만 본인에게 숨긴다.
     */
    class NametagSettings(
        val enabled: Boolean,
        /** 본인과 다른 플레이어 모두에게 보이는 줄. */
        val sharedLines: List<String>,
        /** 다른 플레이어에게만 보이는 줄. 본인 화면에서는 숨겨진다. */
        val othersLines: List<String>,
        val sharedHeightOffset: Double,
        val othersHeightOffset: Double,
        val viewRange: Double,
        val seeThrough: Boolean,
        val textShadow: Boolean,
        val background: Boolean,
        val backgroundColor: Int,
        /** 바닐라 이름표를 숨길지. */
        val hideVanillaNametag: Boolean,
        /** 상대가 블록 등에 가려 실제로 안 보이면 이름표도 함께 숨길지. */
        val hideWhenNotVisible: Boolean,
        /** 위 판정을 몇 틱마다 다시 계산할지. 인원수 제곱에 비례하는 레이캐스트라 너무 짧게 잡지 않는다. */
        val visibilityCheckTicks: Long,
    ) {
        /** 두 묶음 모두 비어 있으면 엔티티를 아예 만들지 않는다. */
        val hasAnyLine: Boolean get() = sharedLines.isNotEmpty() || othersLines.isNotEmpty()
    }

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
                inputMode = enumOrDefault(config.getString("nickname.input-mode"), InputMode.DIALOG) {
                    InputMode.valueOf(it)
                },
                lengthMode = runCatching {
                    LengthMode.valueOf(config.getString("nickname.length-mode", "CHARS")!!.uppercase())
                }.getOrDefault(LengthMode.CHARS),
                minLength = config.getInt("nickname.min-length", 2).coerceAtLeast(1),
                maxLength = config.getInt("nickname.max-length", 8).coerceAtLeast(1),
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
                costItems = readCostItems(config.getConfigurationSection("nickname.cost.item")),
            )

            val display = DisplaySettings(
                colorBleed = config.getBoolean("display.color-bleed", false),
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
                    sharedLines = config.getStringList("display.nametag.shared-lines")
                        .ifEmpty { listOf("%tf_seal%") },
                    // 예전 설정(lines)을 쓰던 서버가 조용히 이름표를 잃지 않도록 남겨둔다.
                    othersLines = config.getStringList("display.nametag.others-lines")
                        .ifEmpty { config.getStringList("display.nametag.lines") }
                        .ifEmpty { listOf("%tf_title%%tf_nickname%") },
                    othersHeightOffset = config.getDouble("display.nametag.others-height-offset", 0.4),
                    sharedHeightOffset = config.getDouble("display.nametag.shared-height-offset", 0.72),
                    viewRange = config.getDouble("display.nametag.view-range", 32.0).coerceAtLeast(1.0),
                    seeThrough = config.getBoolean("display.nametag.see-through", false),
                    textShadow = config.getBoolean("display.nametag.text-shadow", true),
                    background = config.getBoolean("display.nametag.background", false),
                    backgroundColor = config.getInt("display.nametag.background-color", 0x40000000),
                    hideVanillaNametag = config.getBoolean("display.nametag.hide-vanilla", true),
                    hideWhenNotVisible = config.getBoolean("display.nametag.hide-when-not-visible", true),
                    visibilityCheckTicks = config.getLong("display.nametag.visibility-check-ticks", 10L)
                        .coerceAtLeast(1L),
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
                placeholderIntervals = readPlaceholderIntervals(
                    config.getConfigurationSection("placeholder-refresh-intervals"),
                ),
                refreshTicks = config.getLong("display.refresh-ticks", 20L).coerceAtLeast(1L),
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

        /**
         * `nickname.cost.item` 아래의 항목들을 읽는다.
         *
         * 이름 붙인 항목을 여러 개 둘 수 있고, `enabled: true` 인 것만 실제 비용으로 쓰인다.
         * 항목이 하나도 없으면 비용 없이 닉네임을 바꿀 수 있다.
         */
        private fun readCostItems(section: ConfigurationSection?): List<CostItem> {
            if (section == null) return emptyList()
            val result = ArrayList<CostItem>()
            for (key in section.getKeys(false)) {
                val node = section.getConfigurationSection(key) ?: continue
                if (!node.getBoolean("enabled", false)) continue
                val type = when (node.getString("use-type", "vanilla")!!.lowercase()) {
                    "mmoitems", "mmo" -> ItemSourceType.MMOITEMS
                    else -> ItemSourceType.VANILLA
                }
                result += CostItem(
                    label = key,
                    type = type,
                    amount = node.getInt("amount", 1).coerceAtLeast(1),
                    material = material(node.getString("vanilla-material"), Material.PAPER),
                    displayName = node.getString("vanilla-name", "")!!,
                    mmoType = node.getString("mmoitems-type", "")!!,
                    mmoId = node.getString("mmoitems-id", "")!!,
                )
            }
            return result
        }

        /**
         * `placeholder-refresh-intervals` 를 읽는다.
         *
         * 설정에는 `'%player_x%': 50` 처럼 퍼센트를 포함해 적지만, 조회는 토큰 이름만으로
         * 하므로 여기서 퍼센트를 떼고 소문자로 맞춰 둔다.
         */
        private fun readPlaceholderIntervals(section: ConfigurationSection?): PlaceholderIntervals {
            val defaultMillis = section?.getLong("default-refresh-interval", DEFAULT_INTERVAL_MS)
                ?.coerceAtLeast(0L)
                ?: DEFAULT_INTERVAL_MS
            if (section == null) return PlaceholderIntervals(defaultMillis, emptyMap())

            val byToken = HashMap<String, Long>()
            for (key in section.getKeys(false)) {
                if (key == "default-refresh-interval") continue
                if (!section.isInt(key) && !section.isLong(key)) continue
                val token = key.trim().trim('%').lowercase()
                if (token.isEmpty()) continue
                byToken[token] = section.getLong(key).coerceAtLeast(0L)
            }
            return PlaceholderIntervals(defaultMillis, byToken)
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

        /**
         * 열거형 설정값을 읽는다. 오타를 조용히 삼키면 "설정을 바꿨는데 반영이 안 된다"는
         * 상황을 추적하기 어려우므로, 값이 있는데 해석하지 못한 경우에만 경고를 남긴다.
         */
        private inline fun <T : Enum<T>> enumOrDefault(raw: String?, fallback: T, parse: (String) -> T): T {
            if (raw.isNullOrBlank()) return fallback
            return runCatching { parse(raw.trim().uppercase()) }.getOrElse {
                Bukkit.getLogger().warning(
                    "[InMc-TitleForge] 알 수 없는 설정값 '$raw' — 기본값 ${fallback.name} 을(를) 사용합니다.",
                )
                fallback
            }
        }

        /** 토큰별 주기가 지정되지 않았을 때 쓰는 기본 재계산 간격(밀리초). */
        private const val DEFAULT_INTERVAL_MS = 500L
    }
}
