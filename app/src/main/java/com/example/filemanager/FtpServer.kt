package com.example.filemanager

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** FTP server ki settings (Remote screen se badalti hain) */
object FtpConfig {
    var port by mutableIntStateOf(2121)
    var user by mutableStateOf("admin")
    var pass by mutableStateOf("")
    var root by mutableStateOf("")
    var readOnly by mutableStateOf(false)

    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        val s = c.getSharedPreferences("ftp", Context.MODE_PRIVATE)
        port = s.getInt("port", 2121).coerceIn(1024, 65535)
        user = s.getString("user", "admin") ?: "admin"
        pass = s.getString("pass", null) ?: (100000..999999).random().toString()
        root = s.getString("root", null) ?: Environment.getExternalStorageDirectory().absolutePath
        readOnly = s.getBoolean("readOnly", false)
        loaded = true
        save(c)
    }

    fun save(c: Context) {
        c.getSharedPreferences("ftp", Context.MODE_PRIVATE).edit()
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .putString("root", root)
            .putBoolean("readOnly", readOnly)
            .apply()
    }
}

/** Server ki halat (UI isse padhta hai) */
object FtpState {
    var running by mutableStateOf(false)
    var address by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var clients by mutableIntStateOf(0)
}

/** Wi-Fi ya hotspot ka IPv4 address (jis par computer jud sakta hai) */
fun localIp(): String? {
    val wifi = mutableListOf<String>()
    val other = mutableListOf<String>()
    try {
        val nets = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in Collections.list(nets)) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in Collections.list(ni.inetAddresses)) {
                if (a !is Inet4Address || a.isLoopbackAddress) continue
                val ip = a.hostAddress ?: continue
                val n = ni.name
                if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) wifi.add(ip)
                else if (a.isSiteLocalAddress) other.add(ip)
            }
        }
    } catch (e: Exception) {
    }
    return wifi.firstOrNull() ?: other.firstOrNull()
}

/**
 * Chhota FTP server. USER/PASS, LIST, RETR (download), STOR (upload), MKD, DELE, RMD, RENAME,
 * PASV/EPSV/PORT waghera chalta hai. root folder ke bahar kuch nahi dikhta.
 */
class FtpServer(
    private val root: File,
    private val user: String,
    private val pass: String,
    private val port: Int,
    private val readOnly: Boolean,
    private val advertiseIp: String,
    private val onClients: (Int) -> Unit
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private val clients = AtomicInteger(0)
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())

    /** Port bind karta hai (fail hone par exception) aur accept loop alag thread me chalata hai */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        pool.execute {
            while (running) {
                try {
                    val c = s.accept()
                    sockets.add(c)
                    pool.execute { Session(c).run() }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (e: Exception) { } } }
        pool.shutdownNow()
    }

    private inner class Session(private val ctrl: Socket) {
        private val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.UTF_8))
        private val out = ctrl.getOutputStream()
        private var cwd = "/"
        private var userOk = false
        private var loggedIn = false
        private var pasv: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var renameFrom: File? = null
        private var rest = 0L

        fun run() {
            onClients(clients.incrementAndGet())
            try {
                ctrl.soTimeout = 10 * 60 * 1000
                send("220 All-In-One FM FTP server ready")
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!handle(line)) break
                }
            } catch (e: Exception) {
            } finally {
                closePasv()
                try { ctrl.close() } catch (e: Exception) { }
                sockets.remove(ctrl)
                onClients(clients.decrementAndGet())
            }
        }

        private fun send(s: String) {
            synchronized(out) {
                out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }

        /** Virtual path ko saaf karta hai (. aur .. hata kar), root se bahar nahi jane deta */
        private fun normalize(path: String): String {
            val parts = ArrayDeque<String>()
            val base = if (path.startsWith("/")) path else "$cwd/$path"
            for (p in base.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(p)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun real(path: String) = File(root, normalize(path))

        private fun closePasv() {
            try { pasv?.close() } catch (e: Exception) { }
            pasv = null
        }

        private fun openData(msg: String): Socket? {
            send(msg)
            return try {
                val p = pasv
                val a = active
                when {
                    p != null -> { p.soTimeout = 15000; p.accept() }
                    a != null -> Socket().also { it.connect(a, 10000) }
                    else -> null
                }
            } catch (e: Exception) {
                null
            } finally {
                closePasv()
            }
        }

        private fun fmtDate(t: Long): String {
            val recent = Math.abs(System.currentTimeMillis() - t) < 180L * 24 * 3600 * 1000
            return SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US).format(Date(t))
        }

        private fun listLine(f: File): String {
            val d = if (f.isDirectory) "d" else "-"
            val perm = if (readOnly) "r-xr-xr-x" else "rwxr-xr-x"
            val size = if (f.isFile) f.length() else 4096L
            return "$d$perm 1 owner group $size ${fmtDate(f.lastModified())} ${f.name}"
        }

        private fun readOnlyReply() = send("550 Server read-only hai")

        /** false lautaye to connection band ho jata hai */
        private fun handle(line: String): Boolean {
            val sp = line.indexOf(' ')
            val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase()
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim()

            if (!loggedIn && cmd !in setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "OPTS", "NOOP")) {
                send("530 Pehle login karo (USER aur PASS)")
                return true
            }

            when (cmd) {
                "USER" -> { userOk = arg == user; loggedIn = false; send("331 Password chahiye") }
                "PASS" -> {
                    if (userOk && arg == pass) { loggedIn = true; send("230 Login ho gaya") }
                    else { loggedIn = false; send("530 Login galat hai") }
                }
                "SYST" -> send("215 UNIX Type: L8")
                "FEAT" -> send("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End")
                "OPTS", "NOOP", "TYPE", "MODE", "STRU" -> send("200 OK")
                "PWD", "XPWD" -> send("257 \"$cwd\" is current directory")
                "CWD", "XCWD" -> {
                    val v = normalize(arg)
                    if (File(root, v).isDirectory) { cwd = v; send("250 OK") }
                    else send("550 Folder nahi mila")
                }
                "CDUP", "XCUP" -> { cwd = normalize(".."); send("250 OK") }
                "PASV" -> {
                    closePasv()
                    active = null
                    val s = ServerSocket(0)
                    pasv = s
                    val p = s.localPort
                    send("227 Entering Passive Mode (${advertiseIp.replace('.', ',')},${p shr 8},${p and 255})")
                }
                "EPSV" -> {
                    closePasv()
                    active = null
                    val s = ServerSocket(0)
                    pasv = s
                    send("229 Entering Extended Passive Mode (|||${s.localPort}|)")
                }
                "PORT" -> {
                    val n = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (n.size == 6) {
                        val host = "${n[0]}.${n[1]}.${n[2]}.${n[3]}"
                        // sirf usi computer ko data bhejna jisne connect kiya hai
                        if (host == ctrl.inetAddress.hostAddress) {
                            closePasv()
                            active = InetSocketAddress(host, n[4] * 256 + n[5])
                            send("200 PORT theek hai")
                        } else send("504 Address match nahi hua")
                    } else send("501 PORT galat hai")
                }
                "LIST", "NLST" -> {
                    val a = if (arg.startsWith("-")) arg.substringAfter(' ', "") else arg
                    val target = if (a.isEmpty()) File(root, cwd) else real(a)
                    if (!target.exists()) {
                        send("550 Nahi mila")
                    } else {
                        val files = if (target.isDirectory)
                            (target.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList())
                        else listOf(target)
                        val s = openData("150 Listing aa rahi hai")
                        if (s == null) send("425 Data connection nahi khul paya")
                        else try {
                            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                            for (f in files) {
                                if (cmd == "NLST") w.write(f.name + "\r\n") else w.write(listLine(f) + "\r\n")
                            }
                            w.flush()
                            send("226 Listing poori hui")
                        } catch (e: Exception) {
                            send("426 Transfer ruk gaya")
                        } finally {
                            try { s.close() } catch (e: Exception) { }
                        }
                    }
                }
                "RETR" -> {
                    val f = real(arg)
                    if (!f.isFile) send("550 File nahi mili")
                    else {
                        val s = openData("150 File bhej raha hoon")
                        if (s == null) send("425 Data connection nahi khul paya")
                        else try {
                            FileInputStream(f).use { fin ->
                                if (rest > 0) fin.channel.position(rest)
                                s.getOutputStream().use { os -> fin.copyTo(os, 64 * 1024) }
                            }
                            send("226 Transfer poora hua")
                        } catch (e: Exception) {
                            send("426 Transfer ruk gaya")
                        } finally {
                            rest = 0
                            try { s.close() } catch (e: Exception) { }
                        }
                    }
                }
                "STOR", "APPE" -> {
                    rest = 0
                    if (readOnly) readOnlyReply()
                    else {
                        val f = real(arg)
                        val parent = f.parentFile
                        if (parent == null || !parent.isDirectory || f.isDirectory) send("553 Yahan save nahi ho sakta")
                        else {
                            val s = openData("150 File bhejo")
                            if (s == null) send("425 Data connection nahi khul paya")
                            else try {
                                FileOutputStream(f, cmd == "APPE").use { fo ->
                                    s.getInputStream().copyTo(fo, 64 * 1024)
                                }
                                send("226 Transfer poora hua")
                            } catch (e: Exception) {
                                send("451 Save nahi hua")
                            } finally {
                                try { s.close() } catch (e: Exception) { }
                            }
                        }
                    }
                }
                "SIZE" -> {
                    val f = real(arg)
                    if (f.isFile) send("213 ${f.length()}") else send("550 File nahi hai")
                }
                "MDTM" -> {
                    val f = real(arg)
                    if (f.exists()) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        fmt.timeZone = TimeZone.getTimeZone("UTC")
                        send("213 ${fmt.format(Date(f.lastModified()))}")
                    } else send("550 Nahi mila")
                }
                "REST" -> { rest = arg.toLongOrNull() ?: 0L; send("350 $rest se dobara shuru") }
                "DELE" -> {
                    if (readOnly) readOnlyReply()
                    else if (real(arg).let { it.isFile && it.delete() }) send("250 Delete ho gaya")
                    else send("550 Delete nahi hua")
                }
                "RMD", "XRMD" -> {
                    if (readOnly) readOnlyReply()
                    else if (real(arg).let { it.isDirectory && it.delete() }) send("250 Folder hata diya")
                    else send("550 Folder nahi hata (khaali hona chahiye)")
                }
                "MKD", "XMKD" -> {
                    if (readOnly) readOnlyReply()
                    else {
                        val v = normalize(arg)
                        if (File(root, v).mkdirs()) send("257 \"$v\" ban gaya") else send("550 Folder nahi bana")
                    }
                }
                "RNFR" -> {
                    if (readOnly) readOnlyReply()
                    else {
                        val f = real(arg)
                        if (f.exists()) { renameFrom = f; send("350 Ab naya naam (RNTO) bhejo") }
                        else send("550 Nahi mila")
                    }
                }
                "RNTO" -> {
                    if (readOnly) readOnlyReply()
                    else {
                        val from = renameFrom
                        renameFrom = null
                        if (from != null && from.renameTo(real(arg))) send("250 Naam badal gaya")
                        else send("550 Naam nahi badla")
                    }
                }
                "ABOR" -> send("226 OK")
                "QUIT" -> { send("221 Alvida"); return false }
                else -> send("502 Ye command support nahi hai")
            }
            return true
        }
    }
}
