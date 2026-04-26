/*
 * ONI Contribitor service
 * Copyright (C) 2026 Stefan Oltmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
import de.stefan_oltmann.oni.model.Cluster
import de.stefan_oltmann.oni.model.ClusterType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val COORDINATE = "PRE-C-719330309-0-0-ZB937"
// The version that produced the checked-in sample.json. Used only for
// the round-trip parity check in this test; production reads the live
// version from WorldgenRuntime.version.
private const val SAMPLE_GAME_VERSION: Int = 720697

private val rawJson = ContributorServiceTest::class.java.getResourceAsStream("sample.json")!!
    .readAllBytes()
    .decodeToString()

/**
 * Drives the contributor loop with deterministic fakes so the
 * 200/409/429/error transitions are observable without real V8 or HTTP.
 * The actual V8/WASM round-trip is covered by WorldgenRuntimeTest, and
 * the HTTP wire format is the same as the browser (which is the
 * authority).
 */
class ContributorServiceTest {

    @Test
    fun `200 path increments uploaded and clears errors`() = runBlocking {
        var uploads = 0
        val service = serviceWith { uploads++; ok() }
        service.start()
        waitFor { service.status().uploaded >= 3 }
        service.shutdown()

        val state = service.status()
        assertTrue(state.uploaded >= 3, "uploaded=$state")
        assertEquals(0L, state.failed)
        assertEquals(0L, state.conflicted)
        assertEquals(0L, state.throttled)
    }

    @Test
    fun `409 increments conflicted and keeps running`() = runBlocking {
        val service = serviceWith {
            UploadResult.Responded(HttpStatusCode.Conflict, body = null)
        }
        service.start()
        waitFor { service.status().conflicted >= 3 }
        service.shutdown()

        val state = service.status()
        assertTrue(state.conflicted >= 3)
        assertEquals(0L, state.uploaded)
        assertEquals(0L, state.failed)
    }

    @Test
    fun `429 grows delay and stops once it exceeds max`() = runBlocking {
        val service = serviceWith {
            UploadResult.Responded(HttpStatusCode.TooManyRequests, body = null)
        }
        service.start()
        waitFor(timeoutMillis = 30_000) { !service.status().running }
        service.shutdown()

        val state = service.status()
        assertFalse(state.running)
        assertTrue(state.throttled > 0)
        // serviceWith() configures maxDelayMs = 5 to keep the test fast.
        assertTrue(
            state.currentDelayMs > 5,
            "expected delay past test max (5ms), got ${state.currentDelayMs}",
        )
        assertTrue(state.lastError!!.contains("Throttled"))
    }

    @Test
    fun `network failure increments failed and keeps running`() = runBlocking {
        val attempts = AtomicInteger()
        val service = serviceWith {
            if (attempts.incrementAndGet() == 1)
                UploadResult.NetworkFailure(java.net.ConnectException("bad gateway"))
            else
                ok()
        }
        service.start()
        waitFor { service.status().uploaded >= 1 }
        service.shutdown()

        val state = service.status()
        assertTrue(state.failed >= 1)
        assertTrue(state.uploaded >= 1)
    }

    private fun serviceWith(uploader: suspend (Cluster) -> UploadResult) = ContributorService(
        nextCoordinate = { COORDINATE },
        worldgen = { rawJson.trimEnd() },
        uploader = uploader,
        gameVersion = SAMPLE_GAME_VERSION,
        // Tight throttle params keep tests fast — semantics still match production.
        initialDelayMs = 1,
        delayStepMs = 1,
        maxDelayMs = 5,
        throttlePauseMillis = 1,
        longBackoffMillis = 1,
    )

    private fun ok() = UploadResult.Responded(HttpStatusCode.OK, body = null)

    private suspend fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(50)
        }
        error("Condition not met within ${timeoutMillis}ms")
    }

    init {
        // Sanity: the sample produces a parseable Cluster with the right coord.
        val cluster = WorldgenMapDataConverter.convert(
            mapData = WorldgenMapData.fromJson(rawJson.trimEnd()),
            gameVersion = SAMPLE_GAME_VERSION,
        )
        check(cluster.coordinate == COORDINATE) { "sample coord mismatch: ${cluster.coordinate}" }
        check(cluster.cluster == ClusterType.DLC_RELICA_MINOR) { "unexpected cluster type ${cluster.cluster}" }
    }
}
