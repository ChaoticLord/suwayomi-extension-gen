package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

open class LegionscansSelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
    fun selectedValue(): String = options[state].second
}

class LegionscansStatusFilter : LegionscansSelectFilter(
    "Estado",
    arrayOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "Pausado" to "hiatus",
        "Cancelado" to "dropped",
    ),
)

class LegionscansTypeFilter : LegionscansSelectFilter(
    "Tipo",
    arrayOf(
        "Todos" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Comic" to "comic",
    ),
)

class LegionscansOrderByFilter : LegionscansSelectFilter(
    "Ordenar por",
    arrayOf(
        "Por defecto" to "",
        "A-Z" to "title",
        "Z-A" to "titlereverse",
        "Actualización" to "update",
        "Más nuevo" to "latest",
        "Popularidad" to "popular",
    ),
)

class LegionscansGenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class LegionscansGenreListFilter(genres: List<LegionscansGenreCheckBox>) : Filter.Group<LegionscansGenreCheckBox>("Géneros", genres)

fun getLegionscansFilterList(): FilterList = FilterList(
    LegionscansStatusFilter(),
    LegionscansTypeFilter(),
    LegionscansOrderByFilter(),
    LegionscansGenreListFilter(
        listOf(
            LegionscansGenreCheckBox("Acción", "accion"),
            LegionscansGenreCheckBox("Artes Marciales", "artes-marciales"),
            LegionscansGenreCheckBox("Aventura", "aventura"),
            LegionscansGenreCheckBox("Comedia", "comedia"),
            LegionscansGenreCheckBox("Drama", "drama"),
            LegionscansGenreCheckBox("Fantasía", "fantasia"),
            LegionscansGenreCheckBox("Harem", "harem"),
            LegionscansGenreCheckBox("Isekai", "isekai"),
            LegionscansGenreCheckBox("Magia", "magia"),
            LegionscansGenreCheckBox("Misterio", "misterio"),
            LegionscansGenreCheckBox("Psicológico", "psicologico"),
            LegionscansGenreCheckBox("Reencarnación", "reencarnacion"),
            LegionscansGenreCheckBox("Romance", "romance"),
            LegionscansGenreCheckBox("Sci-Fi", "sci-fi"),
            LegionscansGenreCheckBox("Seinen", "seinen"),
            LegionscansGenreCheckBox("Shonen", "shonen"),
            LegionscansGenreCheckBox("Sobrenatural", "sobrenatural"),
            LegionscansGenreCheckBox("Tragedia", "tragedia"),
            LegionscansGenreCheckBox("Vida Escolar", "vida-escolar"),
        ),
    ),
)
