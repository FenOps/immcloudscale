package app.immcloudscale.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.nextcloud.CloudFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FilesTab(val label: String) { MyFiles("My Files"), Recent("Recent"), Starred("Starred"), Shared("Shared"), Offline("Offline") }
enum class SortBy(val label: String) { Name("Name"), Modified("Last modified"), Size("Storage used") }

data class Transfer(val name: String, val progress: Float?)

data class FilesState(
    val tab: FilesTab = FilesTab.MyFiles,
    val path: String = "/",
    val items: List<CloudFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sort: SortBy = SortBy.Name,
    val ascending: Boolean = true,
    val search: String? = null,
    val offlinePaths: Set<String> = emptySet(),
    val transfer: Transfer? = null,
    val message: String? = null,
    val selected: Set<String> = emptySet(), // paths picked with a long press
) {
    val selectedFiles: List<CloudFile> get() = items.filter { it.path in selected }

    val sorted: List<CloudFile>
        get() {
            val cmp: Comparator<CloudFile> = when (sort) {
                SortBy.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortBy.Modified -> compareBy { it.modified }
                SortBy.Size -> compareBy { it.size }
            }
            val ordered = items.sortedWith(if (ascending) cmp else cmp.reversed())
            // Drive keeps folders first in My Files; recent/search lists stay in server order.
            return if (tab == FilesTab.MyFiles && search == null) ordered.sortedBy { !it.isFolder } else if (tab == FilesTab.Recent) items else ordered
        }
    val breadcrumbs: List<Pair<String, String>>
        get() {
            val parts = path.split('/').filter { it.isNotEmpty() }
            return listOf("My Files" to "/") + parts.indices.map { i -> parts[i] to "/" + parts.take(i + 1).joinToString("/") }
        }
}

class FilesViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(FilesState(offlinePaths = g.offline.all().map { it.first }.toSet()))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var loadedAt = 0L

    init {
        // Reload whenever the server connection changes, so a list opened before pairing doesn't stay empty.
        viewModelScope.launch {
            g.settingsState.map { Triple(it.ncUrl, it.ncUser, it.ncAppPassword) }.distinctUntilChanged().collect { load() }
        }
    }

    /** Reload when coming back to the screen after a while. */
    fun loadIfStale(maxAgeMs: Long = 30_000) {
        if (System.currentTimeMillis() - loadedAt > maxAgeMs && !_state.value.loading) load()
    }

    fun load() {
        job?.cancel()
        val s = _state.value
        loadedAt = System.currentTimeMillis()
        if (!g.settingsState.value.nextcloudConfigured) {
            _state.update { it.copy(items = emptyList(), loading = false, error = null) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val items = when {
                    s.search != null -> g.nextcloud.search(s.search)
                    s.tab == FilesTab.MyFiles -> g.nextcloud.list(s.path)
                    s.tab == FilesTab.Recent -> g.nextcloud.search(null, 40)
                    s.tab == FilesTab.Starred -> g.nextcloud.favorites()
                    s.tab == FilesTab.Shared -> g.nextcloud.shares(mine = false).plus(g.nextcloud.shares(mine = true))
                        .map { it.path }.distinct().take(60)
                        .map { p -> async { runCatching { g.nextcloud.stat(p) }.getOrNull() } }.awaitAll().filterNotNull()
                    else -> g.offline.all().map { (p, f) ->
                        CloudFile(p, f.name, false, f.length(), f.lastModified(), null, null, false, false, false)
                    }
                }
                val paths = items.mapTo(HashSet()) { it.path }
                _state.update { st -> st.copy(items = items, loading = false, selected = st.selected.filterTo(HashSet()) { it in paths }) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load files") }
            }
        }
    }

    fun selectTab(t: FilesTab) { _state.update { it.copy(tab = t, path = "/", search = null, items = emptyList(), selected = emptySet()) }; load() }
    fun open(folder: String) { _state.update { it.copy(tab = FilesTab.MyFiles, path = folder, search = null, items = emptyList(), selected = emptySet()) }; load() }
    fun search(q: String?) { _state.update { it.copy(search = q?.takeIf { s -> s.isNotBlank() }, selected = emptySet()) }; load() }

    fun toggleSelect(f: CloudFile) = _state.update { it.copy(selected = if (f.path in it.selected) it.selected - f.path else it.selected + f.path) }
    fun selectAll() = _state.update { it.copy(selected = it.items.mapTo(HashSet()) { f -> f.path }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Runs [op] on each picked file in turn; one that fails doesn't stop the rest. */
    private fun forSelected(done: (Int) -> String, reload: Boolean = true, op: suspend (CloudFile, String) -> Unit) = viewModelScope.launch {
        val chosen = _state.value.selectedFiles
        if (chosen.isEmpty()) return@launch // all gone in a reload meanwhile
        _state.update { it.copy(selected = emptySet()) }
        var failed = 0
        var lastError: String? = null
        chosen.forEachIndexed { i, f ->
            val title = if (chosen.size > 1) "${i + 1} of ${chosen.size}: ${f.name}" else f.name
            _state.update { it.copy(transfer = Transfer(title, null)) }
            try {
                op(f, title)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed++
                lastError = e.message
            }
        }
        val ok = chosen.size - failed
        val msg = when {
            failed == 0 -> done(ok)
            ok == 0 -> "Failed: ${lastError ?: "unknown error"}"
            else -> "$ok done, $failed failed: ${lastError ?: "unknown error"}"
        }
        _state.update { it.copy(transfer = null, message = msg) }
        if (reload) load()
    }

    fun moveSelected(folder: String, copy: Boolean) {
        val where = folder.substringAfterLast('/').ifEmpty { "My Files" }
        forSelected({ n -> if (copy) "${items(n)} copied to $where" else "${items(n)} moved to $where" }) { f, _ ->
            g.nextcloud.move(f.path, folder.trimEnd('/') + "/" + f.name, copy = copy)
        }
    }
    fun deleteSelected() = forSelected({ n -> "${items(n)} moved to the Nextcloud trash bin" }) { f, _ ->
        g.nextcloud.delete(f.path); g.offline.remove(f.path)
        _state.update { it.copy(offlinePaths = it.offlinePaths - f.path) }
    }
    /** Stars them all, or takes the stars off when every one already has one. */
    fun starSelected() {
        val on = !_state.value.selectedFiles.all { it.favorite }
        forSelected({ n -> if (on) "${items(n)} added to Starred" else "${items(n)} removed from Starred" }, reload = _state.value.tab == FilesTab.Starred) { f, _ ->
            g.nextcloud.setFavorite(f.path, on)
            _state.update { s -> s.copy(items = s.items.map { if (it.path == f.path) it.copy(favorite = on) else it }) }
        }
    }
    /** Keeps the picked files (not folders) on the phone, or removes the copies when they're all kept already. */
    fun offlineSelected() {
        val s = _state.value
        val files = s.selectedFiles.filter { !it.isFolder }
        if (files.isEmpty()) { _state.update { it.copy(selected = emptySet(), message = "Folders can't be kept offline; pick files") }; return }
        if (files.all { it.path in s.offlinePaths }) {
            files.forEach { g.offline.remove(it.path) }
            _state.update { st -> st.copy(selected = emptySet(), offlinePaths = st.offlinePaths - files.map { it.path }.toSet(), message = "Removed ${items(files.size)} from offline") }
            if (s.tab == FilesTab.Offline) load()
            return
        }
        val skipped = s.selectedFiles.size - files.size
        _state.update { it.copy(selected = files.mapTo(HashSet()) { f -> f.path }) }
        forSelected({ n -> "${items(n)} available offline" + if (skipped > 0) " (folders can't be kept offline)" else "" }, reload = false) { f, title ->
            if (f.path !in _state.value.offlinePaths) {
                g.nextcloud.download(f.path, g.offline.fileFor(f.path)) { p -> _state.update { it.copy(transfer = Transfer(title, p)) } }
                _state.update { it.copy(offlinePaths = it.offlinePaths + f.path) }
            }
        }
    }
    private fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    fun up(): Boolean {
        val s = _state.value
        if (s.search != null) { search(null); return true }
        if (s.tab == FilesTab.MyFiles && s.path != "/") { open(s.path.substringBeforeLast('/', "").ifEmpty { "/" }); return true }
        return false
    }
    fun sort(by: SortBy) = _state.update { if (it.sort == by) it.copy(ascending = !it.ascending) else it.copy(sort = by, ascending = by == SortBy.Name) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun action(msg: String?, reload: Boolean = true, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            _state.update { it.copy(message = msg) }
            if (reload) load()
        } catch (e: Exception) {
            _state.update { it.copy(message = e.message ?: "Something went wrong", transfer = null) }
        }
    }

    fun newFolder(name: String) = action("Folder created") { g.nextcloud.createFolder(_state.value.path.trimEnd('/') + "/" + name) }
    fun rename(f: CloudFile, name: String) = action("Renamed") { g.nextcloud.move(f.path, f.parent.trimEnd('/') + "/" + name) }
    fun moveTo(f: CloudFile, folder: String) = action("Moved to ${folder.substringAfterLast('/').ifEmpty { "My Files" }}") {
        g.nextcloud.move(f.path, folder.trimEnd('/') + "/" + f.name)
    }
    fun copyTo(f: CloudFile, folder: String) = action("Copy made") { g.nextcloud.move(f.path, folder.trimEnd('/') + "/" + f.name, copy = true) }
    fun delete(f: CloudFile) = action("${f.name} moved to the Nextcloud trash bin") {
        g.nextcloud.delete(f.path); g.offline.remove(f.path)
        _state.update { it.copy(offlinePaths = it.offlinePaths - f.path) }
    }
    fun star(f: CloudFile) = action(if (f.favorite) "Removed from Starred" else "Added to Starred", reload = false) {
        g.nextcloud.setFavorite(f.path, !f.favorite)
        _state.update { s ->
            val items = s.items.map { if (it.path == f.path) it.copy(favorite = !f.favorite) else it }
            s.copy(items = if (s.tab == FilesTab.Starred) items.filter { it.favorite } else items)
        }
    }
    fun shareLink(f: CloudFile, onLink: (String) -> Unit) = action(null, reload = false) { onLink(g.nextcloud.createShareLink(f.path)) }

    fun toggleOffline(f: CloudFile) {
        if (f.path in _state.value.offlinePaths) {
            g.offline.remove(f.path)
            _state.update { it.copy(offlinePaths = it.offlinePaths - f.path, message = "Removed offline copy") }
            if (_state.value.tab == FilesTab.Offline) load()
            return
        }
        download(f) { _state.update { it.copy(offlinePaths = it.offlinePaths + f.path, message = "${f.name} is available offline") } }
    }

    /** Downloads into the offline store (reused when the file is opened in another app). */
    fun download(f: CloudFile, then: (java.io.File) -> Unit) = viewModelScope.launch {
        val target = g.offline.fileFor(f.path)
        // Reuse the local copy only if it's still the server's version (same size and not older).
        if (target.exists() && target.length() == f.size && target.lastModified() >= f.modified) { then(target); return@launch }
        _state.update { it.copy(transfer = Transfer(f.name, 0f)) }
        try {
            g.nextcloud.download(f.path, target) { p -> _state.update { it.copy(transfer = Transfer(f.name, p)) } }
            _state.update { it.copy(transfer = null, offlinePaths = it.offlinePaths + f.path) }
            then(target)
        } catch (e: Exception) {
            _state.update { it.copy(transfer = null, message = e.message ?: "Download failed") }
        }
    }

    fun upload(uris: List<Uri>) = viewModelScope.launch {
        val folder = _state.value.path
        var failed = 0
        var lastError: String? = null
        uris.forEachIndexed { i, uri ->
            _state.update { it.copy(transfer = Transfer("Uploading ${i + 1} of ${uris.size}", null)) }
            try {
                g.uploadUri(uri, folder, forceFiles = true)
            } catch (e: Exception) {
                failed++
                lastError = e.message
            }
        }
        val ok = uris.size - failed
        val msg = when {
            failed == 0 -> "$ok item${if (ok == 1) "" else "s"} uploaded"
            ok == 0 -> "Upload failed: ${lastError ?: "unknown error"}"
            else -> "$ok uploaded, $failed failed: ${lastError ?: "unknown error"}"
        }
        _state.update { it.copy(transfer = null, message = msg) }
        load()
    }
}
