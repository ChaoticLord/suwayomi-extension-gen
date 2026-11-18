package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    displayName: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    fun selectedValue(): String = options[state].second
}

class StatusFilter : SelectFilter(
    "Estado",
    listOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "En pausa" to "hiatus",
    ),
)

class TypeFilter : SelectFilter(
    "Tipo",
    listOf(
        "Todos" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Comic" to "comic",
    ),
)

class OrderFilter : SelectFilter(
    "Ordenar por",
    listOf(
        "Por defecto" to "",
        "Popular" to "popular",
        "Últimas actualizaciones" to "update",
        "Recién agregados" to "latest",
        "A - Z" to "title",
        "Z - A" to "titlereverse",
    ),
)

class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<GenreCheckBox>(
    "Géneros",
    listOf(
        GenreCheckBox("Acción", "accion"),
        GenreCheckBox("Artes Marciales", "artes-marciales"),
        GenreCheckBox("Aventura", "aventura"),
        GenreCheckBox("Comedia", "comedia"),
        GenreCheckBox("Demonios", "demonios"),
        GenreCheckBox("Drama", "drama"),
        GenreCheckBox("Ecchi", "ecchi"),
        GenreCheckBox("Fantasía", "fantasia"),
        GenreCheckBox("Harem", "harem"),
        GenreCheckBox("Histórico", "historico"),
        GenreCheckBox("Horror", "horror"),
        GenreCheckBox("Isekai", "isekai"),
        GenreCheckBox("Josei", "josei"),
        GenreCheckBox("Magia", "magia"),
        GenreCheckBox("Misterio", "misterio"),
        GenreCheckBox("Psicológico", "psicologico"),
        GenreCheckBox("Reencarnación", "reencarnacion"),
        GenreCheckBox("Romance", "romance"),
        GenreCheckBox("Sci-Fi", "sci-fi"),
        GenreCheckBox("Seinen", "seinen"),
        GenreCheckBox("Shoujo", "shoujo"),
        GenreCheckBox("Shounen", "shounen"),
        GenreCheckBox("Slice of Life", "slice-of-life"),
        GenreCheckBox("Sobrenatural", "sobrenatural"),
        GenreCheckBox("Tragedia", "tragedia"),
        GenreCheckBox("Vida Escolar", "vida-escolar"),
        GenreCheckBox("Webtoon", "webtoon"),
    ),
)
