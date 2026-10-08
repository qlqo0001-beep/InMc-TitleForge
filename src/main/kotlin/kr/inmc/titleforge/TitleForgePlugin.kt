package kr.inmc.titleforge

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.titleforge.badge.BadgeRegistry
import kr.inmc.titleforge.badge.BadgeService
import kr.inmc.titleforge.command.TitleForgeCommand
import kr.inmc.titleforge.config.Messages
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.display.DisplayTicker
import kr.inmc.titleforge.display.NametagService
import kr.inmc.titleforge.display.TablistService
import kr.inmc.titleforge.display.TokenRenderer
import kr.inmc.titleforge.gui.Menu
import kr.inmc.titleforge.hook.MMOItemsHook
import kr.inmc.titleforge.hook.MetricsHook
import kr.inmc.titleforge.hook.MythicLibHook
import kr.inmc.titleforge.hook.PlaceholderService
import kr.inmc.core.integration.EconomyHook
import kr.inmc.titleforge.listener.PlayerListener
import kr.inmc.titleforge.input.ChatTextInput
import kr.inmc.titleforge.input.DialogTextInput
import kr.inmc.titleforge.nickname.NameDisplayService
import kr.inmc.titleforge.nickname.NicknameCommandBridge
import kr.inmc.titleforge.nickname.NicknameDialogBridge
import kr.inmc.titleforge.nickname.NicknameIndex
import kr.inmc.titleforge.nickname.NicknameService
import kr.inmc.titleforge.place.PlaceNames
import kr.inmc.titleforge.place.PlaceTracker
import kr.inmc.titleforge.player.ProfileManager
import kr.inmc.titleforge.rank.RankService
import kr.inmc.titleforge.stat.StatApplier
import kr.inmc.titleforge.stat.StatRegistry
import kr.inmc.titleforge.storage.SqlStorage
import kr.inmc.titleforge.storage.Storage
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class TitleForgePlugin : JavaPlugin() {

    lateinit var settings: Settings
        private set

    lateinit var messages: Messages
        private set

    lateinit var storage: Storage
        private set

    lateinit var badges: BadgeRegistry
        private set

    /** 스텟 정의 레지스트리 (바닐라 내장 + stats.yml). */
    lateinit var stats: StatRegistry
        private set

    lateinit var profiles: ProfileManager
        private set

    lateinit var statApplier: StatApplier
        private set

    lateinit var badgeService: BadgeService
        private set

    lateinit var nameDisplay: NameDisplayService
        private set

    lateinit var nicknames: NicknameService
        private set

    /** 접속자 "아이디 ↔ 닉네임" 색인. 명령어 탭 완성·인자 치환이 이것만 읽는다. */
    lateinit var nicknameIndex: NicknameIndex
        private set

    /** 닉네임이 있는 모든 사람(오프라인 포함) — 화면 속 머리 이름 바꾸기가 패킷 스레드에서 읽는다. */
    val nicknameDirectory = kr.inmc.titleforge.nickname.NicknameDirectory()

    /** packetevents 가 있을 때 붙는 화면 속 머리 이름 바꾸기(`hook/GuiHeadNames`). 타입을 적지 않는다 — 클래스를 미리 읽지 않게(규칙 12). */
    private var guiHeadNames: AutoCloseable? = null

    lateinit var dialogInput: DialogTextInput
        private set

    lateinit var chatInput: ChatTextInput
        private set

    lateinit var nametags: NametagService
        private set

    lateinit var tablist: TablistService
        private set

    /** 이름표·탭리스트의 `%토큰%` 치환기. 재계산 주기 캐시를 들고 있다. */
    lateinit var tokens: TokenRenderer
        private set

    lateinit var rank: RankService
        private set

    lateinit var placeholders: PlaceholderService
        private set

    /** 월드·생물군계를 보여줄 이름 (places.yml). */
    lateinit var places: PlaceNames
        private set

    /** 플레이어가 지금 있는 월드·생물군계. 플레이스홀더가 비동기 스레드에서도 읽는다. */
    lateinit var placeTracker: PlaceTracker
        private set

    /** places.yml 쓰기 순서. 워커 둘이 엇갈려 옛 내용이 새 내용을 덮지 않게 한다. */
    private val placesVersion = java.util.concurrent.atomic.AtomicLong()
    private var placesWritten = 0L
    private val placesLock = Any()

    private lateinit var commandBridge: NicknameCommandBridge

    private lateinit var ticker: DisplayTicker

    /**
     * Vault 경제. core 의 훅을 그대로 쓴다 - 없으면 isEnabled 가 false 다.
     *
     * 다른 서비스들과 같이 onEnable 에서 만든다. 필드 초기화 시점에 logger 를 읽는 것은
     * JavaPlugin 의 생성 순서에 기대는 일이라 이 파일의 관례를 따랐다.
     */
    lateinit var economy: EconomyHook
        private set

    /** MMOItems 아이템 식별. 없으면 null 이고 MMOItems 기반 비용 아이템은 인식되지 않는다. */
    var mmoItems: MMOItemsHook? = null
        private set

    private var autosaveTask: ScheduledTask? = null

    private var metrics: MetricsHook? = null

    private companion object {
        const val STATS_FILE = "stats.yml"
        const val PLACES_FILE = "places.yml"
    }

    override fun onEnable() {
        saveDefaultConfig()

        messages = Messages(this)
        messages.reload()
        settings = Settings.load(config)
        refreshMessageGlobals()

        badges = BadgeRegistry()
        stats = StatRegistry(logger)
        stats.reload(loadStatsConfig())
        places = PlaceNames()
        loadPlaces()
        placeTracker = PlaceTracker(this)
        statApplier = StatApplier(logger, stats)
        statApplier.refreshDefinitions()
        profiles = ProfileManager(this)
        badgeService = BadgeService(this)
        nameDisplay = NameDisplayService(this)
        nicknames = NicknameService(this)
        nicknameIndex = NicknameIndex(this)
        commandBridge = NicknameCommandBridge(this)
        dialogInput = DialogTextInput(this)
        chatInput = ChatTextInput(this)
        nametags = NametagService(this)
        tablist = TablistService(this)
        rank = RankService(this)
        placeholders = PlaceholderService(logger)
        economy = EconomyHook(logger)
        tokens = TokenRenderer(this)
        ticker = DisplayTicker(this)

        if (!setupStorage()) {
            logger.severe("저장소 초기화에 실패해 플러그인을 비활성화합니다.")
            server.pluginManager.disablePlugin(this)
            return
        }

        registerListeners()
        registerCommands()
        setupHooks()
        startAutosave()
        badgeService.startFlushTask()
        nametags.cleanupOrphans()
        ticker.start()

        // 다른 훅이 다 붙은 뒤에 시작해야 연동 사용 여부가 정확히 잡힌다.
        metrics = MetricsHook(this).also { it.start() }

        // 리로드 후 이미 접속해 있는 플레이어 복구
        for (player in Bukkit.getOnlinePlayers()) {
            Sched.async(this) {
                profiles.loadOnPreLogin(player.uniqueId, player.name)
                Sched.entity(this, player) {
                    profiles.cached(player.uniqueId)?.let { statApplier.apply(player, it.totalStats) }
                    nameDisplay.refresh(player)
                }
            }
        }

        logger.info("InMc-TitleForge 활성화 완료 (칭호 ${badges.total()}개)")
    }

    override fun onDisable() {
        metrics?.stop()
        metrics = null
        runCatching { guiHeadNames?.close() }
        guiHeadNames = null
        autosaveTask?.cancel()
        autosaveTask = null
        kr.inmc.core.integration.PlayerSettings.unlisten(kr.inmc.titleforge.display.TitleForgeSettings.OWNER)
        kr.inmc.titleforge.display.TitleForgeSettings.unregister()
        if (::ticker.isInitialized) ticker.stop()
        if (::badgeService.isInitialized) badgeService.stopFlushTask()
        if (::nametags.isInitialized) nametags.removeAll()

        // isEnabled 는 onDisable 진입 전에 이미 false 라 Sched.async 가 동작하지 않는다.
        // 대기 중인 정의·프로필을 저장소가 닫히기 전에 직접 내보낸다.
        if (::badgeService.isInitialized) runCatching { badgeService.flushPending() }
        if (::profiles.isInitialized) profiles.flushBlocking()
        if (::storage.isInitialized) runCatching { storage.close() }
    }

    /** config.yml / messages.yml 을 다시 읽어 스냅샷을 교체한다. */
    fun reloadSettings() {
        reloadConfig()
        settings = Settings.load(config)
        messages.reload()
        refreshMessageGlobals()
        stats.reload(loadStatsConfig())
        loadPlaces()
        statApplier.refreshDefinitions()
        rank.invalidate()
        startAutosave()
        badgeService.startFlushTask()
        ticker.start()
        tokens.clear()
        nametags.refreshAll()
        tablist.clear()
        // 제외 명령어 목록이 바뀌었을 수 있으니 "여기가 플레이어 이름 칸" 기억도 버린다.
        commandBridge.clear()
    }

    /**
     * 연동용 안정 진입점 — 타 inmc 플러그인이 표시용 이름을 리플렉션으로 읽는다.
     * 시그니처를 바꾸면 양쪽 CHANGELOG 에 적는다. 닉네임이 없거나 본인이 표시를 끄면 null.
     */
    fun displayNameOf(uuid: java.util.UUID): String? =
        kr.inmc.titleforge.api.TitleForgeApi.displayNameOf(uuid)

    /**
     * 연동용 안정 진입점 — 닉네임으로 사람의 uuid(오프라인 포함, 2026-10-07 inmc-discord `/정보`). 비교는 닉네임 정규화 규칙 그대로,
     * 두 명 이상이 같은 닉네임이면 null. 맵만 본다(DB 없음) — 아무 스레드에서나. 시그니처를 바꾸면 양쪽 CHANGELOG 에 적는다.
     */
    fun uuidOfNickname(nickname: String): java.util.UUID? = nicknameDirectory.uuidOf(nickname)

    /**
     * messages.yml 어디에서나 쓸 수 있는 공용 토큰을 갱신한다.
     *
     * 호출부가 넘기지 않아도 되므로, 예를 들어 `nickname.prompt-chat` 에
     * `<allowed>` 를 넣어 허용 문자를 안내할 수 있다.
     * 값은 설정 스냅샷에서 오므로 reload 때만 다시 만든다.
     *
     * **토큰을 추가할 때는 README 의 표도 함께 갱신할 것.**
     */
    private fun refreshMessageGlobals() {
        val nickname = settings.nickname
        messages.setGlobals(
            mapOf(
                // 닉네임 허용 문자 안내. 예: "한글(완성형), 영문, 숫자, _-"
                "allowed" to nickname.allowedDescription,
                "nick_min" to nickname.minLength,
                "nick_max" to nickname.maxLength,
                "nick_cooldown" to Text.duration(nickname.cooldownSeconds),
            ),
        )
    }

    /** stats.yml 을 읽는다. 없으면 기본 파일을 깔아준다. */
    private fun loadStatsConfig(): org.bukkit.configuration.ConfigurationSection? {
        val file = java.io.File(dataFolder, STATS_FILE)
        if (!file.exists()) saveResource(STATS_FILE, false)
        return runCatching {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        }.getOrElse {
            logger.severe("stats.yml 을 읽지 못했습니다 (${it.message}). 바닐라 스텟만 사용합니다.")
            null
        }
    }

    /**
     * places.yml 을 읽는다. 없으면 기본 파일(월드·생물군계 한글 이름)을 깔아준다. 못 읽으면 지금 이름을 그대로 둔다.
     *
     * 이미 깔린 파일에도 **기본값에 새로 생긴 이름을 더해 다시 쓴다** — 업데이트로 늘어난 생물군계(새 데이터팩 버전·
     * 새 바닐라 생물군계)가 관리자가 손대지 않아도 들어온다. 고친 것·지운 것은 그대로다.
     */
    private fun loadPlaces() {
        val file = java.io.File(dataFolder, PLACES_FILE)
        if (!file.exists()) saveResource(PLACES_FILE, false)
        val defaults = getResource(PLACES_FILE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        runCatching { places.load(file.readText(Charsets.UTF_8), defaults) }
            .onSuccess { added ->
                if (added > 0) {
                    logger.info("places.yml 에 새 기본 이름 ${added}개를 더했습니다.")
                    savePlaces()
                }
            }
            .onFailure { logger.severe("places.yml 을 읽지 못했습니다 (${it.message}). 이전 이름을 그대로 씁니다.") }
    }

    /**
     * GUI 로 고친 장소 이름을 쓴다. 내용은 여기(메인)서 뜨고 파일은 워커에서 쓴 뒤 바꿔 끼운다 —
     * 쓰는 도중에 죽어도 옛 파일이 남는다.
     */
    fun savePlaces() {
        val text = places.save()
        val version = placesVersion.incrementAndGet()
        Sched.async(this) {
            synchronized(placesLock) {
                if (version < placesWritten) return@async
                runCatching {
                    val file = java.io.File(dataFolder, PLACES_FILE)
                    val temp = java.io.File(dataFolder, "$PLACES_FILE.tmp")
                    temp.writeText(text, Charsets.UTF_8)
                    java.nio.file.Files.move(
                        temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                    placesWritten = version
                }.onFailure { logger.warning("places.yml 을 저장하지 못했습니다: ${it.message}") }
            }
        }
    }

    private fun setupStorage(): Boolean = runCatching {
        val sql = SqlStorage(settings.storage, dataFolder, logger)
        sql.init()
        storage = sql
        // 기동 시 1회만 동기 로드한다. 이후 모든 접근은 비동기.
        badges.replaceAll(sql.loadBadges())
        // 화면 속 머리 이름 바꾸기용 "모든 사람의 닉네임" — 메인을 막지 않게 워커에서(규칙 1).
        Sched.async(this) {
            runCatching { nicknameDirectory.fill(sql.loadNicknames()) }
                .onFailure { logger.warning("닉네임 목록을 읽지 못했습니다(화면 속 머리 이름은 접속자만 바뀝니다): ${it.message}") }
        }
        true
    }.getOrElse {
        logger.severe("저장소 오류: ${it.message}")
        it.printStackTrace()
        false
    }

    private fun registerListeners() {
        val pm = server.pluginManager
        pm.registerEvents(PlayerListener(this), this)
        pm.registerEvents(Menu.MenuListener(), this)
        pm.registerEvents(nameDisplay, this)
        pm.registerEvents(chatInput, this)
        pm.registerEvents(commandBridge, this)
        pm.registerEvents(NicknameDialogBridge(this), this)
        pm.registerEvents(placeTracker, this)
    }

    private fun registerCommands() {
        TitleForgeCommand(this).register(this)
    }

    private fun setupHooks() {
        // 클래스 로딩 자체를 막기 위해 존재 확인 후에만 훅을 건드린다 (맞춤 지침 7.3-12).
        // EconomyHook.setup() 이 그 확인을 안에서 하고 로그까지 남긴다.
        economy.setup()

        // 개인 설정(닉네임·칭호·인장 표시, 타인 인장 끄기) — 플레이어 메뉴 화면에 보인다.
        kr.inmc.titleforge.display.TitleForgeSettings.register()
        kr.inmc.core.integration.PlayerSettings.listen(kr.inmc.titleforge.display.TitleForgeSettings.OWNER) { player, key ->
            when (key) {
                kr.inmc.titleforge.display.TitleForgeSettings.HIDE_OTHERS_SEAL -> nametags.refreshSealVisibility(player)
                else -> nametags.refreshSelfView(player)
            }
        }

        // 화면 속 머리 이름 — packetevents 가 켜진 뒤(첫 틱)에 붙는다. 켜고 끄기는 설정(`display.gui-head-names`)이 패킷마다 본다.
        if (server.pluginManager.getPlugin("packetevents") != null) {
            Sched.global(this) {
                runCatching {
                    guiHeadNames = kr.inmc.titleforge.hook.GuiHeadNames(this)
                    logger.info("packetevents 연동 활성화 (화면 속 플레이어 머리 이름 → 닉네임)")
                }.onFailure { logger.warning("packetevents 연동 실패: ${it.message}") }
            }
        }

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            runCatching {
                kr.inmc.titleforge.hook.PlaceholderHook(this).register()
                logger.info("PlaceholderAPI 연동 활성화 (%titleforge_...%)")
            }.onFailure { logger.warning("PlaceholderAPI 연동 실패: ${it.message}") }
        }
        placeholders.setup()

        // MMOItems 비용 아이템을 쓰는 설정이 있으면 아이템 식별 훅을 준비한다.
        // 값이 바닐라 NBT 에 들어 있어 MMOItems 가 없어도 읽기 자체는 가능하다.
        val mmoCostItems = settings.nickname.costItems
            .filter { it.type == Settings.ItemSourceType.MMOITEMS }
        if (mmoCostItems.isNotEmpty()) {
            val hook = MMOItemsHook.setup(logger)
            mmoItems = hook
            logger.info("MMOItems 비용 아이템 ${mmoCostItems.size}종 (읽기 경로: ${hook.mode})")
            if (server.pluginManager.getPlugin("MMOItems") == null) {
                logger.warning("MMOItems 플러그인이 설치되어 있지 않습니다. 해당 비용 아이템은 획득할 수 없습니다.")
            }
        }

        // MMOItems(MythicLib). 없으면 바닐라 스텟만으로 정상 동작한다.
        val mmoStats = stats.ofKind(kr.inmc.titleforge.stat.StatKind.MMO).size
        if (mmoStats > 0) {
            val hook = MythicLibHook.setup(logger)
            statApplier.mythicLib = hook
            if (hook != null) {
                logger.info("MMOItems 스텟 연동 활성화 (${mmoStats}종)")
            } else {
                logger.info("MMOItems 미연동 — MMO 스텟 ${mmoStats}종은 값만 보관됩니다.")
            }
        }
    }

    private fun startAutosave() {
        autosaveTask?.cancel()
        autosaveTask = null
        val period = settings.storage.autosaveSeconds
        if (period <= 0L) return
        autosaveTask = Sched.asyncTimer(this, period, period) {
            profiles.saveAllDirty()
            profiles.evictExpired()
        }
    }
}
