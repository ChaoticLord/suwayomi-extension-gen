package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.source.model.Filter

open class LegionSelectFilter(
    displayName: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    fun selectedValue(): String = options[state].second
}

class LegionStatusFilter : LegionSelectFilter(
    "Estado",
    listOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "En pausa" to "hiatus",
    ),
)

class LegionTypeFilter : LegionSelectFilter(
    "Tipo",
    listOf(
        "Todos" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Comic" to "comic",
    ),
)

class LegionOrderFilter : LegionSelectFilter(
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

class LegionGenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class LegionGenreFilter : Filter.Group<LegionGenreCheckBox>(
    "Géneros",
    listOf(
        LegionGenreCheckBox("Acción", "accion"),
        LegionGenreCheckBox("Artes Marciales", "artes-marciales"),
        LegionGenreCheckBox("Aventura", "aventura"),
        LegionGenreCheckBox("Comedia", "comedia"),
        LegionGenreCheckBox("Demonios", "demonios"),
        LegionGenreCheckBox("Drama", "drama"),
        LegionGenreCheckBox("Ecchi", "ecchi"),
        LegionGenreCheckBox("Fantasía", "fantasia"),
        LegionGenreCheckBox("Harem", "harem"),
        LegionGenreCheckBox("Histórico", "historico"),
        LegionGenreCheckBox("Horror", "horror"),
        LegionGenreCheckBox("Isekai", "isekai"),
        LegionGenreCheckBox("Josei", "josei"),
        LegionGenreCheckBox("Magia", "magia"),
        LegionGenreCheckBox("Misterio", "misterio"),
        LegionGenreCheckBox("Psicológico", "psicologico"),
        LegionGenreCheckBox("Reencarnación", "reencarnacion"),
        LegionGenreCheckBox("Romance", "romance"),
        LegionGenreCheckBox("Sci-Fi", "sci-fi"),
        LegionGenreCheckBox("Seinen", "seinen"),
        LegionGenreCheckBox("Shoujo", "shoujo"),
        LegionGenreCheckBox("Shounen", "shounen"),
        LegionGenreCheckBox("Slice of Life", "slice-of-life"),
        LegionGenreCheckBox("Sobrenatural", "sobrenatural"),
        LegionGenreCheckBox("Tragedia", "tragedia"),
        LegionGenreCheckBox("Vida Escolar", "vida-escolar"),
        LegionGenreCheckBox("Webtoon", "webtoon"),
    ),
)
