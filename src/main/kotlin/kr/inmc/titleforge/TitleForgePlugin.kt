package kr.inmc.titleforge

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.titleforge.badge.BadgeRegistry
import kr.inmc.titleforge.badge.BadgeService
import kr.inmc.titleforge.command.TitleForgeCommand
import kr.inmc.titleforge.config.Messages
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.gui.Menu
import kr.inmc.titleforge.hook.VaultHook
import kr.inmc.titleforge.listener.PlayerListener
import kr.inmc.titleforge.nickname.AnvilNicknameInput
import kr.inmc.titleforge.nickname.ChatNicknameInput
import kr.inmc.titleforge.nickname.NameDisplayService
import kr.inmc.titleforge.nickname.NicknameService
import kr.inmc.titleforge.player.ProfileManager
import kr.inmc.titleforge.stat.StatApplier
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

    lateinit var anvilInput: AnvilNicknameInput
        private set

    lateinit var chatInput: ChatNicknameInput
        private set

    var vault: VaultHook? = null
        private set

    private var autosaveTask: ScheduledTask? = null

    override fun onEnable() {
        saveDefaultConfig()

        messages = Messages(this)
        messages.reload()
        settings = Settings.load(config)

        badges = BadgeRegistry()
        statApplier = StatApplier(logger)
        profiles = ProfileManager(this)
        badgeService = BadgeService(this)
        nameDisplay = NameDisplayService(this)
        nicknames = NicknameService(this)
        anvilInput = AnvilNicknameInput(this)
        chatInput = ChatNicknameInput(this)

        if (!setupStorage()) {
            logger.severe("저장소 초기화에 실패해 플러그인을 비활성화합니다.")
            server.pluginManager.disablePlugin(this)
            return
        }

        registerListeners()
        registerCommands()
        setupHooks()
        startAutosave()

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

        if (::profiles.isInitialized) profiles.flushBlocking()
        if (::storage.isInitialized) runCatching { storage.close() }
    }

    /** config.yml / messages.yml 을 다시 읽어 스냅샷을 교체한다. */
    fun reloadSettings() {
        reloadConfig()
        settings = Settings.load(config)
        messages.reload()
        startAutosave()
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
        pm.registerEvents(anvilInput, this)
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
