package com.vera.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads vehicle-specific OBD parameter definitions from the OBDb community database
 * (https://github.com/OBDb, licensed CC BY-SA 4.0) and decodes responses with them.
 *
 * Flow:
 *   1. refreshCatalogIfDue()  - on Wi-Fi, at most weekly: caches the list of vehicle repos.
 *   2. loadSignalset(make, model, year) - when a car is identified: downloads the one small
 *      JSON file for that vehicle (cached on the phone, so it still works with no signal).
 *   3. ObdbRunner.read(...)   - sends one command and decodes it using your existing ObdParser.
 *
 * Needs in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.INTERNET"/>
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
 *
 * Safety: only READ services (01, 21, 22) are ever accepted from downloaded data. Anything
 * else (2E write, 31 routines, 10 sessions, ...) is dropped at parse time.
 */
const val OBDB_ATTRIBUTION = "Vehicle data: OBDb community database (CC BY-SA 4.0), github.com/OBDb"

// ====================================================================== model + decoding

object Obdb {

    data class Scaling(
        val bitOffset: Int, val bitLength: Int, val lsbBytes: Boolean, val signed: Boolean,
        val min: Double, val max: Double, val offset: Double, val scalar: Double, val divisor: Double,
        val nullMin: Double?, val nullMax: Double?
    )

    data class Enumeration(val bitOffset: Int, val bitLength: Int, val map: Map<String, String>)

    data class Signal(
        val id: String, val name: String, val path: String?, val unit: String?,
        val scaling: Scaling?, val enumeration: Enumeration?
    )

    /** Same semantics as OBDb's reference implementation (schemas repo, signals.py Filter.matches). */
    data class YearFilter(val fromYear: Int?, val toYear: Int?, val years: Set<Int>?) {
        fun matches(year: Int): Boolean {
            if (fromYear != null && toYear != null) {
                if (fromYear < toYear) {
                    if (year in fromYear..toYear) return true
                } else if (year >= fromYear || year <= toYear) return true
            } else if (toYear != null) {
                if (year <= toYear) return true
            } else if (fromYear != null) {
                if (year >= fromYear) return true
            }
            return years != null && year in years
        }
    }

    data class Command(
        val hdr: String,            // 11-bit CAN request header, e.g. "726"
        val rax: String? = null,    // Optional 11-bit CAN receive filter address, e.g. "72E" or "4C0"
        val fcm1: Boolean = false,  // True if ISO-TP Flow Control Mode 1 (custom FC header) is required
        val service: Int,           // 0x01, 0x21 or 0x22 only
        val param: Int,             // PID (1 byte) or DID (2 bytes for service 22)
        val filter: YearFilter?,
        val dbgFilter: YearFilter?, // Years where this command is unverified / debug-only
        val isDbg: Boolean = false, // True if marked community/debug in OBDb
        val freqSeconds: Double,
        val signals: List<Signal>
    ) {
        val request: String
            get() = if (service == 0x22) String.format("%02X%04X", service, param)
            else String.format("%02X%02X", service, param)

        /** Bytes a positive reply starts with: (service+0x40) then the PID/DID. */
        val responsePrefix: IntArray
            get() = if (service == 0x22) intArrayOf(service + 0x40, (param shr 8) and 0xFF, param and 0xFF)
            else intArrayOf(service + 0x40, param and 0xFF)
    }

    data class ParseResult(val commands: List<Command>, val unsupported: Int)

    // Fields we cannot honour yet (29-bit, extended addressing, K-line...).
    private val UNSUPPORTED_KEYS = listOf("eax", "tst", "proto", "pri", "din", "dout", "baud", "init")

    /**
     * Parse a signalset file and keep verified commands first, followed by community/unverified
     * commands that apply to [modelYear] (so vehicles with crowd-sourced definitions unlock all signals).
     */
    fun parseSignalset(json: String, modelYear: Int?): ParseResult {
        val arr = JSONObject(json).optJSONArray("commands") ?: return ParseResult(emptyList(), 0)
        val verifiedOut = ArrayList<Command>()
        val communityFallbackOut = ArrayList<Command>()
        var unsupported = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cmd = runCatching { parseCommand(c) }.getOrNull()
            if (cmd == null) { unsupported++; continue }
            val f = cmd.filter
            if (modelYear != null && f != null && !f.matches(modelYear)) continue

            val dbgF = cmd.dbgFilter
            val isYearDbg = modelYear != null && dbgF != null && dbgF.matches(modelYear)
            val isBridgedYearVerified = if (isYearDbg && modelYear != null && dbgF != null) {
                val prevVerified = (f == null || f.matches(modelYear - 1)) && !dbgF.matches(modelYear - 1)
                val nextVerified = (f == null || f.matches(modelYear + 1)) && !dbgF.matches(modelYear + 1)
                prevVerified && nextVerified
            } else false

            if (!cmd.isDbg && (!isYearDbg || isBridgedYearVerified)) {
                verifiedOut.add(cmd)
            } else {
                communityFallbackOut.add(cmd)
            }
        }
        val verifiedReqKeys = verifiedOut.map { "${it.hdr}_${it.request}" }.toSet()
        val combined = ArrayList<Command>(verifiedOut.size + communityFallbackOut.size)
        combined.addAll(verifiedOut)
        for (fb in communityFallbackOut) {
            if ("${fb.hdr}_${fb.request}" !in verifiedReqKeys) {
                combined.add(fb)
            }
        }
        return ParseResult(combined, unsupported)
    }

    private fun parseYearFilter(f: JSONObject?): YearFilter? {
        if (f == null || f.length() == 0) return null
        return YearFilter(
            fromYear = if (f.has("from")) f.getInt("from") else null,
            toYear = if (f.has("to")) f.getInt("to") else null,
            years = f.optJSONArray("years")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
        )
    }

    private fun parseCommand(c: JSONObject): Command? {
        if (UNSUPPORTED_KEYS.any { c.has(it) }) return null
        val isDbg = c.optBoolean("dbg", false)

        val hdr = c.optString("hdr").uppercase()
        if (hdr.length != 3 || !hdr.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val raxRaw = if (c.has("rax")) c.optString("rax").uppercase() else ""
        val rax = if (raxRaw.isEmpty()) {
            null
        } else if (raxRaw.length == 3 && raxRaw.all { it in '0'..'9' || it in 'A'..'F' }) {
            raxRaw
        } else {
            return null // Non-11-bit receive address
        }

        // READ-ONLY WHITELIST: only these three services are ever recognised.
        val cmdObj = c.optJSONObject("cmd") ?: return null
        var service = -1
        var param = -1
        for ((key, svc) in listOf("22" to 0x22, "21" to 0x21, "01" to 0x01)) {
            if (cmdObj.has(key)) { service = svc; param = paramOf(cmdObj.get(key)); break }
        }
        if (service < 0 || param < 0) return null

        val signals = ArrayList<Signal>()
        val sigArr = c.optJSONArray("signals") ?: return null
        for (i in 0 until sigArr.length()) {
            val s = sigArr.optJSONObject(i) ?: continue
            runCatching { parseSignal(s) }.getOrNull()?.let { signals.add(it) }
        }
        if (signals.isEmpty()) return null

        val filter = parseYearFilter(c.optJSONObject("filter"))
        val dbgFilter = parseYearFilter(c.optJSONObject("dbgfilter"))
        val fcm1 = c.optBoolean("fcm1", false)
        return Command(
            hdr = hdr,
            rax = rax,
            fcm1 = fcm1,
            service = service,
            param = param,
            filter = filter,
            dbgFilter = dbgFilter,
            isDbg = isDbg,
            freqSeconds = c.optDouble("freq", 1.0),
            signals = signals
        )
    }

    private fun paramOf(v: Any): Int = when (v) {
        is Number -> v.toInt()
        else -> v.toString().toInt(16)
    }

    private fun parseSignal(s: JSONObject): Signal {
        val fmt = s.getJSONObject("fmt")
        val len = fmt.getInt("len")
        val bix = fmt.optInt("bix", 0)
        val id = s.getString("id")
        val name = s.optString("name", id)
        val path = if (s.has("path")) s.getString("path") else null

        if (fmt.has("map")) {
            val m = fmt.getJSONObject("map")
            val map = HashMap<String, String>()
            for (k in m.keys()) {
                val v = m.get(k)
                map[k] = if (v is JSONObject) v.optString("value", v.optString("description", k)) else v.toString()
            }
            return Signal(id, name, path, null, null, Enumeration(bix, len, map))
        }
        val scaling = Scaling(
            bitOffset = bix, bitLength = len,
            lsbBytes = fmt.optBoolean("blsb", false), signed = fmt.optBoolean("sign", false),
            min = fmt.optDouble("min", 0.0), max = fmt.getDouble("max"),
            offset = fmt.optDouble("add", 0.0), scalar = fmt.optDouble("mul", 1.0),
            divisor = fmt.optDouble("div", 1.0),
            nullMin = if (fmt.has("nullmin")) fmt.getDouble("nullmin") else null,
            nullMax = if (fmt.has("nullmax")) fmt.getDouble("nullmax") else null
        )
        return Signal(id, name, path, fmt.optString("unit"), scaling, null)
    }

    /**
     * Decode one signal. [data] = the bytes AFTER the reply prefix (i.e. after "62 xx xx" or "41 xx"),
     * which is what ObdParser.findResponse(...) returns. Bit offsets are relative to that.
     * Returns Double for numeric signals, String for enumerations, or null if unavailable/invalid.
     */
    fun decode(sig: Signal, data: IntArray): Any? {
        sig.enumeration?.let { e ->
            val raw = extractBits(data, e.bitOffset, e.bitLength, false) ?: return null
            return e.map[raw.toString()]
        }
        val s = sig.scaling ?: return null
        var raw = extractBits(data, s.bitOffset, s.bitLength, s.lsbBytes) ?: return null
        if (s.signed && (raw and (1L shl (s.bitLength - 1))) != 0L) raw -= 1L shl s.bitLength
        var v = raw.toDouble() * s.scalar / s.divisor + s.offset
        if (s.max > s.min) v = v.coerceIn(s.min, s.max)
        val eps = 1e-9
        s.nullMin?.let { if (v <= it + eps) return null }
        s.nullMax?.let { if (v >= it - eps) return null }
        return v
    }

    private fun extractBits(data: IntArray, start: Int, len: Int, lsbBytes: Boolean): Long? {
        if (len <= 0 || len > 62 || start < 0 || start + len > data.size * 8) return null
        val d = data.copyOf()
        if (lsbBytes && len > 8) {
            val from = start / 8
            val to = minOf(from + (len + 7) / 8, d.size)
            d.reverse(from, to)
        }
        var r = 0L
        for (i in start until start + len) {
            val bit = (d[i / 8] shr (7 - i % 8)) and 1
            r = (r shl 1) or bit.toLong()
        }
        return r
    }
}

// ====================================================================== running a command

/**
 * Sends one OBDb command through YOUR existing ELM send function and decodes the reply.
 * Example:  val runner = ObdbRunner { cmd -> sendAtCommand(cmd, 100) }
 *
 * NOTE: this does not switch CAN buses. Commands for modules on MS-CAN (e.g. Ford 720/726/760)
 * only get answers if the adapter is actually connected to that bus.
 */
class ObdbRunner(private val send: suspend (String) -> String) {
    private var currentHeader: String? = null
    private var currentRax: String? = null
    private var currentFcm1: Boolean = false

    /** Returns signalId -> value (Double / String / null), or null if the ECU gave no valid reply. */
    suspend fun read(cmd: Obdb.Command): Map<String, Any?>? {
        val hdrInt = cmd.hdr.toIntOrNull(16)
        val rxAddr = cmd.rax ?: if (hdrInt != null && cmd.hdr.length == 3) String.format("%03X", hdrInt + 8) else null
        if (currentHeader != cmd.hdr || currentRax != rxAddr || currentFcm1 != cmd.fcm1) {
            send("AT SH ${cmd.hdr}")
            if (rxAddr != null) {
                send("AT CRA $rxAddr")
            }
            if (cmd.fcm1) {
                send("AT FC SH ${cmd.hdr}")
                send("AT FC SD 30 00 00")
                send("AT FC SM 1")
            } else if (currentFcm1) {
                send("AT FC SM 0")
            }
            currentHeader = cmd.hdr
            currentRax = rxAddr
            currentFcm1 = cmd.fcm1
        }
        val resp = send(cmd.request)
        val payloads = ObdParser.extractPayloads(resp, echo = cmd.request)
        val data = ObdParser.findResponse(payloads, *cmd.responsePrefix) ?: return null
        return cmd.signals.associate { it.id to Obdb.decode(it, data) }
    }

    /** Call when you're done so the next ordinary request goes out on the functional header again. */
    suspend fun restoreHeader() {
        if (currentFcm1) {
            send("AT FC SM 0")
        }
        send("AT CRA")
        send("AT AR")
        send("AT SH 7DF")
        currentHeader = null
        currentRax = null
        currentFcm1 = false
    }
}

// ====================================================================== catalog + download + cache

class ObdbRepository(private val context: Context) {

    sealed class LoadResult {
        data class Loaded(
            val repo: String, val file: String, val commands: List<Obdb.Command>,
            val unsupported: Int, val fromCache: Boolean
        ) : LoadResult()
        data class NoData(val tried: List<String>) : LoadResult()   // none of these repo names exist in OBDb
        data class Unavailable(val reason: String) : LoadResult()   // offline and nothing cached
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    private val dir = File(context.filesDir, "obdb").also { it.mkdirs() }
    private val catalogFile = File(dir, "catalog.json")

    companion object {
        private const val CATALOG_MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private const val FILE_MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        private val YEAR_FILE = Regex("^(\\d{4})-(\\d{4})\\.json$")
        private val refreshing = AtomicBoolean(false)   // one catalog download at a time, app-wide
    }

    // ---------------------------------------------------------------- network state

    private fun caps(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }

    fun isOnWifi(): Boolean = caps()?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } ?: false

    // ---------------------------------------------------------------- catalog (list of vehicle repos)

    private val buildStampFile = File(dir, "catalog.build")
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Changes on every install or update of the app, including debug re-installs from Android Studio. */
    private fun currentBuildStamp(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    } catch (e: Exception) { "unknown" }

    /** True on first run, right after a new build is installed, or when the cached list is a week old. */
    fun isCatalogDue(): Boolean {
        if (!catalogFile.exists()) return true
        if (System.currentTimeMillis() - catalogFile.lastModified() >= CATALOG_MAX_AGE_MS) return true
        val saved = try { buildStampFile.readText() } catch (e: Exception) { "" }
        return saved != currentBuildStamp()
    }

    /**
     * Call once at app start with a long-lived scope (viewModelScope / lifecycleScope).
     * Refreshes right away if the phone is on Wi-Fi and a refresh is due, and otherwise keeps
     * watching: the moment a working Wi-Fi connection appears while the app is open, it refreshes.
     * Pair with stopAutoRefresh() when the owner is destroyed.
     */
    fun startAutoRefresh(scope: CoroutineScope) {
        scope.launch { refreshCatalogIfDue() }
        if (wifiCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // VALIDATED = this Wi-Fi really has internet (not e.g. an adapter's own Wi-Fi network)
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && isCatalogDue()) {
                    scope.launch(Dispatchers.IO) { fetchCatalog() }
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            // Could not register (permission/OS quirk): the launch-time refresh above still works.
        }
    }

    fun stopAutoRefresh() {
        val cb = wifiCallback ?: return
        try {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (e: Exception) { }
        wifiCallback = null
    }

    /** Refreshes the cached list of vehicle repos if due. Only runs on Wi-Fi. */
    suspend fun refreshCatalogIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && !isCatalogDue()) return@withContext false
        if (!isOnWifi()) return@withContext false
        fetchCatalog()
    }

    /** Blocking download of the repo list (call off the main thread). On any failure the old catalog is kept. */
    private fun fetchCatalog(): Boolean {
        if (!refreshing.compareAndSet(false, true)) return false
        try {
            val names = ArrayList<String>()
            var page = 1
            while (page <= 20) {
                val arr = JSONArray(httpGet("https://api.github.com/orgs/OBDb/repos?per_page=100&page=$page"))
                for (i in 0 until arr.length()) names.add(arr.getJSONObject(i).getString("name"))
                if (arr.length() < 100) break
                page++
            }
            if (names.isEmpty()) return false
            catalogFile.writeText(JSONArray(names).toString())
            buildStampFile.writeText(currentBuildStamp())   // only after success, so a failed try retries next time
            return true
        } catch (e: Exception) {
            return false   // rate-limited or offline
        } finally {
            refreshing.set(false)
        }
    }

    private fun readCatalog(): List<String> = try {
        if (!catalogFile.exists()) emptyList()
        else JSONArray(catalogFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (e: Exception) { emptyList() }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Maps NHTSA-style make/model ("FORD", "F-150") to a repo name ("Ford-F-150"). */
    fun findRepo(make: String, model: String): String? {
        val repos = readCatalog()
        val makeKey = norm(make)
        val key = makeKey + norm(model)
        repos.firstOrNull { norm(it) == key }?.let { return it }
        // e.g. model "F-150 Lariat" -> repo "Ford-F-150": longest repo name that prefixes the key
        return repos
            .filter { val n = norm(it); n.startsWith(makeKey) && n.length > makeKey.length && key.startsWith(n) }
            .maxByOrNull { it.length }
    }

    /**
     * Candidate repo names when the catalog is missing or doesn't match. Case doesn't matter on
     * GitHub, but hyphens do ("Ford-F-150" works, "Ford-F150" is a 404). Tries the full model,
     * then drops trailing words ("F-150 XLT" -> "F-150"), and also inserts a hyphen between
     * letters and digits ("F150" -> "F-150").
     */
    private fun guessRepos(make: String, model: String): List<String> {
        val mk = make.trim().lowercase().replaceFirstChar { it.uppercase() }
        val words = model.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        for (n in words.size downTo 1) {
            val m = words.take(n).joinToString("-")
            out.add("$mk-$m")
            out.add("$mk-" + m.replace(Regex("([A-Za-z])(\\d)"), "$1-$2"))
        }
        // Manufacturer platform fallbacks so unlisted models/trims still inherit shared ECU PIDs
        val mUpper = make.uppercase()
        val mModelUpper = model.uppercase()
        when {
            mUpper.contains("TOYOTA") || mUpper.contains("LEXUS") || mUpper.contains("SCION") -> {
                if (mModelUpper.contains("PRIUS") || mModelUpper.contains("HYBRID") || mModelUpper.contains("CT")) {
                    out.add("Toyota-Prius")
                }
                out.add("Toyota-Camry")
                out.add("Toyota-RAV4")
                out.add("Toyota-Tacoma")
            }
            mUpper.contains("FORD") || mUpper.contains("LINCOLN") || mUpper.contains("MERCURY") -> {
                out.add("Ford-F-150")
                out.add("Ford-Explorer")
                out.add("Ford-Mustang")
            }
            mUpper.contains("CHEV") || mUpper.contains("GMC") || mUpper.contains("BUICK") || mUpper.contains("CADILLAC") || mUpper == "GM" -> {
                out.add("Chevrolet-Silverado")
                out.add("Chevrolet-Equinox")
                out.add("Chevrolet-Tahoe")
            }
            mUpper.contains("HONDA") || mUpper.contains("ACURA") -> {
                out.add("Honda-Civic")
                out.add("Honda-Accord")
                out.add("Honda-CR-V")
            }
            mUpper.contains("SUBARU") -> {
                out.add("Subaru-Outback")
                out.add("Subaru-Forester")
                out.add("Subaru-WRX")
            }
            mUpper.contains("HYUNDAI") || mUpper.contains("KIA") || mUpper.contains("GENESIS") -> {
                out.add("Hyundai-Sonata")
                out.add("Hyundai-Elantra")
                out.add("Kia-Sorento")
            }
            mUpper.contains("JEEP") || mUpper.contains("DODGE") || mUpper.contains("RAM") || mUpper.contains("CHRYSLER") -> {
                out.add("Jeep-Grand-Cherokee")
                out.add("Chrysler-Pacifica")
                out.add("Dodge-Grand-Caravan")
                out.add("Jeep-Wrangler")
                out.add("Ram-1500")
            }
            mUpper.contains("VOLKSWAGEN") || mUpper.contains("VW") || mUpper.contains("AUDI") || mUpper.contains("PORSCHE") -> {
                out.add("Volkswagen-Golf")
                out.add("Volkswagen-Jetta")
                out.add("Audi-A4")
            }
            mUpper.contains("BMW") || mUpper.contains("MINI") -> {
                out.add("BMW-3-Series")
                out.add("BMW-X5")
            }
            mUpper.contains("NISSAN") || mUpper.contains("INFINITI") -> {
                out.add("Nissan-Altima")
                out.add("Nissan-Rogue")
                out.add("Nissan-Leaf")
            }
            mUpper.contains("MAZDA") -> {
                out.add("Mazda-CX-5")
                out.add("Mazda-3")
            }
            mUpper.contains("MERCEDES") -> {
                out.add("Mercedes-Benz-C-Class")
                out.add("Mercedes-Benz-E-Class")
            }
            mUpper.contains("VOLVO") -> {
                out.add("Volvo-XC60")
                out.add("Volvo-XC90")
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- per-vehicle file

    private fun listFiles(repo: String): List<String> {
        val cache = File(dir, "files__$repo.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < FILE_MAX_AGE_MS
        fun fromCache(): List<String>? = try {
            JSONArray(cache.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) { null }

        if (fresh) fromCache()?.let { return it }
        return try {
            val arr = JSONArray(httpGet("https://api.github.com/repos/OBDb/$repo/contents/signalsets/v3"))
            val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
                .filter { it.endsWith(".json") }
            cache.writeText(JSONArray(names).toString())
            names
        } catch (e: Exception) {
            fromCache() ?: listOf("default.json")
        }
    }

    /** Same rule as OBDb's reference loader: most specific year-range file, else default.json. */
    private fun pickFile(files: List<String>, year: Int): String =
        files.mapNotNull { f ->
            YEAR_FILE.matchEntire(f)?.let { Triple(f, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        }.filter { year in it.second..it.third }
            .minByOrNull { it.third - it.second }?.first ?: "default.json"

    /**
     * Fetch (or reuse the cached copy of) the parameter definitions for this vehicle.
     * Tries the catalog match first, then guessed repo names. Safe to call from any coroutine.
     * Works offline if the file was fetched before.
     */
    suspend fun loadSignalset(make: String, model: String, year: Int): LoadResult = withContext(Dispatchers.IO) {
        val candidates = (listOfNotNull(findRepo(make, model)) + guessRepos(make, model))
            .distinct().filter { SAFE_NAME.matches(it) }
        for (repo in candidates) {
            val r = loadFromRepo(repo, year) ?: continue   // null = no such repo, try the next name
            return@withContext r
        }
        LoadResult.NoData(candidates)
    }

    /** Returns null only when this repo name does not exist (HTTP 404 and nothing cached). */
    private fun loadFromRepo(repo: String, year: Int): LoadResult? {
        val file = pickFile(listFiles(repo), year)
        if (!SAFE_NAME.matches(file)) return null
        val cache = File(dir, "${repo}__$file")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < FILE_MAX_AGE_MS

        var text: String? = if (fresh) cache.readText() else null
        var fromCache = text != null
        if (text == null) {
            try {
                val body = httpGet("https://raw.githubusercontent.com/OBDb/$repo/main/signalsets/v3/$file")
                cache.writeText(body)
                text = body
            } catch (e: HttpStatusException) {
                if (e.code == 404 && !cache.exists()) return null
            } catch (e: Exception) {
                // offline / timeout: fall through to stale cache
            }
            if (text == null && cache.exists()) { text = cache.readText(); fromCache = true }
        }
        val body = text ?: return LoadResult.Unavailable("No connection and nothing cached for $repo")

        return try {
            val parsed = Obdb.parseSignalset(body, year)
            LoadResult.Loaded(repo, file, parsed.commands, parsed.unsupported, fromCache)
        } catch (e: Exception) {
            LoadResult.Unavailable("Could not parse $repo/$file: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- http

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "VERA-OBD-App")
        try {
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
