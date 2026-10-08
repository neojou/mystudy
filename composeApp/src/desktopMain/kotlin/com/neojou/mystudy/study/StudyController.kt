package com.neojou.mystudy.study

import com.neojou.mystudy.wiki.ask.AskPipeline
import com.neojou.mystudy.wiki.ask.AskResult
import com.neojou.mystudy.wiki.ask.proposeQueryFile
import com.neojou.mystudy.wiki.index.NoteSummary
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.index.WikiWatcher
import com.neojou.mystudy.wiki.index.indexDatabasePath
import com.neojou.mystudy.wiki.ingest.DraftResult
import com.neojou.mystudy.wiki.ingest.ExtractResult
import com.neojou.mystudy.wiki.ingest.IngestPipeline
import com.neojou.mystudy.wiki.markdown.queryTerms
import com.neojou.mystudy.wiki.model.Claim
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.ollama.OllamaClient
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.SettingsStore
import com.neojou.mystudy.wiki.settings.StudySettings
import com.neojou.mystudy.wiki.vault.Discovery
import com.neojou.mystudy.wiki.vault.MAX_DIRECTORY_INGEST
import com.neojou.mystudy.wiki.vault.applyProposal
import com.neojou.mystudy.wiki.vault.discoverWikiRoot
import com.neojou.mystudy.wiki.vault.ingestTargets
import com.neojou.mystudy.wiki.vault.listMarkdownUnder
import com.neojou.mystudy.wiki.vault.readCapped
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.neojou.mystudy.wiki.vault.stageIntoRaw
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.io.path.isDirectory

data class ClaimChoice(
    val claim: Claim,
    val checked: Boolean,
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

    var previewTitle by mutableStateOf("")
        private set
    var previewBody by mutableStateOf("")
        private set

    var rawFiles by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedRaw by mutableStateOf<String?>(null)
        private set
    var rawPreview by mutableStateOf("")
        private set
    var claims by mutableStateOf<List<ClaimChoice>>(emptyList())
        private set
    var ingestMessage by mutableStateOf("")
        private set
    var queue by mutableStateOf<List<String>>(emptyList())
        private set

    var question by mutableStateOf("")
    var answer by mutableStateOf("")
        private set
    var askSources by mutableStateOf<List<String>>(emptyList())
        private set
    var askMessage by mutableStateOf("")
        private set

    var proposal by mutableStateOf<DraftProposal?>(null)
        private set
    var stagePaths by mutableStateOf<List<String>>(emptyList())
        private set

    var pendingMode by mutableStateOf<StudyMode?>(null)
        private set
    @Volatile private var index: WikiIndex? = null
    private var watcher: WikiWatcher? = null
    private var debounce: Job? = null
    private var generation = 0

    init {
        refresh(honorSaved = true)
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

    fun dismissStage() {
        stagePaths = emptyList()
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
        appSettings.save(next)
        settings = next
        refresh(honorSaved = true)
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
                val terms = queryTerms(query)
                val paths = linkedSetOf<String>()
                paths += current.searchFts(terms, 40)
                terms.filter { it.length == 2 }.forEach { term -> paths += current.searchLike(term, 40) }
                paths.mapNotNull { path ->
                    current.load(path)?.let { NoteSummary(it.path, it.title, it.type) }
                }
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

    fun previewFile(path: Path) {
        previewTitle = path.fileName?.toString() ?: path.toString()
        scope.launch {
            previewBody = withContext(Dispatchers.IO) { readPreview(path) }
        }
    }

    fun requestIngest(path: Path) {
        val root = wikiRoot
        if (root == null) {
            error = "Create a wiki root before ingest."
            showCreate = settings.vaultPath.let { Path.of(it).isDirectory() }
            return
        }
        val targets = ingestTargets(path, Path.of(root), Path.of(settings.vaultPath))
        when {
            targets.isEmpty() -> error = "No markdown file outside the wiki to ingest."
            targets.size > MAX_DIRECTORY_INGEST -> {
                error = "That folder has ${targets.size} notes. Choose a smaller folder."
            }
            else -> stagePaths = targets.map { it.toString() }
        }
    }

    fun confirmStage() {
        val paths = stagePaths
        if (paths.isEmpty()) return
        stagePaths = emptyList()
        val root = wikiRoot ?: return
        scope.launch {
            busy = true
            try {
                val staged = withContext(Dispatchers.IO) {
                    paths.map { source -> stageIntoRaw(Path.of(root), Path.of(source)).relativePath }
                }
                queue = staged
                rawFiles = withContext(Dispatchers.IO) { listMarkdownUnder(Path.of(root), "raw") }
                treeTick += 1
                selectRaw(staged.first())
                pendingMode = StudyMode.Ingest
                ingestMessage = "Copied into raw/. Original files were not changed."
            } catch (caught: Exception) {
                error = caught.message ?: "Could not copy into raw/."
            } finally {
                busy = false
            }
        }
    }

    fun copyPickedFile(absolute: String) {
        val root = wikiRoot ?: run {
            error = "Create a wiki root before copying into raw/."
            return
        }
        scope.launch {
            busy = true
            try {
                val staged = withContext(Dispatchers.IO) { stageIntoRaw(Path.of(root), Path.of(absolute)) }
                rawFiles = withContext(Dispatchers.IO) { listMarkdownUnder(Path.of(root), "raw") }
                treeTick += 1
                selectRaw(staged.relativePath)
                ingestMessage = if (staged.copied) "Copied into raw/." else "Using the raw file that is already there."
            } catch (caught: Exception) {
                error = caught.message ?: "Could not copy into raw/."
            } finally {
                busy = false
            }
        }
    }

    fun selectRaw(relative: String) {
        selectedRaw = relative
        claims = emptyList()
        val root = wikiRoot ?: return
        scope.launch {
            rawPreview = withContext(Dispatchers.IO) { readPreview(Path.of(root).resolve(relative)) }
        }
    }

    fun setClaimChecked(index: Int, checked: Boolean) {
        claims = claims.mapIndexed { item, choice ->
            if (item == index) choice.copy(checked = checked) else choice
        }
    }

    fun extractClaims() {
        val relative = selectedRaw ?: run {
            ingestMessage = "Choose a raw note."
            return
        }
        val root = wikiRoot ?: return
        scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    readCapped(Path.of(root).resolve(relative), PromptBudget.inputCharBudget(settings.ollamaNumCtx))
                }
                if (text.truncated) {
                    ingestMessage = "This raw note is too big for one pass. Split it. Nothing was sent."
                    return@launch
                }
                when (val result = withContext(Dispatchers.IO) { IngestPipeline(client()).extract(text.text, settings.ollamaNumCtx) }) {
                    is ExtractResult.Ok -> {
                        claims = result.claims.map { ClaimChoice(it, checked = true) }
                        ingestMessage = "${result.claims.size} claims. Uncheck any you do not want."
                    }
                    is ExtractResult.Stopped -> ingestMessage = result.message
                }
            } catch (caught: Exception) {
                ingestMessage = caught.message ?: "Extract failed."
            } finally {
                busy = false
            }
        }
    }

    fun draftClaims() {
        val relative = selectedRaw ?: return
        val accepted = claims.filter { it.checked }.map { it.claim }
        scope.launch {
            busy = true
            try {
                val result = withContext(dbDispatcher) {
                    val current = index ?: return@withContext DraftResult.Stopped("Index is not open.")
                    IngestPipeline(client()).draft(current, relative, accepted, settings.ollamaNumCtx, LocalDate.now())
                }
                when (result) {
                    is DraftResult.Ok -> proposal = result.proposal
                    is DraftResult.Stopped -> ingestMessage = result.message
                }
            } catch (caught: Exception) {
                ingestMessage = caught.message ?: "Draft failed."
            } finally {
                busy = false
            }
        }
    }

    fun ask() {
        val asked = question.trim()
        if (asked.isEmpty()) return
        scope.launch {
            busy = true
            answer = ""
            askSources = emptyList()
            try {
                val result = withContext(dbDispatcher) {
                    val current = index ?: return@withContext AskResult.NotCalled("Index is not open.")
                    AskPipeline(current, client()).ask(asked, settings.ollamaNumCtx)
                }
                when (result) {
                    is AskResult.NoMatch -> askMessage = result.message
                    is AskResult.NotCalled -> askMessage = result.message
                    is AskResult.Answer -> {
                        answer = result.text
                        askSources = result.sourcePaths
                        askMessage = ""
                    }
                }
            } catch (caught: Exception) {
                askMessage = caught.message ?: "Ask failed."
            } finally {
                busy = false
            }
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

    fun confirmWrites(checked: Set<String>) {
        val draft = proposal ?: return
        proposal = null
        val root = wikiRoot ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    applyProposal(Path.of(root), draft.pages, checked, draft.logTitle, draft.operation, LocalDate.now())
                }
                val snapshot = withContext(dbDispatcher) {
                    index?.reconcileAll()
                    (index?.noteCount() ?: 0) to (index?.listSummaries().orEmpty())
                }
                noteCount = snapshot.first
                summaries = snapshot.second
                rawFiles = withContext(Dispatchers.IO) { listMarkdownUnder(Path.of(root), "raw") }
                treeTick += 1
                if (result.failed != null) error = result.failed else ingestMessage = "Wrote ${result.written.joinToString(", ")}."
            } catch (caught: Exception) {
                error = caught.message ?: "Write failed."
            } finally {
                busy = false
            }
        }
    }

    fun skipQueued() {
        val rest = queue.drop(1)
        queue = rest
        claims = emptyList()
        if (rest.isNotEmpty()) selectRaw(rest.first())
    }

    fun close() {
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
            rawFiles = withContext(Dispatchers.IO) { listMarkdownUnder(root, "raw") }
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
        appSettings.save(updated)
        settings = updated
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
