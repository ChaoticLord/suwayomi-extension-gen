package eu.kanade.tachiyomi.extension.es.capibaratraductor

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter(
    private val vals: List<Pair<String, String>>,
) : Filter.Select<String>("Ordenar por", vals.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = vals.getOrNull(state)?.second ?: ""
}

class StatusFilter(
    private val vals: List<Pair<String, String>>,
) : Filter.Select<String>("Estado", vals.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = vals.getOrNull(state)?.second ?: ""
}

class GenreCheckBox(
    name: String,
    val slug: String,
) : Filter.CheckBox(name)

class GenreFilter(
    genres: List<GenreCheckBox>,
) : Filter.Group<GenreCheckBox>("Géneros", genres)

object FilterData {
    val sortOptions = listOf(
        "Populares" to "popular",
        "Recientes" to "recientes",
        "A - Z" to "alfabetico",
        "Mejor valorados" to "valoracion",
    )

    val statusOptions = listOf(
        "Todos" to "",
        "En emisión" to "en-emision",
        "Finalizado" to "finalizado",
        "En pausa" to "pausado",
        "Cancelado" to "cancelado",
    )

    fun createGenres(): List<GenreCheckBox> = listOf(
        GenreCheckBox("Acción", "accion"),
        GenreCheckBox("Aventura", "aventura"),
        GenreCheckBox("Comedia", "comedia"),
        GenreCheckBox("Drama", "drama"),
        GenreCheckBox("Fantasía", "fantasia"),
        GenreCheckBox("Isekai", "isekai"),
        GenreCheckBox("Misterio", "misterio"),
        GenreCheckBox("Psicológico", "psicologico"),
        GenreCheckBox("Recuentos de la vida", "recuentos-de-la-vida"),
        GenreCheckBox("Romance", "romance"),
        GenreCheckBox("Seinen", "seinen"),
        GenreCheckBox("Shonen", "shonen"),
        GenreCheckBox("Shojo", "shojo"),
        GenreCheckBox("Sobrenatural", "sobrenatural"),
        GenreCheckBox("Terror", "terror"),
        GenreCheckBox("Tragedia", "tragedia"),
    )
}
