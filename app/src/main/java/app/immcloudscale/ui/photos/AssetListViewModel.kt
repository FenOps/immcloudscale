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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var page = 1
    private var job: Job? = null
    private var loadedAt = 0L

    init {
        // Reload whenever the server connection changes (pairing, signing in, switching servers), not just once:
        // a list loaded before pairing, or straight after it with an empty library, would otherwise stay empty.
        viewModelScope.launch {
            graph.settingsState.map { it.immichUrl to it.immichToken }.distinctUntilChanged().collect { refresh() }
        }
    }

    /** Reload when coming back to the screen after a while, so photos backed up meanwhile show up. */
    fun refreshIfStale(maxAgeMs: Long = 30_000) {
        if (System.currentTimeMillis() - loadedAt > maxAgeMs && !_state.value.loading) refresh()
    }

    fun refresh() {
        job?.cancel()
        page = 1
        loadedAt = System.currentTimeMillis()
        _state.update { it.copy(refreshing = it.assets.isNotEmpty(), endReached = false, error = null) }
        if (query == AssetQuery.Timeline) viewModelScope.launch {
            runCatching { graph.immich.memories() }.onSuccess { m -> _state.update { it.copy(memories = m.filter { x -> x.assets.isNotEmpty() }) } }
        }
        loadMore(reset = true)
    }

    fun loadMore(reset: Boolean = false) {
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

    fun toggle(id: String) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun selectAll(ids: Collection<String>) = _state.update { s -> s.copy(selected = s.selected + ids) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun act(label: String, removeFromList: Boolean, block: suspend (List<String>) -> Unit) {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                block(ids)
                _state.update { s ->
                    s.copy(
                        selected = emptySet(),
                        assets = if (removeFromList) s.assets.filter { it.id !in ids } else s.assets,
                        message = "$label ${ids.size} item${if (ids.size == 1) "" else "s"}",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Something went wrong") }
            }
        }
    }

    fun favoriteSelected(fav: Boolean = true) = act(if (fav) "Added to favorites:" else "Removed from favorites:", query == AssetQuery.Favorites && !fav) {
        graph.immich.setFavorite(it, fav)
        _state.update { s -> s.copy(assets = s.assets.map { a -> if (a.id in it) a.copy(isFavorite = fav) else a }) }
    }
    fun archiveSelected(archive: Boolean = true) = act(if (archive) "Archived" else "Unarchived", true) { graph.immich.setArchived(it, archive) }
    fun trashSelected() = act("Moved to trash:", true) { graph.immich.delete(it) }
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
    fun applyEdit(updated: AssetDto?, removedId: String?) = _state.update { s ->
        s.copy(assets = s.assets.mapNotNull { a ->
            when {
                a.id == removedId -> null
                updated != null && a.id == updated.id -> updated
                else -> a
            }
        })
    }
}
