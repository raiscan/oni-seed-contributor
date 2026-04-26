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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class InstallationIdTest {

    @Test
    fun `uses INSTALLATION_ID when it is a valid UUID`() {
        val pinned = "11111111-2222-3333-4444-555555555555"
        assertEquals(pinned, InstallationId.resolve(pinned))
    }

    @Test
    fun `trims whitespace`() {
        val pinned = "11111111-2222-3333-4444-555555555555"
        assertEquals(pinned, InstallationId.resolve("  $pinned\n"))
    }

    @Test
    fun `generates fresh UUID when env is null or blank`() {
        val a = InstallationId.resolve(null)
        val b = InstallationId.resolve("")
        val c = InstallationId.resolve("   ")
        // All three are valid UUIDs and all three are different.
        UUID.fromString(a); UUID.fromString(b); UUID.fromString(c)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
    }

    @Test
    fun `rejects malformed UUID with actionable error`() {
        val e = assertFailsWith<IllegalStateException> {
            InstallationId.resolve("not-a-uuid")
        }
        assert(e.message!!.contains("INSTALLATION_ID"))
    }
}
