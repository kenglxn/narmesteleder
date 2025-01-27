package no.nav.syfo.application

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.authenticate
import io.ktor.features.CORS
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import no.nav.syfo.Environment
import no.nav.syfo.application.api.registerNaisApi
import no.nav.syfo.application.api.setupSwaggerDocApi
import no.nav.syfo.application.db.Database
import no.nav.syfo.application.metrics.monitorHttpRequests
import no.nav.syfo.forskuttering.registrerForskutteringApi
import no.nav.syfo.log
import no.nav.syfo.narmesteleder.NarmesteLederService
import no.nav.syfo.narmesteleder.SyforestNarmesteLederService
import no.nav.syfo.narmesteleder.arbeidsforhold.service.ArbeidsgiverService
import no.nav.syfo.narmesteleder.oppdatering.DeaktiverNarmesteLederService
import no.nav.syfo.narmesteleder.oppdatering.kafka.NLRequestProducer
import no.nav.syfo.narmesteleder.oppdatering.kafka.NLResponseProducer
import no.nav.syfo.narmesteleder.organisasjon.client.OrganisasjonsinfoClient
import no.nav.syfo.narmesteleder.registrerNarmesteLederApi
import no.nav.syfo.narmesteleder.user.registrerNarmesteLederUserApi
import no.nav.syfo.narmesteleder.user.registrerNarmesteLederUserArbeidsgiverApi
import no.nav.syfo.narmesteleder.user.registrerNarmesteLederUserArbeidsgiverApiV2
import no.nav.syfo.pdl.service.PdlPersonService
import java.util.UUID

@KtorExperimentalAPI
fun createApplicationEngine(
    env: Environment,
    applicationState: ApplicationState,
    jwkProvider: JwkProvider,
    jwkProviderLoginservice: JwkProvider,
    jwkProviderTokenX: JwkProvider,
    loginserviceIssuer: String,
    tokenXIssuer: String,
    database: Database,
    pdlPersonService: PdlPersonService,
    nlResponseProducer: NLResponseProducer,
    nlRequestProducer: NLRequestProducer,
    arbeidsgiverService: ArbeidsgiverService,
    organisasjonsinfoClient: OrganisasjonsinfoClient
): ApplicationEngine =
    embeddedServer(Netty, env.applicationPort) {
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        setupAuth(
            jwkProvider = jwkProvider,
            jwkProviderLoginservice = jwkProviderLoginservice,
            jwkProviderTokenX = jwkProviderTokenX,
            env = env,
            loginserviceIssuer = loginserviceIssuer,
            tokenXIssuer = tokenXIssuer
        )
        install(CallLogging) {
            mdc("Nav-Callid") { call ->
                call.request.queryParameters["Nav-Callid"] ?: UUID.randomUUID().toString()
            }
            mdc("Nav-Consumer-Id") { call ->
                call.request.queryParameters["Nav-Consumer-Id"] ?: "narmesteleder"
            }
        }
        install(StatusPages) {
            exception<Throwable> { cause ->
                log.error("Caught exception", cause)
                call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")
            }
        }
        install(CORS) {
            method(HttpMethod.Get)
            method(HttpMethod.Post)
            method(HttpMethod.Options)
            env.allowedOrigin.forEach {
                hosts.add("https://$it")
            }
            header("nav_csrf_protection")
            header("Sykmeldt-Fnr")
            allowCredentials = true
            allowNonSimpleContentTypes = true
        }

        val narmesteLederService = NarmesteLederService(database, pdlPersonService)
        val deaktiverNarmesteLederService = DeaktiverNarmesteLederService(nlResponseProducer, nlRequestProducer, arbeidsgiverService, pdlPersonService, database)
        val syforestNarmesteLederService = SyforestNarmesteLederService(narmesteLederService, organisasjonsinfoClient)
        routing {
            registerNaisApi(applicationState)
            if (env.cluster == "dev-gcp") {
                setupSwaggerDocApi()
            }
            authenticate("servicebruker") {
                registrerForskutteringApi(database)
                registrerNarmesteLederApi(database, narmesteLederService)
            }
            authenticate("loginservice") {
                registrerNarmesteLederUserApi(deaktiverNarmesteLederService, narmesteLederService, syforestNarmesteLederService)
                registrerNarmesteLederUserArbeidsgiverApi(deaktiverNarmesteLederService, narmesteLederService)
            }
            authenticate("tokenx") {
                registrerNarmesteLederUserArbeidsgiverApiV2(narmesteLederService)
            }
        }
        intercept(ApplicationCallPipeline.Monitoring, monitorHttpRequests())
    }
