package dev.routeodo.core

data class Place(val label: String, val coordinate: Coordinate) {
    init { require(label.isNotBlank()) }
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
