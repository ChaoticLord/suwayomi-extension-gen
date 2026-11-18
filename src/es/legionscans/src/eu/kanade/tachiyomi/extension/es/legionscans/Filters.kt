package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

open class SelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
    fun selectedValue(): String = options[state].second
}

class StatusFilter : SelectFilter(
    "Estado",
    arrayOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "Pausado" to "hiatus",
        "Cancelado" to "dropped",
    ),
)

class TypeFilter : SelectFilter(
    "Tipo",
    arrayOf(
        "Todos" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Comic" to "comic",
    ),
)

class OrderByFilter : SelectFilter(
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

class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class GenreListFilter(genres: List<GenreCheckBox>) : Filter.Group<GenreCheckBox>("Géneros", genres)

fun getLegionscansFilterList(): FilterList = FilterList(
    StatusFilter(),
    TypeFilter(),
    OrderByFilter(),
    GenreListFilter(
        listOf(
            GenreCheckBox("Acción", "accion"),
            GenreCheckBox("Artes Marciales", "artes-marciales"),
            GenreCheckBox("Aventura", "aventura"),
            GenreCheckBox("Comedia", "comedia"),
            GenreCheckBox("Drama", "drama"),
            GenreCheckBox("Fantasía", "fantasia"),
            GenreCheckBox("Harem", "harem"),
            GenreCheckBox("Isekai", "isekai"),
            GenreCheckBox("Magia", "magia"),
            GenreCheckBox("Misterio", "misterio"),
            GenreCheckBox("Psicológico", "psicologico"),
            GenreCheckBox("Reencarnación", "reencarnacion"),
            GenreCheckBox("Romance", "romance"),
            GenreCheckBox("Sci-Fi", "sci-fi"),
            GenreCheckBox("Seinen", "seinen"),
            GenreCheckBox("Shonen", "shonen"),
            GenreCheckBox("Sobrenatural", "sobrenatural"),
            GenreCheckBox("Tragedia", "tragedia"),
            GenreCheckBox("Vida Escolar", "vida-escolar"),
        ),
    ),
)
