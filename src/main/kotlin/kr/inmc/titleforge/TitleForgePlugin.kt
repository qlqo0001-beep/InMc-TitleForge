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
import kr.inmc.titleforge.hook.MythicLibHook
import kr.inmc.titleforge.hook.PlaceholderService
import kr.inmc.titleforge.hook.VaultHook
import kr.inmc.titleforge.listener.PlayerListener
import kr.inmc.titleforge.input.ChatTextInput
import kr.inmc.titleforge.input.DialogTextInput
import kr.inmc.titleforge.nickname.NameDisplayService
import kr.inmc.titleforge.nickname.NicknameService
import kr.inmc.titleforge.player.ProfileManager
import kr.inmc.titleforge.rank.RankService
import kr.inmc.titleforge.stat.StatApplier
import kr.inmc.titleforge.stat.StatRegistry
import kr.inmc.titleforge.storage.SqlStorage
import kr.inmc.titleforge.storage.Storage
import kr.inmc.titleforge.util.Sched
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

    private lateinit var ticker: DisplayTicker

    var vault: VaultHook? = null
        private set

    /** MMOItems 아이템 식별. 없으면 null 이고 MMOItems 기반 비용 아이템은 인식되지 않는다. */
    var mmoItems: MMOItemsHook? = null
        private set

    private var autosaveTask: ScheduledTask? = null

    private companion object {
        const val STATS_FILE = "stats.yml"
    }

    override fun onEnable() {
        saveDefaultConfig()

        messages = Messages(this)
        messages.reload()
        settings = Settings.load(config)

        badges = BadgeRegistry()
        stats = StatRegistry(logger)
        stats.reload(loadStatsConfig())
        statApplier = StatApplier(logger, stats)
        statApplier.refreshDefinitions()
        profiles = ProfileManager(this)
        badgeService = BadgeService(this)
        nameDisplay = NameDisplayService(this)
        nicknames = NicknameService(this)
        dialogInput = DialogTextInput(this)
        chatInput = ChatTextInput(this)
        nametags = NametagService(this)
        tablist = TablistService(this)
        rank = RankService(this)
        placeholders = PlaceholderService(logger)
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
        autosaveTask?.cancel()
        autosaveTask = null
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
        stats.reload(loadStatsConfig())
        statApplier.refreshDefinitions()
        rank.invalidate()
        startAutosave()
        badgeService.startFlushTask()
        ticker.start()
        tokens.clear()
        nametags.refreshAll()
        tablist.clear()
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

    private fun setupStorage(): Boolean = runCatching {
        val sql = SqlStorage(settings.storage, dataFolder, logger)
        sql.init()
        storage = sql
        // 기동 시 1회만 동기 로드한다. 이후 모든 접근은 비동기.
        badges.replaceAll(sql.loadBadges())
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
    }

    private fun registerCommands() {
        val executor = TitleForgeCommand(this)
        val command = getCommand("it")
        if (command == null) {
            logger.severe("plugin.yml 에 /it 명령어가 정의되어 있지 않습니다.")
            return
        }
        command.setExecutor(executor)
        command.tabCompleter = executor
    }

    private fun setupHooks() {
        // 클래스 로딩 자체를 막기 위해 존재 확인 후에만 훅을 건드린다 (맞춤 지침 7.3-12).
        if (server.pluginManager.getPlugin("Vault") != null) {
            vault = runCatching { VaultHook.setup() }.getOrNull()
            if (vault != null) logger.info("Vault 연동 활성화")
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
