package dev.routeodo.core

data class Place(val label: String, val coordinate: Coordinate, val category: String? = null) {
    init { require(label.isNotBlank()) }
    val title: String get() = label.substringBefore(',')
    val subtitle: String get() = label.substringAfter(',', "").trim()
}

/** Editing a query invalidates both its selection and any older asynchronous search response. */
class EndpointSelection {
    var text: String = ""
        private set
    var place: Place? = null
        private set
    var revision = 0L
        private set

    fun edit(value: String) {
        if (text == value) return
        text = value
        place = null
        revision++
    }

    fun choose(value: Place) {
        text = value.label
        place = value
        revision++
    }
}
