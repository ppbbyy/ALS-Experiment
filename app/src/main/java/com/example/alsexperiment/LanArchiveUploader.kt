package com.example.alsexperiment

import org.json.JSONArray
import java.security.MessageDigest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object LanArchiveUploader {
    fun send(folder: File, cacheDir: File, address: String) {
        val base = URL(address.trim().trimEnd('/'))
        require(base.protocol == "http" && base.host.isNotBlank() && base.path.isEmpty() &&
            base.query == null && base.ref == null && base.userInfo == null) {
            "노트북 주소를 http://192.168.0.10:8765 형식으로 입력하세요."
        }
        val zip = File.createTempFile("als-export-", ".zip", cacheDir)
        try {
            zip.outputStream().use { RecordingArchive.write(folder, it) }
            require(zip.length() <= 64L * 1024 * 1024) { "ZIP은 최대 64MB까지 전송할 수 있습니다." }
            val hash = MessageDigest.getInstance("SHA-256")
            zip.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val checkConnection = URL("${base}/api/archives").openConnection() as HttpURLConnection
            try {
                checkConnection.connectTimeout = 5000
                checkConnection.readTimeout = 5000
                check(checkConnection.responseCode == 200) { "노트북 수신기를 최신 버전으로 실행하세요." }
                val archives = JSONArray(checkConnection.inputStream.bufferedReader().use { it.readText() })
                val existing = (0 until archives.length()).any { archives.getJSONObject(it).getString("id") == digest }
                check(archives.length() < 20 || existing) { "노트북 ZIP 보관함이 가득 찼습니다 (20/20). 새 전송을 시작하지 않았습니다." }
            } finally { checkConnection.disconnect() }
            val name = URLEncoder.encode("${folder.name}.zip", "UTF-8")
            val connection = URL("${base}/archives?name=$name").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 30000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.setFixedLengthStreamingMode(zip.length())
                connection.outputStream.use { output -> zip.inputStream().use { it.copyTo(output) } }
                val code = connection.responseCode
                check(code != 409) { "노트북에 ZIP이 20개 보관되어 새 전송이 거절됐습니다. 태블릿 원본은 보존됩니다." }
                check(code == 200) { "노트북 전송 실패 (HTTP $code)" }
                connection.inputStream.use { it.readBytes() }
            } finally { connection.disconnect() }
        } finally { zip.delete() }
    }
}
