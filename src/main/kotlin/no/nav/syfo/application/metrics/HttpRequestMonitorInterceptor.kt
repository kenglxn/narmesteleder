package no.nav.syfo.application.metrics

import io.ktor.application.ApplicationCall
import io.ktor.request.path
import io.ktor.util.pipeline.PipelineContext

fun monitorHttpRequests(): suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit {
    return {
        val path = context.request.path()
        val timer = HTTP_HISTOGRAM.labels(path).startTimer()
        proceed()
        timer.observeDuration()
    }
}
