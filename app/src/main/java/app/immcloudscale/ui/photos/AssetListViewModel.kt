package app.immcloudscale.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.data.immich.MemoryDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import app.immcloudscale.data.immich.groupByDay
import app.immcloudscale.data.immich.DaySection
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.immcloudscale.data.backup.PhoneCopies
import app.immcloudscale.data.backup.LocalMediaSource

data class AssetListState(
    val assets: List<AssetDto> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val memories: List<MemoryDto> = emptyList(),
    val message: String? = null,
) {
    val selecting get() = selected.isNotEmpty()
}

/** Infinite-scrolling list of one library slice, plus multi-select actions. */
class AssetListViewModel(private val graph: AppGraph, val query: AssetQuery) : ViewModel() {
    private val _state = MutableStateFlow(AssetListState())
    val state: StateFlow<AssetListState> = _state.asStateFlow()
    /** The assets grouped into days, worked out off the main thread whenever they change (ready on return to the screen). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sections: StateFlow<List<DaySection>> = _state.map { it.assets }.distinctUntilChanged()
        .mapLatest { withContext(Dispatchers.Default) { groupByDay(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var page = 1
    private var job: Job? = null
    private var loadedAt = 0L
    // The timeline is kept on the phone (see TimelineCache) and checked in full now and then.
    private val timeline = query == AssetQuery.Timeline
    private var fullSyncAt = 0L
    private var listKey: Pair<String, String>? = null // whose timeline is showing
    // Edits made while a check is running, re-applied to what it fetched (fetched before them).
    private val removedMeanwhile = HashSet<String>()
    private val editedMeanwhile = HashMap<String, AssetDto>()
    private var pending: List<AssetDto> = emptyList() // this phone's photos waiting for backup

    init {
        // Reload whenever the server connection changes (pairing, signing in, switching servers), not just once:
        // a list loaded before pairing, or straight after it with an empty library, would otherwise stay empty.
        viewModelScope.launch {
            graph.settingsState.map { Triple(it.immichUrl, it.immichToken, it.immichEmail) }.distinctUntilChanged().collect { refresh() }
        }
    }

    /** Reload when coming back to the screen after a while, so photos backed up meanwhile show up. */
    fun refreshIfStale(maxAgeMs: Long = 30_000) {
        if (timeline && job?.isActive == true) return // a check is still running: let it finish
        if (System.currentTimeMillis() - loadedAt > maxAgeMs && !_state.value.loading) refresh()
    }

    fun refresh() {
        job?.cancel()
        page = 1
        loadedAt = System.currentTimeMillis()
        if (timeline) return refreshTimeline()
        _state.update { it.copy(refreshing = it.assets.isNotEmpty(), endReached = false, error = null) }
        if (query == AssetQuery.Timeline) viewModelScope.launch {
            runCatching { graph.immich.memories() }.onSuccess { m -> _state.update { it.copy(memories = m.filter { x -> x.assets.isNotEmpty() }) } }
        }
        loadMore(reset = true)
    }

    fun loadMore(reset: Boolean = false) {
        if (timeline && !reset) return // the whole timeline is loaded at once
        val s = _state.value
        if (!reset && (s.loading || s.endReached)) return
        if (!graph.settingsState.value.immichConfigured) {
            _state.update { it.copy(assets = emptyList(), memories = emptyList(), loading = false, refreshing = false, endReached = true) }
            return
        }
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            try {
                val p = graph.immich.assets(query, page)
                page++
                _state.update {
                    val merged = if (reset) p.items else (it.assets + p.items).distinctBy { a -> a.id }
                    it.copy(assets = merged, loading = false, refreshing = false, endReached = p.nextPage == null || p.items.isEmpty(), error = null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load photos") }
            }
        }
    }

    private fun cacheKey(): Pair<String, String> = graph.settingsState.value.let { it.immichUrl to it.immichEmail.ifBlank { it.immichUserName } }

    /**
     * Shows the saved timeline straight away, then brings it up to date: the newest photos every time, the whole
     * library at most every half hour (that's also when deletions made elsewhere disappear).
     */
    private fun refreshTimeline() {
        val s = graph.settingsState.value
        if (!s.immichConfigured) {
            listKey = null
            _state.update { it.copy(assets = emptyList(), memories = emptyList(), loading = false, refreshing = false, endReached = true) }
            return
        }
        viewModelScope.launch {
            runCatching { graph.immich.memories() }.onSuccess { m -> _state.update { it.copy(memories = m.filter { x -> x.assets.isNotEmpty() }) } }
        }
        val key = cacheKey()
        if (key != listKey) {
            // Another account or server: nothing of the previous one may show or be saved under this one.
            listKey = key
            fullSyncAt = 0
            _state.update { it.copy(assets = emptyList(), memories = emptyList(), selected = emptySet()) }
        }
        removedMeanwhile.clear()
        editedMeanwhile.clear()
        job = viewModelScope.launch {
            var known = _state.value.assets.filter { !it.isLocal }
            // Without the account's address yet (mid sign-in), nothing is loaded or saved: it could be someone else's.
            val keyed = key.second.isNotBlank()
            if (known.isEmpty() && keyed) graph.timelineCache.load(key.first, key.second)?.let { saved ->
                known = saved.assets
                fullSyncAt = saved.synced
            }
            pending = runCatching { pendingOnPhone(known) }.getOrDefault(emptyList())
            if (known.isNotEmpty()) show(known, keepRefreshing = true)
            _state.update { it.copy(refreshing = it.assets.isNotEmpty(), loading = it.assets.isEmpty(), endReached = true, error = null) }
            try {
                val head = graph.immich.assets(query, 1, HEAD).items
                known = withContext(Dispatchers.Default) { mergeNewest(known, head) }
                pending = runCatching { pendingOnPhone(known) }.getOrDefault(emptyList()) // just backed up: not pending any more
                show(known)
                if (System.currentTimeMillis() - fullSyncAt > FULL_EVERY || known.size <= head.size) {
                    val all = withContext(Dispatchers.Default) {
                        val all = ArrayList<AssetDto>()
                        var p = 1
                        while (true) {
                            val pg = graph.immich.assets(query, p, PAGE)
                            all += pg.items
                            if (pg.nextPage == null || pg.items.isEmpty()) break
                            p++
                        }
                        all.distinctBy { it.id } // a photo arriving during paging shifts the pages by one
                    }
                    fullSyncAt = System.currentTimeMillis()
                    known = all
                    pending = runCatching { pendingOnPhone(known) }.getOrDefault(emptyList())
                    show(known)
                }
                if (keyed && cacheKey() == key) graph.timelineCache.save(key.first, key.second, _state.value.assets, fullSyncAt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // With a saved timeline there's still something to show: only say so when there's nothing.
                _state.update { it.copy(loading = false, refreshing = false, error = if (it.assets.isEmpty()) e.message ?: "Couldn't load photos" else null) }
            }
        }
    }

    /** Shows [server] (with edits made meanwhile) and this phone's photos waiting for backup, newest first. */
    private suspend fun show(server: List<AssetDto>, keepRefreshing: Boolean = false) {
        val merged = withContext(Dispatchers.Default) {
            val fixed = server.mapNotNull { a -> if (a.id in removedMeanwhile) null else editedMeanwhile[a.id] ?: a }
            if (pending.isEmpty()) fixed else mergeByDate(fixed, pending)
        }
        _state.update { it.copy(assets = merged, loading = false, refreshing = if (keepRefreshing) it.refreshing else false) }
    }

    private suspend fun pendingOnPhone(server: List<AssetDto>): List<AssetDto> = withContext(Dispatchers.IO) {
        val s = graph.settingsState.value
        if (!s.backupEnabled) return@withContext emptyList()
        val mine = PhoneCopies.deviceId(graph.context)
        val done = server.asSequence().filter { it.deviceId == mine }.map { it.deviceAssetId }.toHashSet()
        // Already on the server under the same name and day (from another app, or skipped by backup as a duplicate).
        val named = server.asSequence().map { it.originalFileName.lowercase() + "|" + it.fileCreatedAt.take(10) }.toHashSet()
        LocalMediaSource.query(graph.context, s.backupSince, s.backupVideos, s.backupFolders)
            .filter { m -> "${m.id}-${m.size}" !in done }
            .map { m ->
                AssetDto(
                    id = "local:${m.id}", type = if (m.mime.startsWith("video")) "VIDEO" else "IMAGE", originalFileName = m.name,
                    fileCreatedAt = Instant.ofEpochMilli(if (m.dateTaken > 0) m.dateTaken else m.dateAdded * 1000).toString(),
                    localUri = m.uri.toString(),
                )
            }
            .filter { a -> a.originalFileName.lowercase() + "|" + a.fileCreatedAt.take(10) !in named }
            .sortedByDescending { it.takenAt() }
    }

    private fun persist() {
        if (!timeline) return
        val key = listKey ?: return
        if (key != cacheKey() || key.second.isBlank()) return
        val assets = _state.value.assets
        viewModelScope.launch { runCatching { graph.timelineCache.save(key.first, key.second, assets, fullSyncAt) } }
    }

    // Photos only on the phone can't be selected: the actions all work on the server.
    fun toggle(id: String) = _state.update { s ->
        if (id.startsWith(LOCAL)) s else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }
    fun selectAll(ids: Collection<String>) = _state.update { s -> s.copy(selected = s.selected + ids.filterNot { it.startsWith(LOCAL) }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun act(label: String, removeFromList: Boolean, onDone: () -> Unit = {}, block: suspend (List<String>) -> Unit) {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                block(ids)
                onDone()
                if (removeFromList) removedMeanwhile += ids
                _state.update { s ->
                    s.copy(
                        selected = emptySet(),
                        assets = if (removeFromList) s.assets.filter { it.id !in ids } else s.assets,
                        message = "$label ${ids.size} item${if (ids.size == 1) "" else "s"}",
                    )
                }
                persist()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Something went wrong") }
            }
        }
    }

    fun favoriteSelected(fav: Boolean = true) = act(if (fav) "Added to favorites:" else "Removed from favorites:", query == AssetQuery.Favorites && !fav) {
        graph.immich.setFavorite(it, fav)
        _state.update { s -> s.copy(assets = s.assets.map { a -> if (a.id in it) a.copy(isFavorite = fav).also { e -> editedMeanwhile[e.id] = e } else a }) }
    }
    fun archiveSelected(archive: Boolean = true) = act(if (archive) "Archived" else "Unarchived", true) { graph.immich.setArchived(it, archive) }
    /** [onDone] runs once the server has them in its trash (the phone's copies follow then). */
    fun trashSelected(onDone: () -> Unit = {}) = act("Moved to trash:", true, onDone) { graph.immich.delete(it) }
    fun lockSelected(onDone: () -> Unit = {}) = act("Moved to Locked folder:", true, onDone) { graph.immich.setVisibility(it, "locked") }
    fun deleteForeverSelected() = act("Deleted", true) { graph.immich.delete(it, force = true) }
    fun restoreSelected() = act("Restored", true) { graph.immich.restore(it) }
    fun addSelectedToAlbum(albumId: String) = act("Added to album:", false) { graph.immich.addToAlbum(albumId, it) }
    fun newAlbumFromSelected(name: String) = act("Created \"$name\" with", false) { graph.immich.createAlbum(name, it) }
    fun shareLinkForSelected(onLink: (String) -> Unit) {
        val ids = _state.value.selected.toList()
        viewModelScope.launch {
            runCatching { graph.immich.createShareLink(ids) }
                .onSuccess { clearSelection(); onLink(it) }
                .onFailure { e -> _state.update { it.copy(message = e.message) } }
        }
    }

    fun emptyTrash() = viewModelScope.launch {
        runCatching { graph.immich.emptyTrash() }
            .onSuccess { _state.update { it.copy(assets = emptyList(), message = "Trash emptied") } }
            .onFailure { e -> _state.update { it.copy(message = e.message) } }
    }

    /** Keeps the list in sync after the viewer changed or removed an asset. */
    fun applyEdit(updated: AssetDto?, removedId: String?) {
        removedId?.let { removedMeanwhile += it }
        updated?.let { editedMeanwhile[it.id] = it }
        _state.update { s ->
            s.copy(assets = s.assets.mapNotNull { a ->
                when {
                    a.id == removedId -> null
                    updated != null && a.id == updated.id -> updated
                    else -> a
                }
            })
        }
        persist()
    }
}

private const val LOCAL = "local:"
private const val HEAD = 200 // newest photos checked on every open
private const val PAGE = 1000
private const val FULL_EVERY = 30 * 60_000L

private fun AssetDto.takenAt(): Instant = runCatching { Instant.parse(fileCreatedAt) }.getOrDefault(Instant.EPOCH)

/**
 * The saved timeline updated with the server's newest photos: those replace what was saved down to the oldest
 * of them (so photos deleted among them go too); older saved photos stay until the next full check.
 */
internal fun mergeNewest(saved: List<AssetDto>, newest: List<AssetDto>): List<AssetDto> {
    if (newest.isEmpty()) return saved.distinctBy { it.id }
    val cutoff = newest.minOf { it.takenAt() }
    val ids = newest.mapTo(HashSet()) { it.id }
    // At the cutoff itself, saved photos stay (several can share a time across the boundary).
    return (newest + saved.filter { it.id !in ids && it.takenAt() <= cutoff }).distinctBy { it.id }
}

/** Merges two lists that are each newest first into one, working out each time once. */
internal fun mergeByDate(a: List<AssetDto>, b: List<AssetDto>): List<AssetDto> {
    val ta = a.map { it.takenAt() }
    val tb = b.map { it.takenAt() }
    val out = ArrayList<AssetDto>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        if (j >= b.size || (i < a.size && ta[i] >= tb[j])) out += a[i++] else out += b[j++]
    }
    return out
}
