package studio.acmenra.control.feature.events.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.feature.events.data.datasource.ExportPayloadRegistry
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.datasource.MOCK_PREVIEW_URL
import studio.acmenra.control.feature.events.data.datasource.MOCK_VIDEO_URL
import studio.acmenra.control.feature.events.data.datasource.MockEventsDataStore
import studio.acmenra.control.feature.events.data.datasource.MockEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventsExportFiltersDto
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventTypeOption
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MockEventsDataSourceTest {
    @Test
    fun testBootstrapReturnsMockDataWithMediaAndStatistics() =
        runTest {
            val store = MockEventsDataStore()
            val remoteDataSource = MockEventsRemoteDataSource(store)

            val bootstrap = remoteDataSource.getBootstrap("comp_1", EventsQuery())

            // Check events presence
            assertTrue(bootstrap.events.items.isNotEmpty())
            assertEquals(20, bootstrap.events.items.size)

            // Verify media URLs
            for (item in bootstrap.events.items) {
                assertEquals(MOCK_VIDEO_URL, item.videoUrl)
                assertEquals(MOCK_PREVIEW_URL, item.previewUrl)
            }

            // Verify device statistics
            val devices = bootstrap.summary.devices
            assertEquals(7, devices.totalCount)
            assertEquals(5, devices.onlineOkCount)
            assertEquals(1, devices.unknownCount)
            assertEquals(1, devices.errorCount)

            // Verify categories
            assertEquals(3, bootstrap.summary.categories.size)
            assertTrue(bootstrap.summary.todayEventsCount >= 20)
        }

    @Test
    fun testFilterOptionsAvailable() =
        runTest {
            val store = MockEventsDataStore()
            val remoteDataSource = MockEventsRemoteDataSource(store)

            val result = remoteDataSource.getFilterOptions("comp_1")
            assertTrue(result is FilterOptionsFetchResult.Success)

            val options = result.options
            assertEquals(6, options.eventTypes.size)
            assertEquals(3, options.locations.size)

            val totalDevices = options.locations.sumOf { it.devices.size }
            assertEquals(7, totalDevices)
        }

    @Test
    fun testFilteringEvents() =
        runTest {
            val store = MockEventsDataStore()
            val remoteDataSource = MockEventsRemoteDataSource(store)

            // Filter by event type
            val queryType = EventsQuery(filter = EventsFilter(eventTypeIds = setOf("type_helmet")))
            val bootstrapType = remoteDataSource.getBootstrap("comp_1", queryType)
            assertTrue(bootstrapType.events.items.all { it.eventTypeId == "type_helmet" })

            // Filter by validity (VALID)
            val queryValid = EventsQuery(filter = EventsFilter(validity = EventValidityFilter.VALID))
            val bootstrapValid = remoteDataSource.getBootstrap("comp_1", queryValid)
            assertTrue(bootstrapValid.events.items.none { it.isErroneous })

            // Filter by validity (ERRONEOUS)
            val queryErr = EventsQuery(filter = EventsFilter(validity = EventValidityFilter.ERRONEOUS))
            val bootstrapErr = remoteDataSource.getBootstrap("comp_1", queryErr)
            assertTrue(bootstrapErr.events.items.all { it.isErroneous })
        }

    @Test
    fun testAcknowledgeAndMarkErroneousMutations() =
        runTest {
            val store = MockEventsDataStore()
            val remoteDataSource = MockEventsRemoteDataSource(store)

            val acked = remoteDataSource.acknowledgeEvent("comp_1", "ev_mock_comp_1_1")
            assertEquals("ACKNOWLEDGED", acked.status)
            assertNotNull(acked.acknowledgedAt)
            assertEquals(2L, acked.eventVersion)

            val marked = remoteDataSource.markEventErroneous("comp_1", "ev_mock_comp_1_2", 1L, "idem_1")
            assertTrue(marked.isErroneous)
            assertNotNull(marked.erroneousMarkedAt)
            assertEquals(2L, marked.eventVersion)

            // Verify mutations persisted in store
            val bootstrap = store.getBootstrap("comp_1", EventsQuery())
            val ackedInStore = bootstrap.events.items.first { it.id == "ev_mock_comp_1_1" }
            assertEquals("ACKNOWLEDGED", ackedInStore.status)
            val markedInStore = bootstrap.events.items.first { it.id == "ev_mock_comp_1_2" }
            assertTrue(markedInStore.isErroneous)
        }

    @Test
    fun testRealtimeAdditionsBeginningAndMiddle() =
        runTest {
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val testScope = CoroutineScope(dispatcher + SupervisorJob())
            try {
                val store = MockEventsDataStore()
                val wsDataSource = MockEventsWebSocketDataSource(store)

                val initialBootstrap = store.getBootstrap("comp_1", EventsQuery())
                val initialItems = initialBootstrap.events.items
                val newestInitial = Instant.parse(initialItems.first().occurredAt)

                val receivedCommits = mutableListOf<IncomingWsFrame.TenantCommit>()
                testScope.launch {
                    wsDataSource.incomingFrames.collect { envelope ->
                        val frame = envelope.frame
                        if (frame is IncomingWsFrame.TenantCommit) {
                            receivedCommits.add(frame)
                        }
                    }
                }

                val gate = CompletableDeferred<Unit>()
                val connectionId =
                    wsDataSource.connect(
                        companyId = "comp_1",
                        accessToken = "token",
                        replayId = 1L,
                        afterSequence = 100L,
                        scope = testScope,
                        activationGate = gate,
                        onDisconnected = { _, _ -> },
                    )

                gate.complete(Unit)

                // Fast forward 3.0 seconds -> 1st event emitted (at the beginning)
                testScheduler.advanceTimeBy(3000)
                testScheduler.runCurrent()

                assertEquals(1, receivedCommits.size)
                val firstCommit = receivedCommits[0].frame.eventCreated
                assertNotNull(firstCommit)
                val firstOccurred = Instant.parse(firstCommit.occurredAt)
                assertTrue(firstOccurred >= newestInitial, "First event should be at the beginning")

                // At 6.0 seconds -> 2nd event emitted (in the middle)
                testScheduler.advanceTimeBy(3000)
                testScheduler.runCurrent()

                assertEquals(2, receivedCommits.size)
                val updatedBootstrap = store.getBootstrap("comp_1", EventsQuery())
                assertEquals(22, updatedBootstrap.events.items.size)

                // Verify newly added event has correct media URLs
                for (item in updatedBootstrap.events.items) {
                    assertEquals(MOCK_VIDEO_URL, item.videoUrl)
                    assertEquals(MOCK_PREVIEW_URL, item.previewUrl)
                }

                wsDataSource.disconnect(connectionId)
            } finally {
                testScope.cancel()
            }
        }

    @Test
    fun testExportWithMediaLinksAndUniqueExportIds() =
        runTest {
            val store = MockEventsDataStore()
            val remoteDataSource = MockEventsRemoteDataSource(store)

            // 1. First export: with media links
            val req1 =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(),
                    includeMediaLinks = true,
                    formatPreset = "EXCEL_SEMICOLON_BOM",
                )
            val status1 = remoteDataSource.createExport("comp_1", req1, "idem-1")
            assertEquals("mock-export-1", status1.exportId)
            assertEquals("COMPLETED", status1.status)

            val download1 = remoteDataSource.downloadExport("comp_1", status1.exportId)
            val payload1 = ExportPayloadRegistry.consume(download1.downloadHandle)
            assertNotNull(payload1)
            val csvText1 = payload1.bytes.decodeToString()
            assertTrue(csvText1.contains("PreviewUrl;VideoUrl"))
            assertTrue(csvText1.contains(MOCK_PREVIEW_URL))
            assertTrue(csvText1.contains(MOCK_VIDEO_URL))

            // 2. Second export: without media links, must have unique exportId
            val req2 =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(),
                    includeMediaLinks = false,
                    formatPreset = "STANDARD_COMMA_UTF8",
                )
            val status2 = remoteDataSource.createExport("comp_1", req2, "idem-2")
            assertNotEquals(status1.exportId, status2.exportId)
            assertEquals("mock-export-2", status2.exportId)

            val download2 = remoteDataSource.downloadExport("comp_1", status2.exportId)
            val payload2 = ExportPayloadRegistry.consume(download2.downloadHandle)
            assertNotNull(payload2)
            val csvText2 = payload2.bytes.decodeToString()
            assertFalse(csvText2.contains("PreviewUrl"))
            assertFalse(csvText2.contains("VideoUrl"))
        }

    @Test
    fun testFilterOptionsSanitizesInvalidParameters() {
        val options =
            EventsFilterOptions(
                eventTypes =
                    listOf(
                        EventTypeOption(id = "type_helmet", name = "Каска", iconKey = EventIconKey.HELMET),
                        EventTypeOption(id = "type_phone", name = "Телефон", iconKey = EventIconKey.PHONE),
                    ),
                locations =
                    listOf(
                        LocationFilterGroup(
                            id = "loc_1",
                            name = "Цех 1",
                            devices = listOf(DeviceFilterOption(id = "dev_1", name = "Камера 1")),
                        ),
                    ),
            )

        val dirtyFilter =
            EventsFilter(
                eventTypeIds = setOf("type_helmet", "non_existent_type"),
                deviceIds = setOf("dev_1", "ghost_device_999"),
            )

        val cleaned = options.sanitize(dirtyFilter)
        assertEquals(setOf("type_helmet"), cleaned.eventTypeIds)
        assertEquals(setOf("dev_1"), cleaned.deviceIds)
    }
}
