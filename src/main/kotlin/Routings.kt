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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting(
    service: ContributorService,
    controlApiKey: String?,
    gameVersion: Int,
) {

    println("[INIT] Starting Server at version $VERSION")

    install(ContentNegotiation) { json() }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }

    routing {

        get("/") {
            call.respondText("ONI seed contributor $VERSION")
        }

        /*
         * For debug purposes, return a generated cluster.
         */
        get("/generate/{coordinate}") {

            val coordinate = call.parameters["coordinate"]!!

            val json  = WorldgenRuntime.generate(coordinate)

            val mapData = WorldgenMapData.fromJson(json)

            val cluster = WorldgenMapDataConverter.convert(
                mapData = mapData,
                gameVersion = gameVersion
            )

            call.respond(cluster)
        }

        get("/status") {
            call.respond(service.status())
        }

        get("/start") {
            if (!authorize(controlApiKey)) return@get
            val started = service.start()
            call.respondText(
                if (started) "started" else "already running"
            )
        }

        get("/stop") {
            if (!authorize(controlApiKey)) return@get
            val stopped = service.stop()
            call.respondText(
                if (stopped) "stopped" else "not running"
            )
        }
    }
}

/**
 * Returns true if the request may proceed. On rejection, writes the
 * response itself (403 if no key is configured, 401 on mismatch) and
 * returns false so the route handler can short-circuit.
 */
private suspend fun RoutingContext.authorize(controlApiKey: String?): Boolean {
    if (controlApiKey == null) {
        call.respondText(
            "/start and /stop are disabled — set CONTROL_API_KEY to enable",
            status = HttpStatusCode.Forbidden,
        )
        return false
    }
    val provided = call.request.headers["API_KEY"]
    if (provided != controlApiKey) {
        call.respondText("invalid API_KEY", status = HttpStatusCode.Unauthorized)
        return false
    }
    return true
}
