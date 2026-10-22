package com.enigmacast.companionbridge

import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Eén veld dat de telefoon mag bewerken. */
data class CompanionFormField(
    val id: String = "value",
    val label: String,
    val type: String,
    val value: String,
    val masked: Boolean,
)

/** Wat de brug naar de telefoon stuurt. */
data class CompanionFormState(
    val formId: String,
    val title: String,
    val revision: Long,
    val fields: List<CompanionFormField>,
    val closed: Boolean = false,
)

/**
 * Houdt de formsessie bij die hoort bij het tekstveld (of de meerveldslogin) op de TV.
 * De telefoon leest [state] via de brug en stuurt `cmd.form` terug.
 *
 * De host roept de UI-callbacks altijd op de main-thread aan: de brug verwerkt
 * commando's op een IO-thread en Compose-state hoort daar niet vandaan te komen.
 */
@Singleton
class CompanionFormHost @Inject constructor() {

    data class FieldSpec(
        val id: String,
        val label: String,
        val type: String,
        var value: String,
        val masked: Boolean = false,
        val onValueChange: (String) -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<CompanionFormState?>(null)
    val state: StateFlow<CompanionFormState?> = _state.asStateFlow()

    private var session: Session? = null
    private var nextId = 0
    private var revision = 0L

    /** Eén veld, gebruikt door [com.enigmacast.ui.theme.TvTextField]. */
    @Synchronized
    fun open(
        label: String,
        type: String,
        value: String,
        masked: Boolean,
        onValueChange: (String) -> Unit,
    ): String = openForm(
        title = label,
        fields = listOf(FieldSpec("value", label, type, value, masked, onValueChange)),
    )

    /** Meerdere velden tegelijk, bijvoorbeeld een ontvanger-login. */
    @Synchronized
    fun openForm(title: String, fields: List<FieldSpec>): String {
        session?.let { old -> revision++; _state.value = old.toState(revision, closed = true) }
        val id = "field-${++nextId}"
        revision++
        session = Session(id, title, fields.map { it.copy() }.toMutableList())
        android.util.Log.d("CompanionForm", "open $id title=$title fields=${fields.size}")
        publish()
        return id
    }

    /** De TV heeft zelf getypt; alleen de telefoon bijwerken, niet terugkoppelen. */
    @Synchronized
    fun sync(id: String, value: String) {
        val current = session ?: return
        if (current.id != id) return
        val field = current.fields.firstOrNull { it.id == "value" } ?: return
        if (field.value == value) return
        field.value = value
        revision++
        publish()
    }

    /** De telefoon typt in het enige veld. */
    @Synchronized
    fun update(id: String, text: String) = update(id, "value", text)

    /** De telefoon typt in één veld van een meerveldsformulier. */
    @Synchronized
    fun update(id: String, fieldId: String, text: String) {
        val current = session ?: return
        if (current.id != id) return
        val field = current.fields.firstOrNull { it.id == fieldId } ?: return
        if (field.masked) return
        if (field.value != text) {
            field.value = text
            revision++
            publish()
        }
        main.post { field.onValueChange(text) }
    }

    /** Klaar: laatste tekst toepassen en de sessie sluiten. */
    @Synchronized
    fun submit(id: String, text: String) = submit(id, mapOf("value" to text))

    /** Klaar: alle velden toepassen en de sessie sluiten. */
    @Synchronized
    fun submit(id: String, values: Map<String, String>) {
        val current = session ?: return
        if (current.id != id) return
        val callbacks = ArrayList<Pair<FieldSpec, String>>()
        current.fields.forEach { field ->
            val incoming = values[field.id]
            if (incoming == null) return@forEach
            if (field.masked && incoming.isEmpty()) return@forEach
            field.value = incoming
            callbacks += field to incoming
        }
        revision++
        closeLocked(current)
        callbacks.forEach { (field, value) -> main.post { field.onValueChange(value) } }
    }

    @Synchronized
    fun cancel(id: String) {
        val current = session ?: return
        if (current.id != id) return
        android.util.Log.d("CompanionForm", "cancel $id")
        revision++
        closeLocked(current)
    }

    @Synchronized
    fun close(id: String) {
        val current = session ?: return
        if (current.id != id) return
        android.util.Log.d("CompanionForm", "close $id")
        revision++
        closeLocked(current)
    }

    @Synchronized
    private fun closeLocked(current: Session) {
        session = null
        _state.value = current.toState(revision, closed = true)
    }

    private fun publish() {
        val current = session ?: return
        _state.value = current.toState(revision, closed = false)
    }

    private class Session(
        val id: String,
        val title: String,
        val fields: MutableList<FieldSpec>,
    ) {
        fun toState(revision: Long, closed: Boolean): CompanionFormState = CompanionFormState(
            formId = id,
            title = title,
            revision = revision,
            fields = fields.map { field ->
                CompanionFormField(
                    id = field.id,
                    label = field.label,
                    type = field.type,
                    value = if (field.masked) "" else field.value,
                    masked = field.masked,
                )
            },
            closed = closed,
        )
    }
}
