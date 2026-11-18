package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String
        get() = options[state].second
}

class StatusFilter : SelectFilter(
    "Estado",
    arrayOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "En pausa" to "hiatus",
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
        "Cómic" to "comic",
    ),
)

class OrderByFilter : SelectFilter(
    "Ordenar por",
    arrayOf(
        "Por defecto" to "",
        "A-Z" to "title",
        "Z-A" to "titlereverse",
        "Últimos actualizados" to "update",
        "Últimos añadidos" to "latest",
        "Popular" to "popular",
    ),
)

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<GenreCheckBox>(
    "Géneros",
    listOf(
        GenreCheckBox("Acción", "accion"),
        GenreCheckBox("Artes Marciales", "artes-marciales"),
        GenreCheckBox("Aventura", "aventura"),
        GenreCheckBox("Comedia", "comedia"),
        GenreCheckBox("Deportes", "deportes"),
        GenreCheckBox("Drama", "drama"),
        GenreCheckBox("Ecchi", "ecchi"),
        GenreCheckBox("Escolar", "escolar"),
        GenreCheckBox("Fantasía", "fantasia"),
        GenreCheckBox("Harem", "harem"),
        GenreCheckBox("Histórico", "historico"),
        GenreCheckBox("Horror", "horror"),
        GenreCheckBox("Isekai", "isekai"),
        GenreCheckBox("Josei", "josei"),
        GenreCheckBox("Maduro", "maduro"),
        GenreCheckBox("Mecha", "mecha"),
        GenreCheckBox("Misterio", "misterio"),
        GenreCheckBox("Psicológico", "psicologico"),
        GenreCheckBox("Romance", "romance"),
        GenreCheckBox("Sci-Fi", "sci-fi"),
        GenreCheckBox("Seinen", "seinen"),
        GenreCheckBox("Shoujo", "shoujo"),
        GenreCheckBox("Shounen", "shounen"),
        GenreCheckBox("Slice of Life", "slice-of-life"),
        GenreCheckBox("Sobrenatural", "sobrenatural"),
        GenreCheckBox("Tragedia", "tragedia"),
        GenreCheckBox("Yaoi", "yaoi"),
        GenreCheckBox("Yuri", "yuri"),
    ),
)
