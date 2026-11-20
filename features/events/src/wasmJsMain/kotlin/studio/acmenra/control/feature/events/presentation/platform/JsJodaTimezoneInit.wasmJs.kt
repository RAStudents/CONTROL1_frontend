package studio.acmenra.control.feature.events.domain.model

@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

actual fun ensureTimezoneDatabaseInitialized() {
    JsJodaTimeZoneModule
}
