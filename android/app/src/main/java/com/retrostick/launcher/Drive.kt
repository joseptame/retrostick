package com.retrostick.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
// Device flow only allows drive.file / drive.appdata (developers.google.com/identity/protocols/oauth2/limited-input-device)
private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
const val FOLDER_MIME = "application/vnd.google-apps.folder"

class DriveException(message: String, val httpCode: Int = 0) : IOException(message)

/** Google sign-in with a code typed on the phone ("TVs and Limited Input devices" OAuth client). */
class DriveAuth(context: Context) {
    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private var accessToken: String? = null
    private var accessExpiry = 0L

    class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String,
                     val expiresAt: Long, val intervalSec: Int)

    enum class Outcome { CONNECTED, DENIED, EXPIRED, CANCELLED }

    val isConfigured get() = BuildConfig.OAUTH_CLIENT_ID.isNotEmpty() && BuildConfig.OAUTH_CLIENT_SECRET.isNotEmpty()
    val isConnected get() = prefs.getString("refresh_token", null) != null
    var email: String?
        get() = prefs.getString("email", null)
        set(value) = prefs.edit().putString("email", value).apply()

    fun requestDeviceCode(): DeviceCode {
        val (code, json) = postForm(DEVICE_CODE_URL, mapOf("client_id" to BuildConfig.OAUTH_CLIENT_ID, "scope" to SCOPE))
        if (code != 200) throw DriveException("device/code: ${json.optString("error", "HTTP $code")}", code)
        return DeviceCode(
            json.getString("device_code"), json.getString("user_code"), json.getString("verification_url"),
            System.currentTimeMillis() + json.getLong("expires_in") * 1000, json.optInt("interval", 5),
        )
    }

    /** Polls the token endpoint until the user answers on the phone. Blocking: call from a background thread. */
    fun waitForAuthorization(device: DeviceCode, cancelled: () -> Boolean): Outcome {
        var interval = device.intervalSec
        while (System.currentTimeMillis() < device.expiresAt) {
            Thread.sleep(interval * 1000L)
            if (cancelled()) return Outcome.CANCELLED
            val (code, json) = postForm(TOKEN_URL, mapOf(
                "client_id" to BuildConfig.OAUTH_CLIENT_ID,
                "client_secret" to BuildConfig.OAUTH_CLIENT_SECRET,
                "device_code" to device.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            if (code == 200) {
                storeTokens(json)
                return Outcome.CONNECTED
            }
            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> return Outcome.DENIED
                else -> throw DriveException("token: ${json.optString("error", "HTTP $code")}", code)
            }
        }
        return Outcome.EXPIRED
    }

    @Synchronized
    fun accessToken(): String {
        accessToken?.takeIf { System.currentTimeMillis() < accessExpiry - 60_000 }?.let { return it }
        val refresh = prefs.getString("refresh_token", null) ?: throw DriveException("Drive no conectado")
        val (code, json) = postForm(TOKEN_URL, mapOf(
            "client_id" to BuildConfig.OAUTH_CLIENT_ID,
            "client_secret" to BuildConfig.OAUTH_CLIENT_SECRET,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token",
        ))
        if (code != 200) {
            // Revoked or expired: the user has to connect again.
            if (json.optString("error") == "invalid_grant") disconnect()
            throw DriveException("refresh: ${json.optString("error", "HTTP $code")}", code)
        }
        storeTokens(json)
        return accessToken!!
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        accessToken = null
    }

    private fun storeTokens(json: JSONObject) {
        accessToken = json.getString("access_token")
        accessExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString("refresh_token", it).apply()
        }
    }

    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val body = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.responseCode to readJson(conn)
    }
}

/** Minimal Drive v3 REST client (only what the stick needs). */
class DriveClient(private val auth: DriveAuth) {

    class RemoteFile(val id: String, val name: String, val size: Long, val md5: String?,
                     val modified: Long, val isFolder: Boolean)

    fun list(parentId: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val q = URLEncoder.encode("'$parentId' in parents and trashed = false", "UTF-8")
            val fields = URLEncoder.encode("nextPageToken,files($FILE_FIELDS)", "UTF-8")
            val url = "$DRIVE_API/files?q=$q&fields=$fields&pageSize=1000&spaces=drive" +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = request("GET", url)
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += parse(files.getJSONObject(i))
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    /** Child folder by name, created when missing. Only folders created by RetroStick are visible (drive.file). */
    fun folder(parentId: String, name: String): String {
        list(parentId).firstOrNull { it.isFolder && it.name == name }?.let { return it.id }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parentId))
        return request("POST", "$DRIVE_API/files?fields=id", meta).getString("id")
    }

    /** [onProgress] gets the bytes written so far (a game passed from the Drive collection shows its progress). */
    fun download(id: String, target: File, onProgress: ((Long) -> Unit)? = null) {
        val conn = open("GET", "$DRIVE_API/files/$id?alt=media")
        if (conn.responseCode != 200) throw DriveException("download ${target.name}: HTTP ${conn.responseCode}", conn.responseCode)
        val tmp = File(target.parentFile, ".${target.name}.part")
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress?.invoke(done)
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw DriveException("No se pudo escribir ${target.path}")
        }
    }

    /** Deletes a file RetroStick can see (drive.file): the RetroShop pairing file once read. */
    fun delete(id: String) {
        val conn = open("DELETE", "$DRIVE_API/files/$id")
        val code = conn.responseCode
        conn.disconnect()
        if (code != 204 && code != 404) throw DriveException("delete: HTTP $code", code)
    }

    /** Moves a file to another folder (a game taken off the stick: JugandoAhora -> Coleccion). */
    fun move(id: String, toParent: String, fromParent: String) {
        patch("$DRIVE_API/files/$id?addParents=$toParent&removeParents=$fromParent&fields=id", JSONObject())
    }

    /** Sends a file to Drive's bin (recoverable for 30 days), as the webapp does. */
    fun trash(id: String) {
        patch("$DRIVE_API/files/$id?fields=id", JSONObject().put("trashed", true))
    }

    // HttpURLConnection has no PATCH; Google APIs accept the override header.
    private fun patch(url: String, body: JSONObject) {
        val conn = open("POST", url).apply { setRequestProperty("X-HTTP-Method-Override", "PATCH") }
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val json = readJson(conn)
        if (code !in 200..299) throw DriveException("PATCH ${url.substringBefore('?')}: HTTP $code $json", code)
    }

    /** Resumable upload; updates [existingId] in place (keeps Drive's revision history) or creates a new file. */
    fun upload(local: File, parentId: String, existingId: String?): RemoteFile {
        val meta = JSONObject().put("modifiedTime", rfc3339(local.lastModified()))
        val start = if (existingId == null) {
            meta.put("name", local.name).put("parents", JSONArray().put(parentId))
            open("POST", "$DRIVE_UPLOAD/files?uploadType=resumable&fields=$FILE_FIELDS")
        } else {
            // HttpURLConnection has no PATCH; Google APIs accept the override header.
            open("POST", "$DRIVE_UPLOAD/files/$existingId?uploadType=resumable&fields=$FILE_FIELDS")
                .apply { setRequestProperty("X-HTTP-Method-Override", "PATCH") }
        }
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
        start.setRequestProperty("X-Upload-Content-Length", local.length().toString())
        start.outputStream.use { it.write(meta.toString().toByteArray()) }
        if (start.responseCode != 200) throw DriveException("upload ${local.name}: ${readJson(start)}", start.responseCode)
        val session = start.getHeaderField("Location") ?: throw DriveException("upload ${local.name}: sin sesión")

        val put = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(local.length())
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        local.inputStream().use { input -> put.outputStream.use { input.copyTo(it, 64 * 1024) } }
        val code = put.responseCode
        if (code != 200 && code != 201) throw DriveException("upload ${local.name}: HTTP $code", code)
        return parse(readJson(put))
    }

    fun accountEmail(): String? =
        runCatching { request("GET", "$DRIVE_API/about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress") }
            .getOrNull()

    private fun request(method: String, url: String, body: JSONObject? = null): JSONObject {
        val conn = open(method, url)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val json = readJson(conn)
        if (code !in 200..299) throw DriveException("$method ${url.substringBefore('?')}: HTTP $code $json", code)
        return json
    }

    private fun open(method: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Authorization", "Bearer ${auth.accessToken()}")
    }

    private fun parse(j: JSONObject) = RemoteFile(
        j.getString("id"), j.getString("name"), j.optString("size", "0").toLongOrNull() ?: 0,
        j.optString("md5Checksum").takeIf { it.isNotEmpty() }, parseRfc3339(j.optString("modifiedTime")),
        j.optString("mimeType") == FOLDER_MIME,
    )

    companion object {
        private const val FILE_FIELDS = "id,name,size,md5Checksum,modifiedTime,mimeType"
        private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun rfc3339(millis: Long): String = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date(millis))
        fun parseRfc3339(s: String): Long =
            runCatching { utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").parse(s)!!.time }
                .recoverCatching { utc("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(s)!!.time }.getOrDefault(0)
    }
}

private fun readJson(conn: HttpURLConnection): JSONObject {
    val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text.take(300)) }
}
