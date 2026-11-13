package io.skjaere.usenet.newznab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val KEY = "TESTKEY123"

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun indexer(id: String = "nz", host: String = "indexer.example.org", key: String = KEY) =
    IndexerConfig(id = id, name = id, baseUrl = "https://$host", apiKey = key)

fun MockRequestHandleScope.xml(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/xml"))

/** A client whose requests are answered by [handler]; every request is recorded. */
class FakeIndexers(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val http = HttpClient(MockEngine { request -> requests += request; handler(request) }) {
        install(HttpTimeout)
        expectSuccess = false
    }
    val client = NewznabClient(http)
}
