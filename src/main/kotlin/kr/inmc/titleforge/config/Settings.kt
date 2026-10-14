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
    /** bStats 익명 통계 전송 여부. */
    val metricsEnabled: Boolean,
) {

    enum class StorageType { SQLITE, MYSQL }

    enum class InputMode { DIALOG, CHAT }

    enum class LengthMode { CHARS, WIDTH }

    /** 비용 아이템 판별 방식. */
    enum class ItemSourceType { VANILLA, MMOITEMS, CUSTOM }

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
        /** CUSTOM 전용(2026-10-09). 커스텀아이템 참조 — `inmc:아이디` 또는 아이디만. */
        val customId: String = "",
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

    /**
     * 다른 플러그인 명령어에서 닉네임을 쓰게 할지.
     *
     * @param suggest 플레이어 이름을 제안하는 자리에서 닉네임이 있는 사람은 아이디 대신 닉네임을 띄운다.
     * @param resolve 닉네임으로 들어온 인자를 실행 직전에 실제 아이디로 되돌린다.
     *   꺼 두면 [suggest] 로 뜬 닉네임을 골라도 대부분의 플러그인이 대상을 찾지 못한다.
     * @param excludedCommands [resolve] 에서 제외할 명령어 이름(소문자).
     * @param resolveDialog 다른 플러그인 다이얼로그 입력칸에 닉네임만 들어 있으면 실제 아이디로 되돌린다.
     */
    class CommandBridgeSettings(
        val suggest: Boolean,
        val resolve: Boolean,
        val excludedCommands: Set<String>,
        val resolveDialog: Boolean,
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
        /** 비용을 받을 화폐 id(inmc-economy — 예: `cash`). 빈 칸이면 기본 화폐(없으면 Vault). */
        val economyCurrency: String = "",
        /** 한 번도 바꾼 적 없는 사람의 첫 변경은 비용·쿨타임 없이(테섭 2026-10-02 "원할 때 딱 1번"). */
        val firstChangeFree: Boolean = true,
        /** 비용 아이템 정의. 여러 개를 켜면 **하나만 만족해도** 지불로 인정한다. */
        val costItems: List<CostItem>,
        /** 다른 플러그인 명령어와의 연동. */
        val commandBridge: CommandBridgeSettings,
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
        /**
         * 표시 이름(displayName — CMI·랜드 채팅 등 다른 플러그인이 "이 사람 이름"으로 쓰는 것)의 포맷.
         * 비면 [nameplateFormat] 그대로. 사용자 결정 2026-10-07: 테섭은 `<nickname>` — 닉네임만.
         */
        val displayNameFormat: String,
        /**
         * 화면(상자 창) 속 플레이어 머리의 이름을 닉네임으로 바꿔 보낸다(packetevents 가 있을 때만, 사용자 결정 2026-10-07).
         * 랜드 멤버 목록처럼 다른 플러그인이 실명을 적은 화면에 — 서버의 아이템은 바뀌지 않는다.
         */
        val guiHeadNames: Boolean,
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
        /** 머리 위 말풍선(2026-10-10 — CMI 말풍선을 옮김). */
        val chatBubble: ChatBubbleSettings,
        /** 접속·퇴장·첫 접속 메시지(2026-10-10 — CMI 접속 메시지를 옮김). */
        val joinMessages: JoinMessageSettings,
    )

    /**
     * 공개 채팅을 말한 사람 머리 위에 잠깐 띄우는 말풍선.
     *
     * 시간은 밀리초로 들고 있다 — 설정은 초(소수)로 적고, 만료 판정은 표시 갱신 티커가 벽시계로 한다.
     */
    class ChatBubbleSettings(
        val enabled: Boolean,
        /** 이름표 맨 위(이름표가 꺼져 있으면 머리) 위로 얼마나 띄울지(블록). */
        val offset: Double,
        val showForMillis: Long,
        /** 글자 하나마다 더하는 시간(밀리초). 긴 말은 오래 남는다. */
        val perCharMillis: Double,
        val maxShowForMillis: Long,
        val scale: Float,
        val lineWidth: Int,
        /** 배경판 ARGB. 0 이면 투명. */
        val background: Int,
        val shadow: Boolean,
        /** 이보다 긴 말은 잘라 `…` 를 붙인다. */
        val maxLength: Int,
    ) {
        /** [chars] 글자짜리 말이 떠 있을 시간. 기본 + 글자당, 최대를 넘지 않는다. */
        fun durationMillis(chars: Int): Long =
            (showForMillis + perCharMillis * chars.coerceAtLeast(0)).toLong().coerceIn(0L, maxShowForMillis)
    }

    /**
     * 접속·퇴장 메시지. 형식이 빈 문자열이면 그 메시지를 내보내지 않는다.
     *
     * @param autoHideFrom 접속자가 이 수 이상이면 메시지를 숨긴다. -1 이면 끔(CMI `AutoHideFrom`).
     * @param filter 이름이 하나라도 걸리면 그 사람의 메시지를 숨긴다(CMI `Filter.Regex`).
     */
    class JoinMessageSettings(
        val enabled: Boolean,
        val join: String,
        val firstJoin: String,
        val quit: String,
        val kick: String,
        val autoHideFrom: Int,
        val filter: List<Regex>,
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
        /** 본체가 안 보이는 상태(관전자·투명 물약·베니시)면 이름표도 숨길지. */
        val hideWhenInvisible: Boolean,
        /**
         * `vanished` 메타데이터도 은신 판정에 쓸지.
         *
         * 이 표식은 뷰어를 구분하지 못해, 켜면 베니시를 볼 권한이 있는 관리자에게도
         * 이름표가 사라진다. 그래서 기본값이 꺼짐이다.
         */
        val vanishMetadata: Boolean,
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
                economyCurrency = config.getString("nickname.cost.economy.currency").orEmpty().trim(),
                firstChangeFree = config.getBoolean("nickname.cost.first-change-free", true),
                costItems = readCostItems(config.getConfigurationSection("nickname.cost.item")),
                commandBridge = CommandBridgeSettings(
                    suggest = config.getBoolean("nickname.command-bridge.suggest", true),
                    resolve = config.getBoolean("nickname.command-bridge.resolve", true),
                    excludedCommands = config.getStringList("nickname.command-bridge.excluded-commands")
                        .map { it.lowercase() }
                        .toSet(),
                    resolveDialog = config.getBoolean("nickname.command-bridge.resolve-dialog", true),
                ),
            )

            val display = DisplaySettings(
                colorBleed = config.getBoolean("display.color-bleed", false),
                nameplateFormat = config.getString("display.nameplate-format", "<seal><title><nickname>")!!,
                sealSuffix = config.getString("display.seal-suffix", " ")!!,
                titleSuffix = config.getString("display.title-suffix", " ")!!,
                displayName = config.getBoolean("display.display-name", true),
                displayNameFormat = config.getString("display.display-name-format", "")!!,
                guiHeadNames = config.getBoolean("display.gui-head-names", true),
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
                    hideWhenInvisible = config.getBoolean("display.nametag.hide-when-invisible", true),
                    vanishMetadata = config.getBoolean("display.nametag.vanish-metadata", false),
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
                // 테섭은 CMI 말풍선을 켜 두었다(ChatBubble.PublicMessages) — 이 절이 없는 옛 설정도 CMI 를 빼면 그대로 이어지게 기본 켬.
                chatBubble = ChatBubbleSettings(
                    enabled = config.getBoolean("display.chat-bubble.enabled", true),
                    offset = config.getDouble("display.chat-bubble.offset", 0.3),
                    showForMillis = seconds(config.getDouble("display.chat-bubble.show-for", 5.0)),
                    perCharMillis = config.getDouble("display.chat-bubble.per-char", 0.05).coerceAtLeast(0.0) * 1000.0,
                    maxShowForMillis = seconds(config.getDouble("display.chat-bubble.max-show-for", 10.0)),
                    scale = config.getDouble("display.chat-bubble.scale", 1.0).coerceIn(0.1, 10.0).toFloat(),
                    lineWidth = config.getInt("display.chat-bubble.line-width", 200).coerceIn(20, 1000),
                    background = config.getInt("display.chat-bubble.background", 0x80000000.toInt()),
                    shadow = config.getBoolean("display.chat-bubble.shadow", true),
                    maxLength = config.getInt("display.chat-bubble.max-length", 120).coerceAtLeast(1),
                ),
                joinMessages = JoinMessageSettings(
                    enabled = config.getBoolean("display.join-messages.enabled", true),
                    join = config.getString("display.join-messages.join", DEFAULT_JOIN)!!,
                    firstJoin = config.getString("display.join-messages.first-join", DEFAULT_FIRST_JOIN)!!,
                    quit = config.getString("display.join-messages.quit", DEFAULT_QUIT)!!,
                    kick = config.getString("display.join-messages.kick", DEFAULT_KICK)!!,
                    autoHideFrom = config.getInt("display.join-messages.auto-hide-from", -1),
                    filter = config.getStringList("display.join-messages.filter").mapNotNull { raw ->
                        runCatching { Regex(raw, RegexOption.IGNORE_CASE) }.getOrElse {
                            // Bukkit.getLogger 는 서버가 없으면(시험) 터진다 — 같은 이름의 JUL 로거.
                            java.util.logging.Logger.getLogger("InMc-TitleForge")
                                .warning("접속 메시지 이름 거르기 정규식 오류 '$raw' — 건너뜁니다.")
                            null
                        }
                    },
                ),
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
                metricsEnabled = config.getBoolean("metrics", true),
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
                    "custom", "customitems", "inmc" -> ItemSourceType.CUSTOM
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
                    customId = node.getString("custom-id", "")!!,
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

        /** 초(소수) → 밀리초. 음수는 0. */
        private fun seconds(value: Double): Long = (value.coerceAtLeast(0.0) * 1000.0).toLong()

        // 접속 메시지 기본 형식 — config.yml 에 이 절이 없는 옛 설정에도 쓰인다.
        private const val DEFAULT_JOIN = "<dark_gray>[<green>+</green>]</dark_gray> <nameplate><gray> 님이 접속했습니다.</gray>"
        private const val DEFAULT_FIRST_JOIN =
            "<dark_gray>[<light_purple>+</light_purple>]</dark_gray> <nameplate><gray> 님이 처음 오셨습니다! " +
                "<dark_gray>(<white><total></white>번째)</dark_gray></gray>"
        private const val DEFAULT_QUIT = "<dark_gray>[<red>-</red>]</dark_gray> <nameplate><gray> 님이 나갔습니다.</gray>"
        private const val DEFAULT_KICK = "<dark_gray>[<red>-</red>]</dark_gray> <nameplate><gray> 님이 추방되었습니다.</gray>"

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
