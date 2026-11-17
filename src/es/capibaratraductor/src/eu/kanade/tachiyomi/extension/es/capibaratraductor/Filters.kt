package eu.kanade.tachiyomi.extension.es.capibaratraductor

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter(
    private val options: Array<Pair<String, String>> = SORT_OPTIONS,
) : Filter.Select<String>("Ordenar por", options.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = options[state].second

    companion object {
        val SORT_OPTIONS = arrayOf(
            "Relevancia / Destacados" to "default",
            "Título (A-Z)" to "az",
            "Título (Z-A)" to "za",
        )
    }
}

class ScanFilter : Filter.Text("Scan / Traductor (opcional)")
