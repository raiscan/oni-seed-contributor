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
import java.util.UUID

/**
 * Resolves the installation ID for this run.
 *
 * - If `INSTALLATION_ID` is set to a valid UUID, that value is used —
 *   so an operator who wants the backend to see the same installation
 *   across container restarts can pin one explicitly.
 * - Otherwise a fresh UUID is generated for this process. The container
 *   doesn't need a mounted volume for this to work; the backend
 *   dedupes uploads by Steam ID + coordinate, so a per-restart
 *   installation ID is just a different signal in the analytics, not
 *   a correctness issue.
 */
object InstallationId {
    fun resolve(envValue: String?): String {
        val trimmed = envValue?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            try {
                UUID.fromString(trimmed)
            } catch (_: IllegalArgumentException) {
                error("INSTALLATION_ID is set but not a valid UUID: '$envValue'")
            }
            println("[INIT] Using installationId from INSTALLATION_ID env var")
            return trimmed
        }
        val fresh = UUID.randomUUID().toString()
        println(
            "[INIT] Generated installationId $fresh for this run " +
                "(set INSTALLATION_ID env var to make it stable across restarts)"
        )
        return fresh
    }
}
