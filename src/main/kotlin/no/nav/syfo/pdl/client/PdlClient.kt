package no.nav.syfo.pdl.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import no.nav.syfo.pdl.client.model.GetPersonRequest
import no.nav.syfo.pdl.client.model.GetPersonResponse
import no.nav.syfo.pdl.client.model.GetPersonVariables

class PdlClient(
    private val httpClient: HttpClient,
    private val basePath: String,
    private val apiKey: String,
    private val graphQlQuery: String
) {
    private val temaHeader = "TEMA"
    private val tema = "SYM"

    suspend fun getPersoner(fnrs: List<String>, stsToken: String): GetPersonResponse {
        val getPersonRequest = GetPersonRequest(query = graphQlQuery, variables = GetPersonVariables(identer = fnrs))
        return httpClient.post(basePath) {
            body = getPersonRequest
            header("x-nav-apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $stsToken")
            header(temaHeader, tema)
            header(HttpHeaders.ContentType, "application/json")
        }
    }
}
