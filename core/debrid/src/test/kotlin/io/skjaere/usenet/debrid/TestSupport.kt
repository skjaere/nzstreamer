package io.skjaere.usenet.debrid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A clock the test moves by hand. */
class MutableClock(var instant: Instant = Instant.parse("2026-11-05T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = instant
}

/**
 * An API answered by [handler] on the test's own dispatcher, so the limiter's and the test's waits are on its clock; each
 * request is recorded with the test clock's time it arrived at.
 */
class FakeApi(scope: TestScope, private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    val times = CopyOnWriteArrayList<Long>()
    val http = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    requests += request
                    times += scope.testScheduler.currentTime
                    handler(request)
                }
            },
        ),
    ) {
        install(HttpTimeout)
        expectSuccess = false
    }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), *headers.map { it.first to listOf(it.second) }.toTypedArray()))
