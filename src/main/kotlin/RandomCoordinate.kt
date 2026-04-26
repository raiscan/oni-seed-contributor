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
import de.stefan_oltmann.oni.model.ClusterType

/**
 * Mirror of oni-seed-browser's CoordinateUtil.generateRandomCoordinate.
 * Picks a random ClusterType and a random positive Int seed.
 *
 * Format: `{prefix}-{seed}-0-0-0`. Some prefixes carry DLC requirements
 * the live WASM bundle may or may not implement; those will fail at
 * generate-time and the contributor loop will treat them as transient
 * failures, matching how the browser behaves.
 */
object RandomCoordinate {
    fun next(): String {
        val type = ClusterType.entries.random()
        val seed = (1..Int.MAX_VALUE).random()
        return "${type.prefix}-$seed-0-0-0"
    }
}
