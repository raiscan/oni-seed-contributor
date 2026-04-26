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
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.values.reference.V8ValueArrayBuffer
import com.caoccao.javet.values.reference.V8ValueFunction
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Owns one V8/Node runtime, the instantiated WASM module, and a cached
 * handle to the JS-side `__generate` function. All `generate` calls are
 * serialized by an internal mutex.
 *
 * Cold-start cost: ~1-3s while the constructor runs. Memory: ~150 MB at
 * idle, 300-500 MB under load.
 */
object WorldgenRuntime : AutoCloseable {

    private val nodeRuntime: NodeRuntime = V8Host.getNodeInstance().createV8Runtime()
    private val generateFn: V8ValueFunction

    /**
     * The full version string reported by the WASM, e.g.
     * `"720697+0.1.0"` — `<ONI_BUILD>+<PACKAGE_VERSION>`. Logged at
     * init for diagnostics.
     */
    val versionString: String

    /**
     * The integer game build (the prefix of [versionString]) stamped
     * onto every uploaded cluster. Reading this lazily from the WASM
     * keeps the contributor in lockstep with whatever bundle ships,
     * with no Kotlin-side constant to bump.
     */
    val version: Int

    private val mutex = Mutex()

    init {
        try {
            // Log Javet native presence early — if the platform native
            // failed to load (musl/Alpine, missing arm64), this is where
            // the failure is most usefully visible. Note: Javet 5.0.6
            // doesn't expose the resolved native path on V8Host, so this
            // is best-effort — only the loaded-successfully signal, not
            // the path itself.
            println("[INIT] Javet native: reloadable=${V8Host.isLibraryReloadable()}")

            // 1. Bind the WASM bytes onto globalThis as a real V8
            //    ArrayBuffer (Javet's globalObject.set(String, ByteArray)
            //    does NOT auto-marshal to a JS BufferSource — passing a
            //    raw ByteArray leaves an opaque Java reference that
            //    `new WebAssembly.Module(...)` rejects).
            val wasmBytes = loadClasspathBytes("worldgen/oni_wasm_bg.wasm")

            nodeRuntime.createV8ValueArrayBuffer(wasmBytes.size).use { buf: V8ValueArrayBuffer ->
                buf.fromBytes(wasmBytes)
                nodeRuntime.globalObject.set("wasmBytes", buf)
            }

            // 2. Resolve `./<name>` ES-module specifiers from classpath.
            //    The resolver fires during the bootstrap module's
            //    compilation, when the bootstrap's `import` statements
            //    are encountered.
            nodeRuntime.setV8ModuleResolver { runtime, name, _ ->

                val basename = name.removePrefix("./")

                val src = loadClasspathString("worldgen/$basename")
                    ?: error("Module '$name' not found at classpath:worldgen/$basename")

                runtime.getExecutor(src)
                    .setResourceName(name)
                    .setModule(true)
                    .compileV8Module()
            }

            // 3. Compile and execute the bootstrap module. We compile
            //    explicitly (rather than relying on the executor's
            //    "compile + execute" shortcut) because the bootstrap uses
            //    top-level `await init(...)`. compileV8Module returns an
            //    IV8Module; executeVoid on the module evaluates it,
            //    leaving a pending Promise on the microtask queue.
            //    The JS strip inside __generate MUST run before
            //    JSON.stringify — otherwise typed-array fields serialize
            //    as {"0":v,"1":v,...} objects instead of being omitted.
            nodeRuntime.getExecutor(BOOTSTRAP_SRC)
                .setResourceName("./bootstrap.mjs")
                .setModule(true)
                .compileV8Module()
                .use { module -> module.executeVoid() }

            // 4. Drain the microtask queue and the Node event loop until
            //    `await init(...)` completes (or fails). Without this,
            //    __generate may not yet be installed on globalThis when
            //    we try to look it up below.
            nodeRuntime.await()

            // If WASM init rejected, the unhandled-rejection signal is
            // our only cue. Surface it as a hard failure.
            check(!nodeRuntime.isDead) {
                "NodeRuntime died during bootstrap (likely WASM init failure)"
            }

            // 5. Cache the `__generate` function so per-call invocation
            //    skips re-resolving the global. If init silently failed,
            //    this get() returns a non-V8ValueFunction and the cast
            //    throws — turning "function is undefined" into a clear
            //    classpath/bootstrap error.
            generateFn = nodeRuntime.globalObject.get("__generate")
                ?: error(
                    "Bootstrap did not install globalThis.__generate — " +
                        "WASM init likely failed silently. Check the WASM " +
                        "bundle and module resolver."
                )

            // 6. Resolve `worldgen.version()` once. The WASM returns a
            //    "<build>+<package>" string (e.g. "720697+0.1.0"); the
            //    upload payload needs just the integer build.
            versionString = nodeRuntime.globalObject.get<V8ValueFunction>("__version").use { fn ->
                fn.callString(null)
            }
            version = versionString.substringBefore('+').toIntOrNull()
                ?: error("Unexpected WASM version format: '$versionString' (expected '<build>+<package>')")
            println("[INIT] WASM worldgen version: $versionString (game build $version)")

        } catch (t: Throwable) {
            // Constructor failed — release native resources before
            // propagating, otherwise the NodeRuntime leaks.
            try {
                nodeRuntime.close()
            } catch (_: Throwable) { /* swallow */ }
            throw t
        }
    }

    suspend fun generate(coordinate: String): String =
        mutex.withLock { generateFn.callString(null, coordinate) }

    /**
     * Close holds the mutex so an in-flight generate finishes naturally
     * before the V8 handle is released — without this, a concurrent
     * generate would see a freed handle (JVM crash via the Javet native
     * call). Uses runBlocking because AutoCloseable.close is not suspend;
     * acceptable on a shutdown hook where blocking is fine.
     *
     * The wait is bounded by withTimeout(3.seconds) for the case where
     * close() runs as a shutdown hook triggered by exitProcess(70) on a
     * timeout (DD-011) and the runaway native call is the very thing
     * holding the mutex. Without the bound, the hook would block
     * forever and only the orchestrator's force-kill would free the
     * container; with the bound, we give up gracefully and let the JVM
     * exit. The timeout path leaks the V8 handle in that unusual case,
     * but the OS reaps the process anyway.
     */
    override fun close() = runBlocking {

        try {
            withTimeout(3.seconds) {
                mutex.withLock {
                    try { generateFn.close() } catch (_: Throwable) { /* swallow */ }
                    nodeRuntime.close()
                }
            }
        } catch (_: TimeoutCancellationException) {
            System.err.println(
                "[WARN] JavetWorldgenRuntime.close() gave up after 3s; " +
                    "runtime mutex held by a runaway native call. The JVM will exit; " +
                    "the OS reaps the leaked V8 handle."
            )
        }
    }

    private fun loadClasspathBytes(path: String): ByteArray {
        val cl = Thread.currentThread().contextClassLoader
            ?: WorldgenRuntime::class.java.classLoader
        return cl.getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: error("Classpath resource not found: $path")
    }

    private fun loadClasspathString(path: String): String? {
        val cl = Thread.currentThread().contextClassLoader
            ?: WorldgenRuntime::class.java.classLoader
        return cl.getResourceAsStream(path)
            ?.use { it.readBytes().decodeToString() }
    }
}

/**
 * Bootstrap module + the `__generate` function.
 *
 * **Strip-list parity:** the field-deletion list below MUST stay in
 * lockstep with the upstream onimaxxing JS (see
 * `oni-seed-browser/app/src/wasmJsMain/resources/worldgen.worker.mjs`)
 * and bump in lockstep when the npm package
 * (`@tigin-backwards/oxygen-not-included-worldgen`) version changes.
 *
 * **Kotlin raw-string caveat:** this is a `"""..."""` literal, so a
 * literal `$` would interpolate as a Kotlin expression. The current
 * source has none, but if you add JS template literals here (`` `${x}` ``),
 * escape the `$` as `${'$'}` to keep the JS source intact.
 */
private const val BOOTSTRAP_SRC = """
import init, { worldgen } from './index.js';

try {
    // wasm-bindgen's web-target init expects { module_or_path: ... }.
    // Passing a pre-compiled WebAssembly.Module skips the URL/fetch
    // path that doesn't work in Javet (no import.meta.url base).
    await init({ module_or_path: new WebAssembly.Module(globalThis.wasmBytes) });
} catch (e) {
    console.error('[BOOTSTRAP] WASM init failed:', e);
    throw e;
}

globalThis.__version = function () {
    return worldgen.version();
};

globalThis.__generate = function (coord) {
    const r = worldgen.generate(coord);

    // Drop typed-array fields BEFORE JSON.stringify; otherwise they
    // serialize as objects ({"0": v, "1": v, ...}) instead of being
    // omitted. This is the entire reason for the JS-side strip.
    delete r.element_table;
    for (const w of r.worlds) {
        delete w.element_idx;
        delete w.mass;
        delete w.temperature;
        delete w.disease_idx;
        delete w.disease_count;
        delete w.pickupables;
        for (const cell of w.biome_cells) delete cell.type;
        for (const g of w.geysers) delete g.cell;
        for (const e of w.other_entities) delete e.cell;
        for (const b of w.buildings) {
            delete b.cell;
            delete b.connections;
            delete b.rotationOrientation;
        }
    }
    for (const p of r.starmap_pois) {
        delete p.capacity_roll;
        delete p.recharge_roll;
        delete p.total_capacity;
        delete p.recharge_time;
    }

    return JSON.stringify(r);
};
"""
