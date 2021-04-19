package no.nav.syfo.narmesteleder

import io.ktor.util.KtorExperimentalAPI
import no.nav.syfo.application.db.DatabaseInterface
import no.nav.syfo.db.finnAlleNarmesteledereForSykmeldt
import no.nav.syfo.pdl.model.toFormattedNameString
import no.nav.syfo.pdl.service.PdlPersonService

@KtorExperimentalAPI
class UtvidetNarmesteLederService(
    private val database: DatabaseInterface,
    private val pdlPersonService: PdlPersonService
) {
    suspend fun hentNarmesteledereMedNavn(sykmeldtFnr: String, callId: String): List<NarmesteLederRelasjon> {
        val narmesteLederRelasjoner = database.finnAlleNarmesteledereForSykmeldt(sykmeldtFnr)
        val nlFnrs = narmesteLederRelasjoner.map { it.narmesteLederFnr }
        if (nlFnrs.isEmpty()) {
            return emptyList()
        }
        val nlNavn = pdlPersonService.getPersonnavn(fnrs = nlFnrs, callId = callId)

        return narmesteLederRelasjoner.map { it.copy(navn = nlNavn[it.narmesteLederFnr]?.toFormattedNameString()) }
    }
}