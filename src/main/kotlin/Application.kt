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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {

    val token = SteamAuthToken.parse(
        System.getenv("STEAM_AUTH_TOKEN")
            ?: error("Missing STEAM_AUTH_TOKEN environment variable")
    )

    val mniApiKey = System.getenv("MNI_API_KEY_BROWSER")
        ?: error("Missing MNI_API_KEY_BROWSER environment variable (baked into the official image)")

    val installationId = InstallationId.resolve(System.getenv("INSTALLATION_ID"))

    val controlApiKey: String? = System.getenv("CONTROL_API_KEY")?.takeIf { it.isNotBlank() }
    val autoStart: Boolean = System.getenv("AUTO_START")?.lowercase() != "false"

    val backendClient = BackendClient(
        serverUrl = System.getenv("SERVER_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL,
        token = token,
        installationId = installationId,
        mniApiKey = mniApiKey,
    )

    // Touching `WorldgenRuntime.version` triggers the (~1-3s) V8/WASM
    // bootstrap and surfaces any startup failure here rather than on
    // the first /generate call. Cache the result so we pass the same
    // value to both the loop and the debug route.
    val gameVersion = WorldgenRuntime.version

    val service = ContributorService(
        worldgen = WorldgenRuntime::generate,
        uploader = backendClient::upload,
        gameVersion = gameVersion,
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            service.shutdown()
            backendClient.close()
            WorldgenRuntime.close()
        }
    )

    if (autoStart) {
        println("[INIT] Auto-starting contributor loop (set AUTO_START=false to disable)")
        service.start()
    } else {
        println("[INIT] AUTO_START=false; waiting for /start with CONTROL_API_KEY")
    }

    val port = System.getenv("WORLDGEN_PORT")?.toIntOrNull() ?: 8080

    embeddedServer(
        factory = Netty,
        port = port,
        host = "0.0.0.0",
    ) { configureRouting(service, controlApiKey, gameVersion) }.start(wait = true)
}
