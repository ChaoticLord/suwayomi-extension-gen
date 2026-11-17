package eu.kanade.tachiyomi.extension.es.capibaratraductor

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter : Filter.Select<String>(
    "Ordenar por",
    OPTIONS.map { it.first }.toTypedArray(),
) {
    val selectedValue: String
        get() = OPTIONS.getOrNull(state)?.second ?: "popular"

    companion object {
        private val OPTIONS = arrayOf(
            "Populares" to "popular",
            "Más recientes" to "latest",
            "Alfabético (A-Z)" to "az",
        )
    }
}

class ScanFilter : Filter.Text("Scanlation (nombre o slug)")

class StatusFilter : Filter.Select<String>(
    "Estado",
    OPTIONS.map { it.first }.toTypedArray(),
) {
    val selectedValue: String
        get() = OPTIONS.getOrNull(state)?.second ?: ""

    companion object {
        private val OPTIONS = arrayOf(
            "Todos" to "",
            "En emisión" to "ongoing",
            "Finalizado" to "completed",
            "Pausado" to "hiatus",
            "Cancelado" to "cancelled",
        )
    }
}

class LanguageFilter : Filter.Select<String>(
    "Idioma",
    OPTIONS.map { it.first }.toTypedArray(),
) {
    val selectedValue: String
        get() = OPTIONS.getOrNull(state)?.second ?: ""

    companion object {
        private val OPTIONS = arrayOf(
            "Todos" to "",
            "Español" to "es",
        )
    }
}
