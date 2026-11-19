package studio.acmenra.control.feature.events.domain.model

import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import kotlin.time.Instant

object EventsQueryRouteCodec {
    private const val HASH_PREFIX = "#events"
    private const val PARAM_COMPANY_ID = "companyId"
    private const val PARAM_SEARCH_ID = "search_id"
    private const val PARAM_FROM = "from"
    private const val PARAM_TO = "to"
    private const val PARAM_EVENT_TYPE_IDS = "event_type_ids"
    private const val PARAM_DEVICE_IDS = "device_ids"
    private const val PARAM_VALIDITY = "validity"
    private const val PARAM_SORT = "sort"

    fun encodeHash(
        companyId: String?,
        query: EventsQuery = EventsQuery(),
    ): String {
        val params = mutableListOf<Pair<String, String>>()

        // Always include companyId (default "null" to preserve compatibility with existing tests)
        val cleanCompany = companyId?.takeUnless { it.isBlank() || it == "null" }
        params.add(PARAM_COMPANY_ID to (cleanCompany ?: "null"))

        val filter = query.filter

        filter.searchId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params.add(PARAM_SEARCH_ID to it)
        }

        filter.dateFrom?.let {
            params.add(PARAM_FROM to it.toString())
        }

        filter.dateTo?.let {
            params.add(PARAM_TO to it.toString())
        }

        filter.eventTypeIds.sorted().forEach { typeId ->
            if (typeId.isNotBlank()) {
                params.add(PARAM_EVENT_TYPE_IDS to typeId)
            }
        }

        filter.deviceIds.sorted().forEach { deviceId ->
            if (deviceId.isNotBlank()) {
                params.add(PARAM_DEVICE_IDS to deviceId)
            }
        }

        if (filter.validity != EventValidityFilter.ALL) {
            params.add(PARAM_VALIDITY to filter.validity.name)
        }

        if (query.sort != EventsSort.OCCURRED_AT_DESC) {
            params.add(PARAM_SORT to query.sort.name)
        }

        val queryString =
            params.joinToString("&") { (k, v) ->
                "$k=${v.encodeURLParameter()}"
            }

        return "$HASH_PREFIX?$queryString"
    }

    fun decodeHash(rawHashOrUrl: String): Pair<String?, EventsQuery> {
        val raw = rawHashOrUrl.substringAfter("#", rawHashOrUrl)
        val queryPart =
            if (raw.contains("?")) {
                raw.substringAfter("?")
            } else {
                ""
            }

        if (queryPart.isBlank()) {
            return null to EventsQuery()
        }

        var companyId: String? = null
        var searchId: String? = null
        var dateFrom: Instant? = null
        var dateTo: Instant? = null
        val eventTypeIds = mutableSetOf<String>()
        val deviceIds = mutableSetOf<String>()
        var validity = EventValidityFilter.ALL
        var sort = EventsSort.OCCURRED_AT_DESC

        val pairs = queryPart.split("&")
        for (pair in pairs) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore("=").trim()
            val rawValue = pair.substringAfter("=", "").trim()
            val value = decodeUrlComponent(rawValue)

            when (key) {
                PARAM_COMPANY_ID -> {
                    companyId = value.takeUnless { it.isBlank() || it == "null" }
                }
                PARAM_SEARCH_ID -> {
                    searchId = value.takeUnless { it.isBlank() }
                }
                PARAM_FROM -> {
                    dateFrom = runCatching { Instant.parse(value) }.getOrNull()
                }
                PARAM_TO -> {
                    dateTo = runCatching { Instant.parse(value) }.getOrNull()
                }
                PARAM_EVENT_TYPE_IDS -> {
                    if (value.isNotBlank()) eventTypeIds.add(value)
                }
                PARAM_DEVICE_IDS -> {
                    if (value.isNotBlank()) deviceIds.add(value)
                }
                PARAM_VALIDITY -> {
                    validity =
                        when (value.uppercase()) {
                            "VALID" -> EventValidityFilter.VALID
                            "ERRONEOUS" -> EventValidityFilter.ERRONEOUS
                            else -> EventValidityFilter.ALL
                        }
                }
                PARAM_SORT -> {
                    if (value.equals("OCCURRED_AT_DESC", ignoreCase = true)) {
                        sort = EventsSort.OCCURRED_AT_DESC
                    }
                }
            }
        }

        val filter =
            EventsFilter(
                searchId = searchId,
                dateFrom = dateFrom,
                dateTo = dateTo,
                eventTypeIds = eventTypeIds,
                deviceIds = deviceIds,
                validity = validity,
            )

        return companyId to EventsQuery(filter = filter, sort = sort)
    }

    private fun decodeUrlComponent(s: String): String =
        runCatching {
            s.decodeURLPart()
        }.getOrDefault(s)
}
