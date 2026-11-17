package eu.kanade.tachiyomi.extension.es.capibaratraductor

import eu.kanade.tachiyomi.source.model.Filter

class SelectFilterOption(val name: String, val value: String)

open class UriSelectFilter(
    displayName: String,
    private val options: Array<SelectFilterOption>,
) : Filter.Select<String>(displayName, options.map { it.name }.toTypedArray()) {
    fun selectedValue(): String = options[state].value
}

class SortFilter : UriSelectFilter(
    "Ordenar por",
    arrayOf(
        SelectFilterOption("Mas populares", "popular"),
        SelectFilterOption("Ultimas actualizaciones", "latest"),
        SelectFilterOption("Mejor valorados", "rating"),
        SelectFilterOption("Alfabetico (A-Z)", "alphabetical"),
    ),
)

class StatusFilter : UriSelectFilter(
    "Estado",
    arrayOf(
        SelectFilterOption("Todos", ""),
        SelectFilterOption("En emision", "ONGOING"),
        SelectFilterOption("Finalizado", "COMPLETED"),
        SelectFilterOption("En pausa", "ON_HIATUS"),
        SelectFilterOption("Cancelado", "CANCELLED"),
    ),
)

class TypeFilter : UriSelectFilter(
    "Formato",
    arrayOf(
        SelectFilterOption("Todos", ""),
        SelectFilterOption("Manga", "manga"),
        SelectFilterOption("Manhwa", "manhwa"),
        SelectFilterOption("Manhua", "manhua"),
        SelectFilterOption("One-shot", "one_shot"),
    ),
)

class GenreFilter : UriSelectFilter(
    "Genero",
    arrayOf(
        SelectFilterOption("Todos", ""),
        SelectFilterOption("Accion", "Accion"),
        SelectFilterOption("Aventura", "Aventura"),
        SelectFilterOption("Comedia", "Comedia"),
        SelectFilterOption("Drama", "Drama"),
        SelectFilterOption("Fantasia", "Fantasia"),
        SelectFilterOption("Isekai", "Isekai"),
        SelectFilterOption("Romance", "Romance"),
        SelectFilterOption("Sci-Fi", "Sci-Fi"),
        SelectFilterOption("Seinen", "Seinen"),
        SelectFilterOption("Shoujo", "Shoujo"),
        SelectFilterOption("Shounen", "Shounen"),
        SelectFilterOption("Slice of Life", "Slice of Life"),
        SelectFilterOption("Sobrenatural", "Sobrenatural"),
        SelectFilterOption("Terror", "Terror"),
    ),
)
