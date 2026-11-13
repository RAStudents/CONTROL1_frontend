package studio.acmenra.control.core.ui.notice

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class NoticeType { Info, Success, Error, Offline }

object NoticeDefaults {
    val MaxVisibleNotices = 3
    val InfoDurationMs = 4000L
    val ErrorDurationMs = 6000L
    val EnterScale = 0.92f
    val PulseScale = 1.12f
    val PulseDurationMs = 900
    val TimerAlpha = 0.35f

    // null means the notice stays until dismissed
    fun durationFor(type: NoticeType): Long? =
        when (type) {
            NoticeType.Offline -> null
            NoticeType.Error -> ErrorDurationMs
            NoticeType.Info, NoticeType.Success -> InfoDurationMs
        }
}

// Resource for strings from composeResources, Raw only for text that came from the server
sealed interface NoticeText {
    data class Resource(
        val resource: StringResource,
        val args: List<Any> = emptyList(),
    ) : NoticeText

    data class Raw(
        val value: String,
    ) : NoticeText
}

@Composable
internal fun NoticeText.resolve(): String =
    when (this) {
        is NoticeText.Resource -> stringResource(resource, *args.toTypedArray())
        is NoticeText.Raw -> value
    }

// Lives in a State field, is shown through ConsumeNotice and then reset by the ViewModel.
// key dedupes: a notice with the same key is ignored while one is current or queued.
// id is assigned by NoticeController.
data class Notice(
    val type: NoticeType,
    val title: NoticeText,
    val message: NoticeText? = null,
    val durationMs: Long? = NoticeDefaults.durationFor(type),
    val key: String? = null,
    val id: Long = 0L,
) {
    companion object {
        fun info(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
        ) = Notice(NoticeType.Info, NoticeText.Resource(title), message?.let { NoticeText.Resource(it) }, key = key)

        fun success(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
        ) = Notice(NoticeType.Success, NoticeText.Resource(title), message?.let { NoticeText.Resource(it) }, key = key)

        fun error(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
        ) = Notice(NoticeType.Error, NoticeText.Resource(title), message?.let { NoticeText.Resource(it) }, key = key)

        fun error(
            title: StringResource,
            serverMessage: String,
            key: String? = null,
        ) = Notice(NoticeType.Error, NoticeText.Resource(title), NoticeText.Raw(serverMessage), key = key)
    }
}

// Single instance per app (Koin single). Call from the main thread only.
class NoticeController {
    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current.asStateFlow()

    // All live NoticeHost instances. Only the last one (topmost layer) renders the notice.
    private val _hosts = MutableStateFlow<List<Any>>(emptyList())
    val hosts: StateFlow<List<Any>> = _hosts.asStateFlow()

    private var nextId = 1L

    fun show(notice: Notice) {
        val key = notice.key
        if (key != null && _notices.value.any { it.key == key }) return
        val queued = notice.copy(id = nextId++)
        _notices.update { currentList ->
            val trimmed =
                if (currentList.size >= NoticeDefaults.MaxVisibleNotices) {
                    currentList.drop(currentList.size - NoticeDefaults.MaxVisibleNotices + 1)
                } else {
                    currentList
                }
            trimmed + queued
        }
        _current.value = _notices.value.lastOrNull()
    }

    fun dismiss(id: Long) {
        _notices.update { list -> list.filterNot { it.id == id } }
        _current.value = _notices.value.lastOrNull()
    }

    fun dismissKey(key: String) {
        _notices.update { list -> list.filterNot { it.key == key } }
        _current.value = _notices.value.lastOrNull()
    }

    internal fun attachHost(token: Any) {
        _hosts.update { it + token }
    }

    internal fun detachHost(token: Any) {
        _hosts.update { it - token }
    }
}
