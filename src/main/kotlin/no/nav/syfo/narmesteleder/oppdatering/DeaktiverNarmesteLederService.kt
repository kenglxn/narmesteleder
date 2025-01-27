package no.nav.syfo.narmesteleder.oppdatering

import io.ktor.util.KtorExperimentalAPI
import no.nav.syfo.application.db.DatabaseInterface
import no.nav.syfo.db.finnAktiveNarmestelederkoblinger
import no.nav.syfo.log
import no.nav.syfo.narmesteleder.arbeidsforhold.service.ArbeidsgiverService
import no.nav.syfo.narmesteleder.oppdatering.kafka.NLRequestProducer
import no.nav.syfo.narmesteleder.oppdatering.kafka.NLResponseProducer
import no.nav.syfo.narmesteleder.oppdatering.kafka.model.KafkaMetadata
import no.nav.syfo.narmesteleder.oppdatering.kafka.model.NlKafkaMetadata
import no.nav.syfo.narmesteleder.oppdatering.kafka.model.NlRequest
import no.nav.syfo.narmesteleder.oppdatering.kafka.model.NlRequestKafkaMessage
import no.nav.syfo.narmesteleder.oppdatering.kafka.model.NlResponseKafkaMessage
import no.nav.syfo.narmesteleder.oppdatering.model.NlAvbrutt
import no.nav.syfo.pdl.model.toFormattedNameString
import no.nav.syfo.pdl.service.PdlPersonService
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@KtorExperimentalAPI
class DeaktiverNarmesteLederService(
    private val nlResponseProducer: NLResponseProducer,
    private val nlRequestProducer: NLRequestProducer,
    private val arbeidsgiverService: ArbeidsgiverService,
    private val pdlPersonService: PdlPersonService,
    private val database: DatabaseInterface
) {
    suspend fun deaktiverNarmesteLederForAnsatt(fnrLeder: String, orgnummer: String, fnrSykmeldt: String, token: String, callId: UUID) {
        val aktuelleNlKoblinger = database.finnAktiveNarmestelederkoblinger(fnrLeder).filter { it.orgnummer == orgnummer && it.fnr == fnrSykmeldt }
        if (aktuelleNlKoblinger.isNotEmpty()) {
            log.info("Deaktiverer NL-koblinger for $callId")
            deaktiverNarmesteLeder(orgnummer = aktuelleNlKoblinger.first().orgnummer, fnrSykmeldt = aktuelleNlKoblinger.first().fnr, token = token, callId = callId, forespurtAvAnsatt = false)
        } else {
            log.info("Ingen aktive koblinger å deaktivere $callId")
        }
    }

    suspend fun deaktiverNarmesteLeder(orgnummer: String, fnrSykmeldt: String, token: String, callId: UUID, forespurtAvAnsatt: Boolean = true) {
        val aktivtArbeidsforhold = arbeidsgiverService.getArbeidsgivere(fnr = fnrSykmeldt, token = token, forespurtAvAnsatt = forespurtAvAnsatt)
            .firstOrNull { it.orgnummer == orgnummer && it.aktivtArbeidsforhold }

        if (aktivtArbeidsforhold != null) {
            log.info("Ber om ny nærmeste leder siden arbeidsforhold er aktivt, $callId")
            val navn = pdlPersonService.getPersoner(fnrs = listOf(fnrSykmeldt), callId = callId.toString())[fnrSykmeldt]?.navn

            nlRequestProducer.send(
                NlRequestKafkaMessage(
                    nlRequest = NlRequest(
                        requestId = callId,
                        sykmeldingId = null,
                        fnr = fnrSykmeldt,
                        orgnr = orgnummer,
                        name = navn?.toFormattedNameString() ?: throw RuntimeException("Fant ikke navn på ansatt i PDL $callId")
                    ),
                    metadata = NlKafkaMetadata(
                        timestamp = OffsetDateTime.now(ZoneOffset.UTC),
                        source = getSource(forespurtAvAnsatt)
                    )
                )
            )
        }
        nlResponseProducer.send(
            NlResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(
                    timestamp = OffsetDateTime.now(ZoneOffset.UTC),
                    source = getSource(forespurtAvAnsatt)
                ),
                nlAvbrutt = NlAvbrutt(
                    orgnummer = orgnummer,
                    sykmeldtFnr = fnrSykmeldt,
                    aktivTom = OffsetDateTime.now(ZoneOffset.UTC)
                ),
                nlResponse = null
            )
        )
    }

    private fun getSource(forespurtAvAnsatt: Boolean) = when (forespurtAvAnsatt) {
        true -> "arbeidstaker"
        false -> "leder"
    }
}
