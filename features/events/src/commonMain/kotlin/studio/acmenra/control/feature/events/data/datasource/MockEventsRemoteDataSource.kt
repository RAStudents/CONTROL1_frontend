package studio.acmenra.control.feature.events.data.datasource

import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsQuery

class MockEventsRemoteDataSource(
    private val dataStore: MockEventsDataStore = MockEventsDataStore(),
) : EventsRemoteDataSource {
    override suspend fun getBootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int,
    ): EventsBootstrapDto = dataStore.getBootstrap(companyId, query, limit)

    override suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int,
    ): EventsPageDto = dataStore.getEventsPage(companyId, cursor, limit)

    override suspend fun getFilterOptions(
        companyId: String,
        ifNoneMatchEtag: String?,
    ): FilterOptionsFetchResult = dataStore.getFilterOptions(companyId, ifNoneMatchEtag)

    override suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventDto = dataStore.acknowledgeEvent(companyId, eventId)

    override suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventDto = dataStore.markEventErroneous(companyId, eventId, expectedClassificationVersion, idempotencyKey)

    override suspend fun createExport(
        companyId: String,
        request: CreateExportRequestDto,
        idempotencyKey: String,
    ): EventsExportStatusDto {
        kotlinx.coroutines.delay(400)
        return dataStore.createExport(companyId, request, idempotencyKey)
    }

    override suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatusDto = dataStore.getExportStatus(companyId, exportId)

    override suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload {
        kotlinx.coroutines.delay(200)
        return dataStore.downloadExport(companyId, exportId)
    }
}
