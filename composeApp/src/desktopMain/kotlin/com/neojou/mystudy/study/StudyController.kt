package com.neojou.mystudy.study

import com.neojou.mystudy.wiki.agent.AgentAction
import com.neojou.mystudy.wiki.agent.AgentEvent
import com.neojou.mystudy.wiki.agent.ChatAgent
import com.neojou.mystudy.wiki.agent.ChatAgentResult
import com.neojou.mystudy.wiki.agent.ChatReference
import com.neojou.mystudy.wiki.agent.ChatSession
import com.neojou.mystudy.wiki.agent.ToolObservation
import com.neojou.mystudy.wiki.agent.WikiTools
import com.neojou.mystudy.wiki.ask.AskPipeline
import com.neojou.mystudy.wiki.ask.AskResult
import com.neojou.mystudy.wiki.ask.PacketEdge
import com.neojou.mystudy.wiki.ask.PacketPage
import com.neojou.mystudy.wiki.ask.browseHits
import com.neojou.mystudy.wiki.ask.packetEdges
import com.neojou.mystudy.wiki.ask.proposeQueryFile
import com.neojou.mystudy.wiki.ask.schemaExcerpt
import com.neojou.mystudy.wiki.index.NoteSummary
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.index.WikiWatcher
import com.neojou.mystudy.wiki.index.indexDatabasePath
import com.neojou.mystudy.wiki.ingest.IngestInput
import com.neojou.mystudy.wiki.ingest.IngestPipeline
import com.neojou.mystudy.wiki.ingest.IngestResult
import com.neojou.mystudy.wiki.ingest.vaultRelative
import com.neojou.mystudy.wiki.lint.LintFinding
import com.neojou.mystudy.wiki.lint.LintPage
import com.neojou.mystudy.wiki.lint.LintConfig
import com.neojou.mystudy.wiki.lint.LintProgress
import com.neojou.mystudy.wiki.lint.appendLintReviews
import com.neojou.mystudy.wiki.lint.formatSemantic
import com.neojou.mystudy.wiki.lint.formatStructural
import com.neojou.mystudy.wiki.lint.lintSnapshot
import com.neojou.mystudy.wiki.lint.markExisting
import com.neojou.mystudy.wiki.lint.mergeLintFindings
import com.neojou.mystudy.wiki.lint.parseIgnoreList
import com.neojou.mystudy.wiki.lint.runSemanticLint
import com.neojou.mystudy.wiki.lint.runStructuralLint
import com.neojou.mystudy.wiki.lint.toReviewProposals
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.OllamaClient
import com.neojou.mystudy.wiki.prompt.locateAiPrompts
import com.neojou.mystudy.wiki.research.ResearchPhase
import com.neojou.mystudy.wiki.research.ResearchResult
import com.neojou.mystudy.wiki.research.ResearchUpdate
import com.neojou.mystudy.wiki.research.ResearchRunner
import com.neojou.mystudy.wiki.research.ReviewEntry
import com.neojou.mystudy.wiki.research.SearxngClient
import com.neojou.mystudy.wiki.research.SearxngHit
import com.neojou.mystudy.wiki.research.SearxngStatus
import com.neojou.mystudy.wiki.research.parseReviewFile
import com.neojou.mystudy.wiki.research.researchBlockReason
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.IngestMode
import com.neojou.mystudy.wiki.settings.SettingsStore
import com.neojou.mystudy.wiki.settings.StudySettings
import com.neojou.mystudy.wiki.settings.ingestLinkMessage
import com.neojou.mystudy.wiki.vault.Discovery
import com.neojou.mystudy.wiki.vault.applyProposal
import com.neojou.mystudy.wiki.vault.discoverWikiRoot
import com.neojou.mystudy.wiki.vault.ingestTargets
import com.neojou.mystudy.wiki.vault.linkIntoRawSource
import com.neojou.mystudy.wiki.vault.EditPermission
import com.neojou.mystudy.wiki.vault.LoadedNote
import com.neojou.mystudy.wiki.vault.SaveOutcome
import com.neojou.mystudy.wiki.vault.loadNote
import com.neojou.mystudy.wiki.vault.noteFileSha256
import com.neojou.mystudy.wiki.vault.readTextIfExists
import com.neojou.mystudy.wiki.vault.saveNote as writeNote
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.neojou.mystudy.wiki.vault.wikiIndexRelative
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isDirectory

data class ChatBubble(
    val question: String,
    val steps: List<String>,
    val answer: String,
)

/**
 * Vault, index, and Ollama state for the study panes.
 * SQLite is used only on [dbDispatcher].
 */
class StudyController(
    settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val appSettings = AppSettings(settingsStore)
    private val dbDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wiki-index").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    var settings by mutableStateOf(appSettings.load())
        private set
    var status by mutableStateOf("Opening the vault…")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var noteCount by mutableStateOf(0)
        private set
    var wikiRoot by mutableStateOf<String?>(null)
        private set
    var showCreate by mutableStateOf(false)
        private set
    var rootChoices by mutableStateOf<List<String>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var treeTick by mutableStateOf(0)
        private set

    var summaries by mutableStateOf<List<NoteSummary>>(emptyList())
        private set
    var searchHits by mutableStateOf<List<NoteSummary>>(emptyList())
        private set
    var openNote by mutableStateOf("")
        private set

    var previewPath by mutableStateOf<Path?>(null)
        private set
    var previewTitle by mutableStateOf("")
        private set
    var previewBody by mutableStateOf("")
        private set
    var noteDraft by mutableStateOf("")
        private set
    var noteMode by mutableStateOf(NoteViewMode.Reading)
        private set
    var notePhase by mutableStateOf<NotePhase>(NotePhase.Clean)
        private set
    var notePending by mutableStateOf<PendingNav?>(null)
        private set
    var noteDirty by mutableStateOf(false)
        private set
    var noteCanEdit by mutableStateOf(false)
        private set
    var noteWarning by mutableStateOf<String?>(null)
        private set
    var noteExternal by mutableStateOf(false)
        private set
    var noteMixed by mutableStateOf(false)
        private set
    var noteStatus by mutableStateOf("")
        private set
    var noteUnreadable by mutableStateOf(false)
        private set
    var noteLoading by mutableStateOf(false)
        private set
    var noteNotice by mutableStateOf("")
        private set
    var editorEpoch by mutableStateOf(0)
        private set
    var approvedMode by mutableStateOf<StudyMode?>(null)
        private set
    var noteClipboard by mutableStateOf<String?>(null)
        private set

    var ingestMessage by mutableStateOf("")
        private set

    var question by mutableStateOf("")
    var answer by mutableStateOf("")
        private set
    var askSources by mutableStateOf<List<String>>(emptyList())
        private set
    var askPages by mutableStateOf<List<PacketPage>>(emptyList())
        private set
    var askEdges by mutableStateOf<List<PacketEdge>>(emptyList())
        private set
    var tokenHits by mutableStateOf(0)
        private set
    var graphHits by mutableStateOf(0)
        private set
    var askReported by mutableStateOf(false)
        private set
    var askMessage by mutableStateOf("")
        private set
    var askGraphOpen by mutableStateOf(false)
        private set
    var askReaderPath by mutableStateOf<String?>(null)
        private set
    var askReaderTitle by mutableStateOf("")
        private set
    var askReaderBody by mutableStateOf("")
        private set

    var chatInput by mutableStateOf("")
    var chatHistory by mutableStateOf<List<ChatBubble>>(emptyList())
        private set
    var liveQuestion by mutableStateOf("")
        private set
    var liveSteps by mutableStateOf<List<String>>(emptyList())
        private set
    var liveAnswer by mutableStateOf("")
        private set
    var chatShown by mutableStateOf<List<ChatReference>>(emptyList())
        private set
    var chatPages by mutableStateOf<List<PacketPage>>(emptyList())
        private set
    var chatEdges by mutableStateOf<List<PacketEdge>>(emptyList())
        private set
    var chatMessage by mutableStateOf("")
        private set
    var chatGraphOpen by mutableStateOf(false)
        private set
    var chatReaderPath by mutableStateOf<String?>(null)
        private set
    var chatReaderTitle by mutableStateOf("")
        private set
    var chatReaderBody by mutableStateOf("")
        private set

    var proposal by mutableStateOf<DraftProposal?>(null)
        private set

    var searxngStatus by mutableStateOf<SearxngStatus>(SearxngStatus.Unknown)
        private set
    var researchPhase by mutableStateOf(ResearchPhase.Idle)
        private set
    var researchHits by mutableStateOf<List<SearxngHit>>(emptyList())
        private set
    var researchText by mutableStateOf("")
        private set
    var researchMessage by mutableStateOf("")
        private set
    var researchPage by mutableStateOf<String?>(null)
        private set
    var reviews by mutableStateOf<List<ReviewEntry>>(emptyList())
        private set

    var lintFindings by mutableStateOf<List<LintFinding>>(emptyList())
        private set
    var lintPages by mutableStateOf<List<LintPage>>(emptyList())
        private set
    var lintWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var lintChecked by mutableStateOf<Set<String>>(emptySet())
        private set
    var lintStructuralText by mutableStateOf("")
        private set
    var lintSemanticText by mutableStateOf("")
        private set
    var lintMessage by mutableStateOf("")
        private set
    var lintRunning by mutableStateOf(false)
        private set
    var lintIgnoreText by mutableStateOf("")
        private set

    var pendingMode by mutableStateOf<StudyMode?>(null)
        private set
    @Volatile private var index: WikiIndex? = null
    private val chatSession = ChatSession()
    private var chatJob: Job? = null
    private var chatGeneration = 0
    private var ingestJob: Job? = null
    private var ingestGeneration = 0
    private var researchJob: Job? = null
    private var researchGeneration = 0
    private var lintJob: Job? = null
    private var lintGeneration = 0
    private val lintStop = AtomicBoolean(false)
    private val researchGate = AtomicInteger(0)
    private var researchTarget: ReviewEntry? = null
    private var searxngGeneration = 0
    private var chatQuestion = ""
    private var chatAnswer = ""
    private var chatSources = emptyList<String>()
    private var watcher: WikiWatcher? = null
    private var debounce: Job? = null
    private var generation = 0
    private val noteSession = NoteSession()
    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null
    private var loadToken = 0
    private var saveEpoch = 0
    private var closeAction: (() -> Unit)? = null

    init {
        refresh(honorSaved = true)
        testSearxng()
    }

    fun consumeMode(): StudyMode? {
        val mode = pendingMode
        pendingMode = null
        return mode
    }

    fun offerCreate() {
        if (Path.of(settings.vaultPath).isDirectory()) showCreate = true
    }

    fun dismissCreate() {
        showCreate = false
    }

    fun dismissChoices() {
        rootChoices = emptyList()
    }

    fun dismissProposal() {
        proposal = null
    }

    fun clearError() {
        error = null
    }

    fun refresh(honorSaved: Boolean) {
        val gen = ++generation
        stopWatcher()
        scope.launch {
            try {
                val loaded = settings
                val discovery = withContext(Dispatchers.IO) {
                    val saved = loaded.wikiRootPath.takeIf { it.isNotBlank() }?.let(Path::of)
                    discoverWikiRoot(Path.of(loaded.vaultPath), saved, honorSaved)
                }
                if (gen != generation) return@launch
                when (discovery) {
                    Discovery.VaultMissing -> showMissing(loaded.vaultPath)
                    Discovery.None -> showNone()
                    is Discovery.One -> loadRoot(discovery.root, gen)
                    is Discovery.Many -> {
                        closeIndex()
                        wikiRoot = null
                        resetNote()
                        rootChoices = discovery.roots.map { it.toString() }
                        status = "Several wiki roots found. Choose one."
                    }
                }
            } catch (caught: Exception) {
                error = caught.message ?: "Could not open the vault."
            }
        }
    }

    fun createWiki() {
        scope.launch {
            try {
                val root = withContext(Dispatchers.IO) { scaffoldWiki(Path.of(settings.vaultPath)) }
                persistRoot(root.toString())
                showCreate = false
                treeTick += 1
                refresh(honorSaved = true)
            } catch (caught: Exception) {
                error = caught.message ?: "Could not create the wiki."
            }
        }
    }

    fun chooseRoot(path: String) {
        persistRoot(path)
        rootChoices = emptyList()
        refresh(honorSaved = true)
    }

    fun saveSettings(next: StudySettings) {
        val stored = try {
            appSettings.save(next)
        } catch (caught: IllegalArgumentException) {
            error = caught.message
            return
        }
        settings = stored
        refresh(honorSaved = true)
        testSearxng()
    }

    fun redetect() {
        persistRoot("")
        refresh(honorSaved = false)
    }

    fun rebuildIndex() {
        val root = wikiRoot ?: return
        val gen = ++generation
        stopWatcher()
        scope.launch {
            busy = true
            try {
                val snapshot = withContext(dbDispatcher) {
                    if (gen != generation) return@withContext null
                    index?.close()
                    index = null
                    val database = indexDatabasePath(Path.of(root))
                    Files.deleteIfExists(database)
                    Files.deleteIfExists(Path.of("$database-wal"))
                    Files.deleteIfExists(Path.of("$database-shm"))
                    if (gen != generation) return@withContext null
                    val created = WikiIndex.open(Path.of(root), database)
                    created.reconcileAll()
                    if (gen != generation) {
                        created.close()
                        return@withContext null
                    }
                    index = created
                    created.noteCount() to created.listSummaries()
                } ?: return@launch
                noteCount = snapshot.first
                summaries = snapshot.second
                searchHits = snapshot.second
                status = "Index rebuilt."
                startWatcher(Path.of(root), gen)
            } catch (caught: Exception) {
                error = caught.message ?: "Could not rebuild the index."
            } finally {
                busy = false
            }
        }
    }

    fun checkOllama() {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    client().ping()
                } catch (caught: Exception) {
                    caught.message ?: "Ollama is not reachable."
                }
            }
            status = message
            error = if (message == "Ollama is reachable.") null else message
        }
    }

    fun search(query: String) {
        scope.launch {
            val hits = withContext(dbDispatcher) {
                val current = index ?: return@withContext emptyList()
                if (query.isBlank()) return@withContext current.listSummaries()
                browseHits(current, query)
            }
            searchHits = hits
        }
    }

    fun openIndexed(path: String) {
        scope.launch {
            val body = withContext(dbDispatcher) { index?.load(path)?.body.orEmpty() }
            openNote = body
            previewTitle = path
        }
    }

    fun showAskGraph() {
        if (askPages.isNotEmpty()) askGraphOpen = true
    }

    fun closeAskGraph() {
        askGraphOpen = false
    }

    fun closeAskReader() {
        askReaderPath = null
        askReaderTitle = ""
        askReaderBody = ""
    }

    fun openAskPage(path: String) {
        val page = askPages.firstOrNull { it.path == path }
        askReaderPath = path
        askReaderTitle = page?.title?.ifBlank { path } ?: path
        scope.launch {
            val body = withContext(dbDispatcher) { index?.load(path)?.body.orEmpty() }
            if (askReaderPath == path) askReaderBody = body
        }
    }

    fun requestOpen(path: Path) {
        if (sameNotePath(previewPath, path)) return
        if (noteSession.requestLeave(PendingNav.OpenFile(path))) {
            publishNote()
            return
        }
        previewFile(path)
    }

    fun previewFile(path: Path) {
        val cancelIngest = ingestJob?.isActive == true
        ingestGeneration += 1
        ingestJob?.cancel()
        if (cancelIngest) busy = false
        proposal = null
        val token = ++loadToken
        noteSession.generation = token
        editorEpoch++
        noteLoading = true
        noteNotice = ""
        autosaveJob?.cancel()
        previewPath = path
        previewTitle = path.fileName?.toString() ?: path.toString()
        previewBody = ""
        ingestMessage = ""
        noteSession.path = path
        noteSession.draft = ""
        noteSession.loadedText = ""
        noteSession.base = null
        noteSession.permission = EditPermission.ReadOnly("")
        noteSession.mode = NoteViewMode.Reading
        noteSession.phase = NotePhase.Clean
        noteSession.externalChange = false
        noteSession.pendingNav = null
        pendingMode = StudyMode.Home
        publishNote()
        val vault = Path.of(settings.vaultPath)
        val root = wikiRoot?.let(Path::of)
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { loadNote(vault, root, path) }
            } catch (caught: Exception) {
                LoadedNote.Unreadable(caught.message ?: "無法讀取")
            }
            if (token != loadToken) return@launch
            noteLoading = false
            noteSession.open(path, loaded, token)
            editorEpoch++
            publishNote()
        }
    }

    fun requestMode(mode: StudyMode): Boolean {
        if (noteSession.requestLeave(PendingNav.SwitchMode(mode))) {
            publishNote()
            return false
        }
        return true
    }

    fun requestClose(exit: () -> Unit) {
        if (noteSession.requestLeave(PendingNav.CloseWindow)) {
            closeAction = exit
            publishNote()
            return
        }
        exit()
    }

    fun requestIngest() {
        if (previewPath == null) return
        if (noteSession.requestLeave(PendingNav.RunIngest)) {
            publishNote()
            return
        }
        ingestPreview()
    }

    fun toggleNoteMode() {
        if (noteSession.permission !is EditPermission.Editable) return
        noteSession.mode = if (noteSession.mode == NoteViewMode.Reading) {
            NoteViewMode.Editing
        } else {
            NoteViewMode.Reading
        }
        publishNote()
        checkExternal()
    }

    fun showReading() {
        if (noteSession.mode == NoteViewMode.Reading) return
        noteSession.mode = NoteViewMode.Reading
        publishNote()
        checkExternal()
    }

    fun showEditing() {
        if (noteSession.permission !is EditPermission.Editable) return
        if (noteSession.mode == NoteViewMode.Editing) return
        noteSession.mode = NoteViewMode.Editing
        publishNote()
        checkExternal()
    }

    fun setNoteDraft(epoch: Int, text: String) {
        if (epoch != editorEpoch) return
        if (text == noteSession.draft) return
        noteSession.draft = text
        if (noteSession.phase !is NotePhase.Conflict) {
            noteSession.phase = if (noteSession.dirty) NotePhase.Dirty else NotePhase.Clean
        }
        noteNotice = ""
        publishNote()
        scheduleAutosave()
    }

    fun saveNote() {
        scope.launch { performSave(force = false, recreate = false) }
    }

    fun overwriteNote() {
        scope.launch { performSave(force = true, recreate = false) }
    }

    fun recreateNote() {
        scope.launch { performSave(force = true, recreate = true) }
    }

    fun confirmLeaveSave() {
        scope.launch { performSave(force = false, recreate = false) }
    }

    fun confirmLeaveDiscard() {
        val nav = noteSession.discardAndProceed() ?: return
        editorEpoch++
        noteNotice = ""
        publishNote()
        performNav(nav)
    }

    fun cancelLeave() {
        noteSession.cancelLeave()
        closeAction = null
        publishNote()
    }

    fun cancelConflict() {
        noteSession.cancelLeave()
        closeAction = null
        noteSession.phase = if (noteSession.dirty) NotePhase.Dirty else NotePhase.Clean
        publishNote()
    }

    fun reloadFromBanner() {
        reloadDiscarding(proceed = false)
    }

    fun reloadFromConflict() {
        reloadDiscarding(proceed = noteSession.pendingNav != null)
    }

    fun consumeApprovedMode(): StudyMode? {
        val mode = approvedMode
        approvedMode = null
        return mode
    }

    fun consumeNoteClipboard(): String? {
        val text = noteClipboard
        noteClipboard = null
        return text
    }

    fun onWindowFocus() {
        checkExternal()
    }

    fun canIngestPreview(): Boolean {
        val path = previewPath ?: return false
        val vault = Path.of(settings.vaultPath)
        val root = wikiRoot?.let(Path::of)
        return previewOffersIngest(path, root, vault)
    }

    fun ingestPreview() {
        val path = previewPath ?: return
        val root = wikiRoot
        if (root == null) {
            ingestMessage = "Create a wiki root before ingest."
            showCreate = Path.of(settings.vaultPath).isDirectory()
            return
        }
        val targets = ingestTargets(path, Path.of(root), Path.of(settings.vaultPath))
        if (targets.isEmpty()) {
            ingestMessage = "This file is inside the wiki or is not markdown."
            return
        }
        val source = targets.first()
        val generation = ++ingestGeneration
        val vault = Path.of(settings.vaultPath)
        if (settings.ingestMode == IngestMode.Link) {
            proposal = null
            ingestMessage = "Linking…"
            ingestJob = scope.launch {
                busy = true
                try {
                    val staged = withContext(Dispatchers.IO) {
                        linkIntoRawSource(Path.of(root), source)
                    }
                    if (generation != ingestGeneration) return@launch
                    ingestMessage = ingestLinkMessage(staged.relativePath, staged.created)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (caught: Exception) {
                    if (generation == ingestGeneration) ingestMessage = caught.message ?: "Ingest failed."
                } finally {
                    if (generation == ingestGeneration) busy = false
                }
            }
            return
        }
        ingestJob = scope.launch {
            busy = true
            ingestMessage = "Analyzing…"
            try {
                val result = withContext(Dispatchers.IO) {
                    runIngest(Path.of(root), vault, source) { stage ->
                        scope.launch {
                            if (generation == ingestGeneration) ingestMessage = stage
                        }
                    }
                }
                if (generation != ingestGeneration) return@launch
                when (result) {
                    is IngestResult.Proposal -> {
                        proposal = result.proposal
                        ingestMessage = "Confirm the pages before anything is written."
                    }
                    is IngestResult.Stopped -> ingestMessage = result.message
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (caught: Exception) {
                if (generation == ingestGeneration) ingestMessage = caught.message ?: "Ingest failed."
            } finally {
                if (generation == ingestGeneration) busy = false
            }
        }
    }

    private fun runIngest(
        root: Path,
        vault: Path,
        source: Path,
        onStage: (String) -> Unit,
    ): IngestResult {
        val current = index ?: return IngestResult.Stopped("Index is not open.")
        val input = IngestInput(
            sourceName = source.fileName?.toString() ?: source.toString(),
            sourcePath = vaultRelative(vault, source),
            rawText = source.readText(),
            schema = readTextIfExists(root.resolve("wiki").resolve("schema.md")).orEmpty(),
            purpose = readTextIfExists(root.resolve("wiki").resolve("purpose.md")),
            indexText = readTextIfExists(root.resolve("wiki").resolve("index.md")).orEmpty(),
            overview = readTextIfExists(root.resolve("wiki").resolve("overview.md")),
            numCtx = settings.ollamaNumCtx,
            today = LocalDate.now(),
            resolve = { title, aliases ->
                runBlocking(dbDispatcher) { current.resolve(title, aliases) }
            },
            read = { relative -> readTextIfExists(root.resolve(relative)) },
            findSource = { raw ->
                runBlocking(dbDispatcher) { current.findSourceForRaw(raw) }
            },
            exists = { relative ->
                runBlocking(dbDispatcher) { current.noteExists(relative) } ||
                    Files.isRegularFile(root.resolve(relative))
            },
        )
        return IngestPipeline(client()).ingest(input, onStage)
    }

    fun ask() {
        val asked = question.trim()
        if (asked.isEmpty()) return
        scope.launch {
            busy = true
            answer = ""
            askSources = emptyList()
            askPages = emptyList()
            askEdges = emptyList()
            tokenHits = 0
            graphHits = 0
            askMessage = ""
            askReported = false
            askGraphOpen = false
            closeAskReader()
            try {
                val result = withContext(dbDispatcher) {
                    val current = index ?: return@withContext AskResult.NotCalled("Index is not open.")
                    AskPipeline(current, client()).ask(asked, settings.ollamaNumCtx)
                }
                when (result) {
                    is AskResult.NoMatch -> {
                        askMessage = result.message
                        tokenHits = result.tokenHits
                        graphHits = result.graphHits
                        askReported = true
                    }
                    is AskResult.NotCalled -> {
                        askMessage = result.message
                        askReported = true
                    }
                    is AskResult.Answer -> {
                        answer = result.text
                        askSources = result.sourcePaths
                        askPages = result.pages
                        askEdges = result.edges
                        tokenHits = result.tokenHits
                        graphHits = result.graphHits
                        askMessage = ""
                        askReported = true
                        askGraphOpen = result.pages.isNotEmpty()
                    }
                }
            } catch (caught: Exception) {
                askMessage = caught.message ?: "Ask failed."
                askPages = emptyList()
                askEdges = emptyList()
                tokenHits = 0
                graphHits = 0
                askReported = true
                askGraphOpen = false
            } finally {
                busy = false
            }
        }
    }

    fun chat() {
        val asked = chatInput.trim()
        if (asked.isEmpty() || chatJob?.isActive == true) return
        val generation = ++chatGeneration
        chatQuestion = asked
        chatAnswer = ""
        chatSources = emptyList()
        liveQuestion = asked
        liveSteps = emptyList()
        liveAnswer = ""
        chatShown = emptyList()
        chatPages = emptyList()
        chatEdges = emptyList()
        chatMessage = ""
        chatGraphOpen = false
        closeChatReader()
        chatJob = scope.launch {
            busy = true
            try {
                val schema = withContext(dbDispatcher) {
                    schemaExcerpt(index?.load("wiki/schema.md")?.body)
                }
                if (index == null) {
                    chatMessage = "Index is not open."
                    return@launch
                }
                val agent = ChatAgent(
                    client = client(),
                    tools = { action, refs ->
                        withContext(dbDispatcher) {
                            val current = index ?: return@withContext ToolObservation("Index is not open.")
                            val tools = WikiTools(current)
                            when (action) {
                                is AgentAction.Search -> ToolObservation("", outcome = tools.search(action.query))
                                is AgentAction.ReadPage -> tools.read(action.path, refs)
                                else -> ToolObservation("unsupported")
                            }
                        }
                    },
                    onEvent = { event ->
                        if (generation == chatGeneration) applyChatEvent(event)
                    },
                    edgesFor = { paths ->
                        withContext(dbDispatcher) {
                            val current = index ?: return@withContext emptyList()
                            packetEdges(current.wikiGraph(), paths).map { (from, target) ->
                                PacketEdge(from, target.first, target.second)
                            }
                        }
                    },
                )
                when (val result = agent.run(asked, settings.ollamaNumCtx, chatSession.recent(), schema)) {
                    is ChatAgentResult.NoMatch -> {
                        if (generation != chatGeneration) return@launch
                        chatMessage = result.message
                        chatHistory = chatHistory + ChatBubble(asked, result.steps, "")
                        liveQuestion = ""
                        liveSteps = emptyList()
                    }
                    is ChatAgentResult.NotCalled -> {
                        if (generation != chatGeneration) return@launch
                        chatMessage = result.message
                    }
                    is ChatAgentResult.Answer -> {
                        if (generation != chatGeneration) return@launch
                        chatAnswer = result.text
                        liveAnswer = result.text
                        chatShown = result.shown
                        chatPages = result.pages
                        chatEdges = result.edges
                        chatSources = when {
                            result.cited.isNotEmpty() ->
                                result.references.filter { it.number in result.cited }.map { it.path }
                            result.references.any { it.read } ->
                                result.references.filter { it.read }.map { it.path }
                            else -> emptyList()
                        }
                        chatHistory = chatHistory + ChatBubble(asked, result.steps, result.text)
                        chatSession.add(asked, result.text)
                        liveQuestion = ""
                        liveSteps = emptyList()
                        liveAnswer = ""
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (caught: Exception) {
                if (generation == chatGeneration) chatMessage = caught.message ?: "Chat failed."
            } finally {
                if (generation == chatGeneration) busy = false
            }
        }
    }

    fun cancelChat() {
        chatGeneration += 1
        chatJob?.cancel()
        busy = false
    }

    fun newChat() {
        cancelChat()
        chatSession.clear()
        chatInput = ""
        chatQuestion = ""
        chatAnswer = ""
        chatSources = emptyList()
        chatHistory = emptyList()
        liveQuestion = ""
        liveSteps = emptyList()
        liveAnswer = ""
        chatShown = emptyList()
        chatPages = emptyList()
        chatEdges = emptyList()
        chatMessage = ""
        chatGraphOpen = false
        closeChatReader()
    }

    fun showChatGraph() {
        if (chatPages.isNotEmpty()) chatGraphOpen = true
    }

    fun closeChatGraph() {
        chatGraphOpen = false
    }

    fun closeChatReader() {
        chatReaderPath = null
        chatReaderTitle = ""
        chatReaderBody = ""
    }

    fun openChatPage(path: String) {
        val page = chatShown.firstOrNull { it.path == path }
        chatReaderPath = path
        chatReaderTitle = page?.title?.ifBlank { path } ?: path
        scope.launch {
            val body = withContext(dbDispatcher) { index?.load(path)?.body.orEmpty() }
            if (chatReaderPath == path) chatReaderBody = body
        }
    }

    fun fileChatAnswer() {
        if (chatAnswer.isBlank()) return
        val root = wikiRoot ?: return
        val asked = chatQuestion
        val answer = chatAnswer
        val sources = chatSources
        scope.launch {
            val draft = withContext(dbDispatcher) {
                val current = index
                proposeQueryFile(
                    question = asked,
                    answer = answer,
                    sourcePaths = sources,
                    today = LocalDate.now(),
                    exists = { relative ->
                        current?.noteExists(relative) == true ||
                            Files.isRegularFile(Path.of(root).resolve(relative))
                    },
                )
            }
            proposal = draft
        }
    }

    private fun applyChatEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> chatMessage = "step ${event.step}"
            is AgentEvent.ToolStart -> Unit
            is AgentEvent.ToolEnd -> {
                liveSteps = liveSteps + event.summary
                chatMessage = ""
            }
            is AgentEvent.ReferenceAdded -> Unit
        }
    }

    fun fileAnswer() {
        if (answer.isBlank()) return
        val root = wikiRoot ?: return
        scope.launch {
            val draft = withContext(dbDispatcher) {
                val current = index
                proposeQueryFile(
                    question = question,
                    answer = answer,
                    sourcePaths = askSources,
                    today = LocalDate.now(),
                    exists = { relative ->
                        current?.noteExists(relative) == true ||
                            Files.isRegularFile(Path.of(root).resolve(relative))
                    },
                )
            }
            proposal = draft
        }
    }

    fun confirmWrites(checked: Set<String>, checkedReviews: Set<String> = emptySet()) {
        val draft = proposal ?: return
        proposal = null
        val root = wikiRoot ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    applyProposal(
                        Path.of(root),
                        draft.pages,
                        checked,
                        draft.logTitle,
                        draft.operation,
                        LocalDate.now(),
                        draft.reviews,
                        checkedReviews,
                        draft.sourcePath,
                    )
                }
                val snapshot = withContext(dbDispatcher) {
                    index?.reconcileAll()
                    (index?.noteCount() ?: 0) to (index?.listSummaries().orEmpty())
                }
                noteCount = snapshot.first
                summaries = snapshot.second
                treeTick += 1
                if (result.failed != null) error = result.failed else ingestMessage = "Wrote ${result.written.joinToString(", ")}."
            } catch (caught: Exception) {
                error = caught.message ?: "Write failed."
            } finally {
                busy = false
            }
        }
    }

    fun canResearchNow(): Boolean = researchBlockReason(
        searxngStatus,
        researchPhase,
        wikiRoot != null,
        settings.ollamaModel.isNotBlank(),
    ) == null

    fun researchBlockText(): String? = researchBlockReason(
        searxngStatus,
        researchPhase,
        wikiRoot != null,
        settings.ollamaModel.isNotBlank(),
    )

    fun onDeepResearchOpened() {
        reloadReviews()
        testSearxng()
    }

    fun reloadReviews() {
        val root = wikiRoot
        if (root == null) {
            reviews = emptyList()
            return
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                readTextIfExists(Path.of(root).resolve("wiki").resolve("review.md")).orEmpty()
            }
            if (wikiRoot == root) reviews = parseReviewFile(text)
        }
    }

    fun testSearxng() {
        val url = settings.searxngUrl
        val gen = ++searxngGeneration
        searxngStatus = SearxngStatus.Testing
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                try {
                    SearxngClient(url).testConnection()
                } catch (caught: Exception) {
                    SearxngStatus.Failed(caught.message ?: "SearXNG is not reachable.")
                }
            }
            if (gen == searxngGeneration) searxngStatus = next
        }
    }

    fun startResearch(entry: ReviewEntry) {
        if (!canResearchNow()) return
        if (!researchGate.compareAndSet(0, 1)) return
        val root = wikiRoot
        if (root == null) {
            researchGate.set(0)
            return
        }
        val gen = ++researchGeneration
        val numCtx = settings.ollamaNumCtx
        val baseUrl = settings.ollamaBaseUrl
        val model = settings.ollamaModel
        val searxng = settings.searxngUrl
        researchTarget = entry
        researchHits = emptyList()
        researchText = ""
        researchMessage = ""
        researchPage = null
        researchPhase = ResearchPhase.Searching
        researchJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val runner = ResearchRunner(
                        prompts = locateAiPrompts(),
                        numCtx = numCtx,
                        today = LocalDate.now(),
                        search = { query -> SearxngClient(searxng).search(query) },
                        stream = { request, onDelta ->
                            OllamaClient(baseUrl, model, numCtx).stream(request, onDelta)
                        },
                        noteExists = { relative ->
                            runBlocking(dbDispatcher) { index?.noteExists(relative) == true }
                        },
                    )
                    val indexExcerpt = readTextIfExists(Path.of(root).resolve("wiki").resolve("index.md")).orEmpty()
                    runner.run(
                        root = Path.of(root),
                        entry = entry,
                        indexExcerpt = indexExcerpt,
                        cancelled = { gen != researchGeneration },
                        rootStillOpen = { wikiRoot == root },
                        beginSaving = { researchGate.compareAndSet(1, 2) },
                        onUpdate = { update -> publishResearch(gen, update) },
                    )
                }
                if (gen == researchGeneration) applyResearchResult(result, root)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (caught: Exception) {
                if (gen == researchGeneration) {
                    researchPhase = ResearchPhase.Failed
                    researchMessage = caught.message ?: "Research failed."
                }
            } finally {
                if (gen == researchGeneration) researchGate.set(0)
            }
        }
    }

    fun retryResearch() {
        val entry = researchTarget ?: return
        startResearch(entry)
    }

    fun cancelResearch() {
        if (!researchGate.compareAndSet(1, 0)) return
        researchGeneration += 1
        researchJob?.cancel()
        researchPhase = ResearchPhase.Idle
        researchMessage = "已取消。沒有寫入。"
    }

    fun openResearchedPage() {
        val path = researchPage ?: return
        val root = wikiRoot ?: return
        pendingMode = StudyMode.Browse
        scope.launch {
            val indexed = withContext(dbDispatcher) { index?.load(path)?.body.orEmpty() }
            val text = indexed.ifBlank {
                withContext(Dispatchers.IO) {
                    readTextIfExists(Path.of(root).resolve(path)).orEmpty()
                }
            }
            openNote = text
            previewTitle = path
        }
    }

    private fun publishResearch(gen: Int, update: ResearchUpdate) {
        scope.launch {
            if (gen != researchGeneration) return@launch
            if (researchPhase == ResearchPhase.Done || researchPhase == ResearchPhase.Failed) return@launch
            researchPhase = update.phase
            researchHits = update.hits
            researchText = update.synthesis
        }
    }

    private suspend fun applyResearchResult(result: ResearchResult, root: String) {
        when (result) {
            is ResearchResult.Done -> {
                researchPhase = ResearchPhase.Done
                researchPage = result.path
                researchText = result.synthesis
                researchMessage = result.path
                researchTarget = null
                if (wikiRoot == root) {
                    val snapshot = withContext(dbDispatcher) {
                        index?.reconcileAll()
                        (index?.noteCount() ?: 0) to (index?.listSummaries().orEmpty())
                    }
                    noteCount = snapshot.first
                    summaries = snapshot.second
                    treeTick += 1
                    reloadReviews()
                }
            }
            is ResearchResult.Failed -> {
                researchPhase = ResearchPhase.Failed
                researchMessage = result.message
            }
            ResearchResult.Cancelled -> {
                researchPhase = ResearchPhase.Idle
                researchMessage = "已取消。沒有寫入。"
            }
        }
    }

    fun setLintSemantic(enabled: Boolean) {
        if (lintRunning) return
        settings = appSettings.save(settings.copy(lintSemantic = enabled))
    }

    fun updateLintIgnore(text: String) {
        lintIgnoreText = text
        val root = wikiRoot ?: return
        appSettings.saveLintIgnore(root, text)
    }

    fun toggleLint(id: String) {
        val finding = lintFindings.firstOrNull { it.id == id } ?: return
        if (finding.reviewType == null || finding.alreadyInReview || lintRunning) return
        lintChecked = if (id in lintChecked) lintChecked - id else lintChecked + id
    }

    fun selectAllLint() {
        if (lintRunning) return
        lintChecked = lintFindings.filter { it.reviewType != null && !it.alreadyInReview }.map { it.id }.toSet()
    }

    fun selectNoneLint() {
        if (lintRunning) return
        lintChecked = emptySet()
    }

    fun openLintPage(path: String) {
        pendingMode = StudyMode.Browse
        openIndexed(path)
    }

    fun startLint() {
        if (lintRunning) return
        val root = wikiRoot ?: return
        lintStop.set(false)
        val token = ++lintGeneration
        val wantSemantic = settings.lintSemantic
        val ignore = parseIgnoreList(lintIgnoreText)
        appSettings.saveLintIgnore(root, lintIgnoreText)
        val numCtx = settings.ollamaNumCtx
        val baseUrl = settings.ollamaBaseUrl
        val model = settings.ollamaModel
        lintRunning = true
        lintFindings = emptyList()
        lintPages = emptyList()
        lintWarnings = emptyList()
        lintChecked = emptySet()
        lintMessage = ""
        lintStructuralText = "本地規則：準備中"
        lintSemanticText = if (wantSemantic) "語意健檢：準備中" else ""
        val findingsBox = AtomicReference<List<LintFinding>>(emptyList())
        val warningsBox = AtomicReference<List<String>>(emptyList())
        val structuralDone = AtomicInteger(0)
        val structuralTotal = AtomicInteger(0)
        val semanticDone = AtomicInteger(0)
        val semanticTotal = AtomicInteger(0)
        val progressEpoch = AtomicInteger(0)
        lintJob = scope.launch {
            try {
                val snapshot = withContext(dbDispatcher) {
                    val current = index ?: return@withContext null
                    lintSnapshot(current)
                }
                if (token != lintGeneration) return@launch
                if (snapshot == null || snapshot.pages.isEmpty()) {
                    lintStructuralText = "沒有可檢查的 wiki 頁。"
                    lintSemanticText = ""
                    return@launch
                }
                lintPages = snapshot.pages
                val resolver = { name: String -> snapshot.resolutions[name] ?: Resolution.None }
                val structural = withContext(Dispatchers.Default) {
                    val result = runStructuralLint(
                        pages = snapshot.pages,
                        resolver = resolver,
                        config = LintConfig(ignore = ignore),
                        onProgress = { done, total ->
                            structuralDone.set(done)
                            structuralTotal.set(total)
                            val seen = progressEpoch.get()
                            scope.launch {
                                if (token == lintGeneration && seen == progressEpoch.get()) {
                                    lintStructuralText = formatStructural(done, total, cancelled = false)
                                }
                            }
                        },
                        isActive = { token == lintGeneration && !lintStop.get() },
                    )
                    findingsBox.set(result.findings)
                    structuralDone.set(result.done)
                    structuralTotal.set(result.total)
                    result
                }
                if (token != lintGeneration) return@launch
                val stoppedEarly = lintStop.get() || structural.cancelled
                progressEpoch.incrementAndGet()
                lintFindings = structural.findings
                lintStructuralText = formatStructural(
                    structural.done,
                    structural.total,
                    cancelled = stoppedEarly && structural.done < structural.total,
                )
                if (stoppedEarly) {
                    if (wantSemantic) lintSemanticText = "語意健檢：已取消"
                    applyLintMarks(root, token, structural.findings)
                    return@launch
                }
                if (!wantSemantic) {
                    applyLintMarks(root, token, structural.findings)
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO) {
                    val result = runSemanticLint(
                        pages = snapshot.pages,
                        resolver = resolver,
                        prompts = locateAiPrompts(),
                        numCtx = numCtx,
                        client = ChatClient { request ->
                            OllamaClient(baseUrl, model, numCtx).complete(request)
                        },
                        onProgress = { progress ->
                            val seen = progressEpoch.get()
                            scope.launch {
                                if (token == lintGeneration && seen == progressEpoch.get()) {
                                    lintSemanticText = formatSemantic(progress)
                                }
                            }
                        },
                        isActive = { token == lintGeneration && !lintStop.get() },
                    )
                    val merged = mergeLintFindings(structural.findings, result.findings)
                    findingsBox.set(merged)
                    warningsBox.set(result.warnings)
                    semanticDone.set(result.completedBatches)
                    semanticTotal.set(result.totalBatches)
                    result
                }
                if (token != lintGeneration) return@launch
                progressEpoch.incrementAndGet()
                lintSemanticText = when {
                    outcome.cancelled -> formatSemantic(
                        LintProgress.Semantic(
                            outcome.completedBatches,
                            outcome.totalBatches,
                            0,
                            0,
                            com.neojou.mystudy.wiki.lint.SemanticPhase.Cancelled,
                            "",
                        ),
                    )
                    outcome.totalBatches > 0 -> formatSemantic(
                        LintProgress.Semantic(
                            outcome.totalBatches,
                            outcome.totalBatches,
                            0,
                            0,
                            com.neojou.mystudy.wiki.lint.SemanticPhase.Done,
                            "",
                        ),
                    )
                    else -> outcome.warnings.firstOrNull()?.let { warning ->
                        if (warning.startsWith("語意健檢")) warning else "語意健檢失敗：$warning"
                    } ?: "語意健檢失敗"
                }
                lintWarnings = outcome.warnings.filterNot { it == lintSemanticText }
                applyLintMarks(root, token, findingsBox.get())
            } catch (cancel: CancellationException) {
                if (token == lintGeneration) {
                    lintFindings = findingsBox.get()
                    lintWarnings = warningsBox.get()
                    val pagesDone = structuralDone.get()
                    val pagesTotal = structuralTotal.get()
                    if (pagesTotal > 0 && pagesDone < pagesTotal) {
                        lintStructuralText = formatStructural(pagesDone, pagesTotal, cancelled = true)
                    }
                    if (wantSemantic && semanticTotal.get() > 0) {
                        lintSemanticText = formatSemantic(
                            LintProgress.Semantic(
                                semanticDone.get(),
                                semanticTotal.get(),
                                0,
                                0,
                                com.neojou.mystudy.wiki.lint.SemanticPhase.Cancelled,
                                "",
                            ),
                        )
                    }
                    lintChecked = lintFindings.filter { it.defaultChecked && !it.alreadyInReview }.map { it.id }.toSet()
                }
                throw cancel
            } catch (caught: Exception) {
                if (token == lintGeneration) lintMessage = caught.message ?: "Lint failed."
            } finally {
                if (token == lintGeneration) lintRunning = false
            }
        }
    }

    fun cancelLint() {
        if (!lintRunning) return
        lintStop.set(true)
        lintJob?.cancel()
    }

    fun writeLintReviews() {
        if (lintRunning) return
        val root = wikiRoot ?: return
        val selected = lintFindings.filter { it.id in lintChecked && it.reviewType != null && !it.alreadyInReview }
        if (selected.isEmpty()) return
        val titles = lintPages.associate { it.path to it.title }
        val proposals = toReviewProposals(selected, selected.map { it.id }.toSet()) { path ->
            titles[path]?.ifBlank { null } ?: path.substringAfterLast('/').removeSuffix(".md")
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                appendLintReviews(Path.of(root), proposals, LocalDate.now(), writeLog = true)
            }
            if (wikiRoot != root) return@launch
            lintMessage = result.message
            if (!result.wrote) return@launch
            val text = withContext(Dispatchers.IO) {
                readTextIfExists(Path.of(root).resolve("wiki").resolve("review.md")).orEmpty()
            }
            if (wikiRoot != root) return@launch
            lintFindings = markExisting(lintFindings, parseReviewFile(text))
            lintChecked = emptySet()
            reloadReviews()
            val snapshot = withContext(dbDispatcher) {
                index?.reconcileAll()
                (index?.noteCount() ?: 0) to (index?.listSummaries().orEmpty())
            }
            if (wikiRoot == root) {
                noteCount = snapshot.first
                summaries = snapshot.second
                treeTick += 1
            }
        }
    }

    private suspend fun applyLintMarks(root: String, token: Int, findings: List<LintFinding>) {
        val text = withContext(Dispatchers.IO) {
            readTextIfExists(Path.of(root).resolve("wiki").resolve("review.md")).orEmpty()
        }
        if (token != lintGeneration || wikiRoot != root) return
        val marked = markExisting(findings, parseReviewFile(text))
        lintFindings = marked
        lintChecked = marked.filter { it.defaultChecked && !it.alreadyInReview }.map { it.id }.toSet()
    }

    private fun resetLint(root: String?) {
        lintStop.set(true)
        lintGeneration += 1
        lintJob?.cancel()
        lintRunning = false
        lintFindings = emptyList()
        lintPages = emptyList()
        lintWarnings = emptyList()
        lintChecked = emptySet()
        lintStructuralText = ""
        lintSemanticText = ""
        lintMessage = ""
        lintIgnoreText = if (root.isNullOrBlank()) "" else appSettings.lintIgnore(root)
    }

    fun close() {
        chatGeneration += 1
        chatJob?.cancel()
        ingestGeneration += 1
        ingestJob?.cancel()
        researchGeneration += 1
        researchJob?.cancel()
        lintGeneration += 1
        lintStop.set(true)
        lintJob?.cancel()
        researchGate.set(0)
        searxngGeneration += 1
        generation += 1
        loadToken += 1
        autosaveJob?.cancel()
        EditorChrome.dirty = false
        stopWatcher()
        runBlocking(dbDispatcher) {
            index?.close()
            index = null
        }
        dbDispatcher.close()
    }

    private suspend fun showMissing(path: String) {
        closeIndex()
        wikiRoot = null
        resetLint(null)
        resetNote()
        showCreate = false
        noteCount = 0
        summaries = emptyList()
        status = "Vault folder is missing. Choose it in Settings."
        error = path
    }

    private suspend fun showNone() {
        closeIndex()
        wikiRoot = null
        resetLint(null)
        resetNote()
        noteCount = 0
        summaries = emptyList()
        status = "No wiki root in this vault."
        showCreate = Path.of(settings.vaultPath).isDirectory()
    }

    private suspend fun loadRoot(root: Path, gen: Int) {
        resetLint(null)
        busy = true
        try {
            val opened = withContext(dbDispatcher) {
                if (gen != generation) return@withContext null
                index?.close()
                val created = WikiIndex.open(root, indexDatabasePath(root))
                created.reconcileAll()
                index = created
                created
            } ?: return
            if (gen != generation) {
                withContext(dbDispatcher) { opened.close() }
                return
            }
            val count = withContext(dbDispatcher) { opened.noteCount() }
            val listed = withContext(dbDispatcher) { opened.listSummaries() }
            val previousRoot = wikiRoot
            val openPath = noteSession.path
            wikiRoot = root.toString()
            if (openPath != null && previousRoot != root.toString()) {
                if (previousRoot == null) previewFile(openPath) else resetNote()
            } else if (openPath != null) {
                checkExternal()
            }
            lintIgnoreText = appSettings.lintIgnore(root.toString())
            noteCount = count
            summaries = listed
            searchHits = listed
            status = root.toString()
            error = null
            showCreate = false
            rootChoices = emptyList()
            persistRoot(root.toString())
            startWatcher(root, gen)
        } finally {
            busy = false
        }
    }

    private suspend fun closeIndex() {
        stopWatcher()
        withContext(dbDispatcher) {
            index?.close()
            index = null
        }
    }

    private fun startWatcher(root: Path, gen: Int) {
        stopWatcher()
        if (gen != generation) return
        val created = WikiWatcher(root) { relative ->
            debounce?.cancel()
            debounce = scope.launch {
                delay(300)
                if (gen != generation) return@launch
                val snapshot = withContext(dbDispatcher) {
                    val current = index ?: return@withContext null
                    if (relative == null) current.reconcileAll() else current.reindexRelative(relative)
                    current.noteCount() to current.listSummaries()
                } ?: return@launch
                noteCount = snapshot.first
                summaries = snapshot.second
                val rootPath = wikiRoot?.let(Path::of)
                val openRelative = rootPath?.let { root ->
                    noteSession.path?.let { wikiIndexRelative(root, it) }
                }
                if (relative == null || (openRelative != null && openRelative == relative)) {
                    checkExternal()
                }
            }
        }
        watcher = created
        created.start()
    }

    private fun stopWatcher() {
        debounce?.cancel()
        val current = watcher
        watcher = null
        current?.stop()
    }

    private fun persistRoot(path: String) {
        val updated = settings.copy(wikiRootPath = path)
        settings = appSettings.save(updated)
    }

    private fun client(): OllamaClient = OllamaClient(
        baseUrl = settings.ollamaBaseUrl,
        model = settings.ollamaModel,
        numCtx = settings.ollamaNumCtx,
    )

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        if (!settings.noteAutosave) return
        if (!noteSession.dirty) return
        if (noteSession.phase is NotePhase.Conflict || noteSession.phase is NotePhase.Saving) return
        if (noteSession.permission !is EditPermission.Editable) return
        val epoch = editorEpoch
        autosaveJob = scope.launch {
            delay(2_000)
            if (epoch != editorEpoch) return@launch
            if (noteSession.phase is NotePhase.Conflict) return@launch
            if (!noteSession.dirty) return@launch
            performSave(force = false, recreate = false)
        }
    }

    private suspend fun performSave(force: Boolean, recreate: Boolean) {
        autosaveJob?.cancel()
        val path = noteSession.path ?: return
        val token = loadToken
        val editorText = noteSession.draft
        val loadedText = noteSession.loadedText
        val base = noteSession.base ?: return
        if (noteSession.permission !is EditPermission.Editable) return
        val vault = Path.of(settings.vaultPath)
        val root = wikiRoot?.let(Path::of)
        saveMutex.withLock {
            if (token != loadToken) return@withLock
            saveEpoch++
            noteSession.phase = NotePhase.Saving
            publishNote()
            val outcome = withContext(Dispatchers.IO) {
                writeNote(
                    vault = vault,
                    wikiRoot = root,
                    file = path,
                    editorText = editorText,
                    loadedText = loadedText,
                    base = base,
                    force = force,
                    recreate = recreate,
                )
            }
            if (token != loadToken) return@withLock
            when (outcome) {
                SaveOutcome.Unchanged -> {
                    noteSession.phase = if (noteSession.dirty) NotePhase.Dirty else NotePhase.Clean
                    publishNote()
                    if (!noteSession.dirty) proceedPending()
                }
                is SaveOutcome.Saved -> {
                    noteSession.markSaved(outcome.snapshot, editorText)
                    publishNote()
                    reindexSaved(root, path)
                    if (!noteSession.dirty) proceedPending()
                }
                is SaveOutcome.Conflict -> {
                    noteSession.markConflict(outcome.deleted)
                    publishNote()
                }
                is SaveOutcome.Failed -> {
                    noteSession.markFailed(outcome.message)
                    publishNote()
                }
            }
        }
    }

    private suspend fun reindexSaved(root: Path?, path: Path) {
        if (root == null) return
        val relative = wikiIndexRelative(root, path) ?: return
        val snapshot = withContext(dbDispatcher) {
            val current = index ?: return@withContext null
            current.reindexRelative(relative)
            current.noteCount() to current.listSummaries()
        } ?: return
        noteCount = snapshot.first
        summaries = snapshot.second
        treeTick += 1
    }

    private fun proceedPending() {
        val nav = noteSession.pendingNav ?: return
        noteSession.pendingNav = null
        publishNote()
        performNav(nav)
    }

    private fun performNav(nav: PendingNav) {
        when (nav) {
            is PendingNav.OpenFile -> previewFile(nav.path)
            is PendingNav.SwitchMode -> approvedMode = nav.mode
            PendingNav.CloseWindow -> {
                val exit = closeAction
                closeAction = null
                exit?.invoke()
            }
            PendingNav.RunIngest -> ingestPreview()
        }
    }

    private fun reloadDiscarding(proceed: Boolean) {
        noteClipboard = noteSession.draft
        noteNotice = "已將捨棄的內容複製到剪貼簿"
        if (!proceed) {
            noteSession.pendingNav = null
            closeAction = null
        }
        reloadOpen(proceed)
    }

    private fun reloadOpen(proceed: Boolean) {
        val path = noteSession.path ?: return
        val token = ++loadToken
        noteSession.generation = token
        editorEpoch++
        noteLoading = true
        autosaveJob?.cancel()
        val vault = Path.of(settings.vaultPath)
        val root = wikiRoot?.let(Path::of)
        publishNote()
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { loadNote(vault, root, path) }
            } catch (caught: Exception) {
                LoadedNote.Unreadable(caught.message ?: "無法讀取")
            }
            if (token != loadToken) return@launch
            noteLoading = false
            noteSession.open(path, loaded, token, keepPending = proceed)
            editorEpoch++
            publishNote()
            if (proceed) proceedPending()
        }
    }

    private fun checkExternal() {
        val path = noteSession.path ?: return
        val base = noteSession.base ?: return
        val token = noteSession.generation
        val epoch = saveEpoch
        val baseHash = base.sha256
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                try {
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) return@withContext null
                    val size = Files.size(path)
                    val mtime = Files.getLastModifiedTime(path).toMillis()
                    if (mtime == base.mtimeMillis && size == base.size) return@withContext baseHash
                    noteFileSha256(path)
                } catch (_: Exception) {
                    null
                }
            }
            if (token != loadToken || epoch != saveEpoch) return@launch
            if (hash == null) {
                if (noteSession.dirty) {
                    noteSession.externalChange = true
                    publishNote()
                } else {
                    reloadOpen(proceed = false)
                }
                return@launch
            }
            when (noteSession.noteExternal(hash, token)) {
                ExternalEffect.Ignore -> publishNote()
                ExternalEffect.Banner -> publishNote()
                ExternalEffect.Reload -> reloadOpen(proceed = false)
            }
        }
    }

    private fun publishNote() {
        noteMode = noteSession.mode
        notePhase = noteSession.phase
        notePending = noteSession.pendingNav
        noteDirty = noteSession.dirty
        noteCanEdit = noteSession.permission is EditPermission.Editable
        noteWarning = (noteSession.permission as? EditPermission.Editable)?.warning
        noteExternal = noteSession.externalChange
        noteMixed = noteSession.base?.mixed == true && noteCanEdit
        noteDraft = noteSession.draft
        previewBody = noteSession.draft
        noteUnreadable = !noteLoading && noteSession.path != null && noteSession.base == null &&
            noteSession.permission is EditPermission.ReadOnly
        noteStatus = if (noteLoading) {
            "讀取中…"
        } else {
            noteStatusText(
                permission = noteSession.permission,
                phase = noteSession.phase,
                dirty = noteSession.dirty,
                external = noteSession.externalChange,
                savedAtMillis = noteSession.savedAtMillis,
            )
        }
        EditorChrome.dirty = noteSession.dirty
    }

    private fun resetNote() {
        autosaveJob?.cancel()
        loadToken++
        noteSession.generation = loadToken
        editorEpoch++
        noteLoading = false
        noteNotice = ""
        closeAction = null
        noteSession.path = null
        noteSession.draft = ""
        noteSession.loadedText = ""
        noteSession.base = null
        noteSession.permission = EditPermission.ReadOnly("檔案不存在")
        noteSession.mode = NoteViewMode.Reading
        noteSession.phase = NotePhase.Clean
        noteSession.externalChange = false
        noteSession.pendingNav = null
        noteSession.savedAtMillis = null
        previewPath = null
        previewTitle = ""
        previewBody = ""
        publishNote()
    }

    private fun sameNotePath(left: Path?, right: Path): Boolean {
        if (left == null) return false
        return left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()
    }
}
