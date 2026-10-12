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
import com.neojou.mystudy.wiki.model.DraftProposal
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
import com.neojou.mystudy.wiki.settings.SettingsStore
import com.neojou.mystudy.wiki.settings.StudySettings
import com.neojou.mystudy.wiki.vault.Discovery
import com.neojou.mystudy.wiki.vault.applyProposal
import com.neojou.mystudy.wiki.vault.discoverWikiRoot
import com.neojou.mystudy.wiki.vault.ingestTargets
import com.neojou.mystudy.wiki.vault.readCapped
import com.neojou.mystudy.wiki.vault.readTextIfExists
import com.neojou.mystudy.wiki.vault.scaffoldWiki
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
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private val researchGate = AtomicInteger(0)
    private var researchTarget: ReviewEntry? = null
    private var searxngGeneration = 0
    private var chatQuestion = ""
    private var chatAnswer = ""
    private var chatSources = emptyList<String>()
    private var watcher: WikiWatcher? = null
    private var debounce: Job? = null
    private var generation = 0

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

    fun previewFile(path: Path) {
        val cancelIngest = ingestJob?.isActive == true
        ingestGeneration += 1
        ingestJob?.cancel()
        if (cancelIngest) busy = false
        proposal = null
        previewPath = path
        previewTitle = path.fileName?.toString() ?: path.toString()
        ingestMessage = ""
        pendingMode = StudyMode.Home
        scope.launch {
            previewBody = withContext(Dispatchers.IO) { readPreview(path) }
        }
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

    fun close() {
        chatGeneration += 1
        chatJob?.cancel()
        ingestGeneration += 1
        ingestJob?.cancel()
        researchGeneration += 1
        researchJob?.cancel()
        researchGate.set(0)
        searxngGeneration += 1
        generation += 1
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
        showCreate = false
        noteCount = 0
        summaries = emptyList()
        status = "Vault folder is missing. Choose it in Settings."
        error = path
    }

    private suspend fun showNone() {
        closeIndex()
        wikiRoot = null
        noteCount = 0
        summaries = emptyList()
        status = "No wiki root in this vault."
        showCreate = Path.of(settings.vaultPath).isDirectory()
    }

    private suspend fun loadRoot(root: Path, gen: Int) {
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
            wikiRoot = root.toString()
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

    private fun readPreview(path: Path): String = try {
        if (!Files.isRegularFile(path)) "Select a file."
        else {
            val text = readCapped(path, 100_000)
            if (text.truncated) text.text + "\n… truncated" else text.text
        }
    } catch (_: Exception) {
        "Cannot read this file as UTF-8."
    }
}
