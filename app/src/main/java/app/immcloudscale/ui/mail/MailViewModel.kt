package app.immcloudscale.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.HttpException
import app.immcloudscale.data.mail.MailDeleteResult
import app.immcloudscale.data.mail.MailItem
import app.immcloudscale.data.mail.MailQuery
import app.immcloudscale.data.mail.MailStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MailState(
    val status: MailStatus? = null,
    val query: MailQuery = MailQuery(),
    val items: List<MailItem> = emptyList(),
    val page: Int = 0,
    val more: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(), // "account/id"
    val deleting: Boolean = false,
    val message: String? = null,
    val needPassword: String? = null, // a mailbox whose password the server needs before it can delete there
)

fun MailItem.key() = "$account/$id"

class MailViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(MailState())
    val state: StateFlow<MailState> = _state
    private var job: Job? = null

    /**
     * Brings the mailboxes up to date; the list is reloaded only when [full] (pull to refresh) or still empty, so
     * coming back from a message keeps the place in a long list.
     */
    fun refresh(full: Boolean = false) {
        viewModelScope.launch {
            MailSession.deleted.toList().forEach { (a, id) -> removed(a, id) }
            MailSession.deleted.clear()
            val st = runCatching { g.mail.status() }
            st.onSuccess { s -> _state.update { it.copy(status = s, error = null) } }
                .onFailure { e -> if (_state.value.status == null) _state.update { it.copy(error = e.message ?: "Couldn't reach your server") } }
            if (st.getOrNull()?.accounts?.isNotEmpty() == true && (full || _state.value.items.isEmpty())) load(reset = true)
        }
    }

    fun search(text: String) {
        if (text == _state.value.query.text) return
        _state.update { it.copy(query = it.query.copy(text = text)) }
        load(reset = true, debounce = true)
    }
    fun filterAccount(id: String?) { _state.update { it.copy(query = it.query.copy(account = id)) }; load(reset = true) }
    fun filterAttachments(on: Boolean) { _state.update { it.copy(query = it.query.copy(hasAttachment = on)) }; load(reset = true) }

    fun loadMore() {
        val s = _state.value
        if (s.more && !s.loading) load(reset = false)
    }

    private fun load(reset: Boolean, debounce: Boolean = false) {
        if (reset) job?.cancel() else if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (debounce) delay(350) // wait for the typing to pause
            val s = _state.value
            val page = if (reset) 1 else s.page + 1
            _state.update { it.copy(loading = true, error = null) }
            try {
                val p = g.mail.search(s.query, page)
                _state.update { st ->
                    val items = if (reset) p.items else (st.items + p.items).distinctBy { it.key() }
                    st.copy(items = items, page = page, more = p.more, loading = false, selected = if (reset) emptySet() else st.selected)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't search your mail") }
            }
        }
    }

    fun toggle(m: MailItem) = _state.update { s -> s.copy(selected = if (m.key() in s.selected) s.selected - m.key() else s.selected + m.key()) }
    fun selectAll() = _state.update { s -> s.copy(selected = s.items.mapTo(HashSet()) { it.key() }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun dismissPassword() = _state.update { it.copy(needPassword = null) }

    /** Deletes the picked messages from the mail server and the archive. */
    fun deleteSelected() = delete(_state.value.selected.map { it.substringBefore('/') to it.substringAfter('/') })

    fun delete(messages: List<Pair<String, String>>) {
        if (messages.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            try {
                // In parts: the server takes up to 100 at a time, and each part is answered before the phone gives up.
                var r = MailDeleteResult()
                for (part in messages.chunked(50)) {
                    val p = g.mail.delete(part)
                    r = MailDeleteResult(r.deleted + p.deleted, r.failed + p.failed)
                }
                val failed = r.failed.mapTo(HashSet()) { "${it.account}/${it.id}" }
                val gone = messages.map { "${it.first}/${it.second}" }.filterTo(HashSet()) { it !in failed }
                _state.update { s ->
                    s.copy(
                        items = s.items.filter { it.key() !in gone }, selected = s.selected - gone, deleting = false,
                        message = when {
                            r.failed.isEmpty() -> if (r.deleted == 1) "Email deleted" else "${r.deleted} emails deleted"
                            r.deleted == 0 -> "Couldn't delete: ${r.failed.first().error}"
                            else -> "${r.deleted} deleted, ${r.failed.size} couldn't be: ${r.failed.first().error}"
                        },
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 409: a mailbox added in Bichon's own page, whose password the server doesn't have yet.
                val picked = messages.map { it.first }.toSet()
                val needs = if (e is HttpException && e.code == 409) {
                    _state.value.status?.accounts?.firstOrNull { it.id in picked && !it.deletesOnServer }?.id ?: messages.first().first
                } else null
                _state.update { it.copy(deleting = false, needPassword = needs, message = if (needs == null) e.message ?: "Couldn't delete" else null) }
            }
        }
    }

    /** Saves a mailbox's password so deleting reaches the mail server, then deletes what was picked. */
    fun savePasswordAndDelete(account: String, password: String) {
        viewModelScope.launch {
            try {
                g.mail.setPassword(account, password)
                _state.update { it.copy(needPassword = null) }
                deleteSelected()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(message = e.message ?: "That password didn't work") }
            }
        }
    }

    /** A message deleted from its own screen: take it out of the list too. */
    fun removed(account: String, id: String) = _state.update { s -> s.copy(items = s.items.filterNot { it.account == account && it.id == id }) }
}
