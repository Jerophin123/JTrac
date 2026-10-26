import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject

// ---------------------------------------------------------------------------------------
// JTRAC release distribution for one target (OS + CPU architecture).
//
//   ./gradlew -PwithFrontend :distribution:release [-Ptarget=linux-arm64]
//
// The target defaults to the build machine. Other targets are cross-built: the runtime is
// linked with the build JDK's jlink against the target's jmods (pinned in
// runtime-jmods.properties). Unix archives are written with explicit permissions and the
// PostgreSQL symlinks preserved, whatever the build OS. Output: distribution/releases/<version>/.
// Nothing from the workspace is copied wholesale: the stage is assembled from an explicit
// allowlist and scanned before and after archiving (docs/build-and-release.md).
// ---------------------------------------------------------------------------------------

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Build-time only (never shipped): tar/xz handling with Unix modes and symlinks.
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.tukaani:xz:1.12")
    }
}

plugins {
    base
}

evaluationDependsOn(":backend")
evaluationDependsOn(":launcher")

val releaseVersion = rootProject.version.toString()
val hostTarget: String = run {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val o = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
    val a = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
    "$o-$a"
}
val target: String = providers.gradleProperty("target").getOrElse(hostTarget)
require(target in listOf("windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64")) {
    "Unknown target '$target'"
}
val jmods = Properties().apply { file("runtime-jmods.properties").reader().use { load(it) } }
val crossTarget = target != hostTarget
require(!crossTarget || jmods.getProperty("$target.url") != null) {
    "Target $target differs from this machine ($hostTarget) and has no pinned jmods in runtime-jmods.properties."
}
val windowsTarget = target.startsWith("windows")
val releaseName = "jtrac-$releaseVersion-$target"
val releasesDir: Directory = layout.projectDirectory.dir("releases/$releaseVersion")
val pg = Properties().apply { file("postgres-binaries.properties").reader().use { load(it) } }
val pgArtifact: String? = pg.getProperty("$target.artifact")

/** Modules for the jlink runtime: from jdeps over the server and launcher (docs/build-and-release.md). */
val runtimeModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging", "java.management",
    "java.naming", "java.net.http", "java.prefs", "java.rmi", "java.scripting", "java.security.jgss",
    "java.security.sasl", "java.sql", "java.sql.rowset", "java.xml", "jdk.charsets", "jdk.crypto.cryptoki",
    "jdk.jfr", "jdk.management", "jdk.unsupported", "jdk.zipfs",
)

// Release builds are rare and must always run fully; the task classes below are declared in
// this script, which the configuration cache can't serialize.
tasks.configureEach {
    notCompatibleWithConfigurationCache("release packaging tasks are declared in the build script")
}

// --- Task types ------------------------------------------------------------------------

/** Downloads a file once and verifies its pinned checksums; a mismatch fails the build. */
abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input @get:Optional abstract val sha1: Property<String>
    @get:Input @get:Optional abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val target = output.get().asFile.toPath()
        if (!Files.exists(target) || !matches(target)) {
            Files.createDirectories(target.parent)
            val temp = target.resolveSibling(target.fileName.toString() + ".part")
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val response = client.send(HttpRequest.newBuilder(URI.create(url.get())).GET().build(), HttpResponse.BodyHandlers.ofFile(temp))
            if (response.statusCode() != 200) {
                throw GradleException("Download failed (${response.statusCode()}): ${url.get()}")
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        if (!matches(target)) {
            Files.delete(target)
            throw GradleException("Checksum mismatch for ${url.get()}: refusing to use it.")
        }
    }

    private fun matches(file: Path): Boolean {
        if (!sha1.isPresent && !sha256.isPresent) return false
        if (sha1.isPresent && digest(file, "SHA-1") != sha1.get()) return false
        return !sha256.isPresent || digest(file, "SHA-256") == sha256.get()
    }

    private fun digest(file: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return HexFormat.of().formatHex(md.digest())
    }
}

/**
 * Unpacks the PostgreSQL .txz from the binaries jar (pure Java, any build OS) for staging and
 * scanning. Symlinks are written as copies here; the Unix archive itself keeps them (UnixTarGz).
 */
abstract class ExtractPostgres @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputFile abstract val jar: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun extract() {
        val out = output.get().asFile.toPath()
        fs.delete { delete(out.toFile()) }
        Files.createDirectories(out)
        val links = mutableListOf<Pair<Path, String>>()
        PostgresTxz.read(jar.get().asFile) { entry, input ->
            val target = out.resolve(entry.name).normalize()
            require(target.startsWith(out)) { "Entry escapes the target: ${entry.name}" }
            when {
                entry.isDirectory -> Files.createDirectories(target)
                entry.isSymbolicLink -> links += target to entry.linkName
                else -> {
                    Files.createDirectories(target.parent)
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        for ((link, name) in links) {
            Files.copy(link.parent.resolve(name).normalize(), link, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Reads the PostgreSQL tar.xz embedded in a zonky binaries jar. */
object PostgresTxz {
    fun read(jar: File, action: (TarArchiveEntry, InputStream) -> Unit) {
        ZipFile(jar).use { zip ->
            val txz = zip.entries().asSequence().first { it.name.endsWith(".txz") }
            TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(zip.getInputStream(txz)))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    action(entry, tar)
                }
            }
        }
    }
}

/** Links a minimal Java runtime with jlink. */
abstract class JlinkRuntime @Inject constructor(private val exec: ExecOperations, private val fs: FileSystemOperations) : DefaultTask() {
    @get:Input abstract val jlink: Property<String>
    @get:Input abstract val modules: ListProperty<String>
    @get:InputFiles @get:Optional abstract val targetJmods: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun link() {
        val image = output.get().asFile.resolve("runtime")
        fs.delete { delete(image) }
        val args = mutableListOf(jlink.get(), "--add-modules", modules.get().joinToString(","), "--strip-debug",
            "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", image.absolutePath)
        if (targetJmods.isPresent) {
            val base = targetJmods.get().asFile.walkTopDown().first { it.name == "java.base.jmod" }
            args.addAll(listOf("--module-path", base.parentFile.absolutePath))
        }
        exec.exec { commandLine(args) }
    }
}

/**
 * Writes the Unix package as tar.gz with explicit modes (scripts and binaries 0755, data 0644)
 * and PostgreSQL's own entries straight from its .txz, so symlinks and modes are exact even
 * when the package is built on Windows. Stable order, fixed timestamps, root:root ownership.
 */
abstract class UnixTarGz : DefaultTask() {
    @get:InputDirectory abstract val stage: DirectoryProperty
    @get:InputFile @get:Optional abstract val postgresJar: RegularFileProperty
    @get:Input abstract val prefix: Property<String>
    @get:Input abstract val allowOverwrite: Property<Boolean>
    @get:OutputFile abstract val archive: RegularFileProperty

    @TaskAction
    fun write() {
        val out = archive.get().asFile
        if (out.exists() && !allowOverwrite.get()) {
            throw GradleException("$out already exists. Releases are never overwritten silently; delete it or pass -PoverwriteRelease.")
        }
        out.parentFile.mkdirs()
        val root = stage.get().asFile.toPath()
        val time = java.util.Date(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())
        val pfx = prefix.get()
        val pgFromJar = postgresJar.isPresent
        TarArchiveOutputStream(java.util.zip.GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(out.toPath())))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            fun put(name: String, mode: Int, type: Byte = TarArchiveEntry.LF_NORMAL.toByte(), size: Long = 0, link: String? = null,
                    data: InputStream? = null) {
                val entry = if (link != null) TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK) else TarArchiveEntry(name, type)
                link?.let { entry.linkName = it }
                entry.mode = mode
                entry.modTime = time
                entry.setIds(0, 0)
                entry.setNames("root", "root")
                if (!entry.isDirectory && link == null) entry.size = size
                tar.putArchiveEntry(entry)
                data?.copyTo(tar)
                tar.closeArchiveEntry()
            }
            put("$pfx/", 0b111_101_101 or 0x4000, TarArchiveEntry.LF_DIR.toByte())
            val files = Files.walk(root).use { it.sorted().toList() }
            for (path in files) {
                if (path == root) continue
                val rel = root.relativize(path).toString().replace('\\', '/')
                if (pgFromJar && (rel == "pgsql" || rel.startsWith("pgsql/"))) continue
                if (Files.isDirectory(path)) {
                    put("$pfx/$rel/", 0b111_101_101, TarArchiveEntry.LF_DIR.toByte())
                } else {
                    Files.newInputStream(path).use { input -> put("$pfx/$rel", modeFor(rel), size = Files.size(path), data = input) }
                }
            }
            if (pgFromJar) {
                put("$pfx/pgsql/", 0b111_101_101, TarArchiveEntry.LF_DIR.toByte())
                PostgresTxz.read(postgresJar.get().asFile) { entry, input ->
                    val name = entry.name.removePrefix("./")
                    if (!(name.startsWith("bin/") || name.startsWith("lib/") || name.startsWith("share/"))) return@read
                    when {
                        entry.isDirectory -> put("$pfx/pgsql/${name.trimEnd('/')}/", 0b111_101_101, TarArchiveEntry.LF_DIR.toByte())
                        entry.isSymbolicLink -> put("$pfx/pgsql/$name", 0b111_111_111, link = entry.linkName)
                        else -> put("$pfx/pgsql/$name", if (name.startsWith("bin/")) 0b111_101_101 else entry.mode and 0b111_111_111,
                            size = entry.size, data = input)
                    }
                }
            }
        }
    }

    companion object {
        /** Scripts, the Java launcher binaries and their helpers are executable; everything else is data. */
        fun modeFor(rel: String): Int {
            val exec = rel.matches(Regex("[a-z-]+\\.sh")) || rel == "bin/jtrac" || rel.startsWith("runtime/bin/") ||
                rel == "runtime/lib/jspawnhelper" || rel == "runtime/lib/jexec" || rel.startsWith("pgsql/bin/")
            return if (exec) 0b111_101_101 else 0b110_100_100
        }
    }
}

/** Unpacks a tar.gz for scanning: symlinks become copies of their targets. */
abstract class ExtractTarGz @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputFile abstract val archive: RegularFileProperty
    @get:Input abstract val stripPrefix: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun extract() {
        val out = output.get().asFile.toPath()
        fs.delete { delete(out.toFile()) }
        Files.createDirectories(out)
        val links = mutableListOf<Pair<Path, String>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(Files.newInputStream(archive.get().asFile.toPath())))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = entry.name.removePrefix(stripPrefix.get() + "/")
                if (rel.isEmpty()) continue
                val target = out.resolve(rel).normalize()
                require(target.startsWith(out)) { "Entry escapes the target: ${entry.name}" }
                when {
                    entry.isDirectory -> Files.createDirectories(target)
                    entry.isSymbolicLink -> links += target to entry.linkName
                    else -> {
                        Files.createDirectories(target.parent)
                        Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
        for ((link, name) in links) {
            Files.copy(link.parent.resolve(name).normalize(), link, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Checks what can be checked about a Unix package without running it on the target: every
 * entry under one top folder (no absolute paths, no ".."), executables really executable,
 * symlinks resolving inside the archive, shell scripts with a shebang and LF line endings,
 * and the CPU architecture of the Java and PostgreSQL binaries (ELF / Mach-O headers).
 */
abstract class UnixArchiveCheck : DefaultTask() {
    @get:InputFile abstract val archive: RegularFileProperty
    @get:Input abstract val prefix: Property<String>
    @get:Input abstract val os: Property<String>
    @get:Input abstract val arch: Property<String>
    @get:Input abstract val expectPostgres: Property<Boolean>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val pfx = prefix.get()
        val entries = linkedMapOf<String, TarArchiveEntry>()
        val heads = mutableMapOf<String, ByteArray>()
        val scripts = mutableMapOf<String, String>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(Files.newInputStream(archive.get().asFile.toPath())))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                entries[e.name.trimEnd('/')] = e
                val rel = e.name.removePrefix("$pfx/")
                if (e.isFile && (rel.endsWith(".sh") || rel == "bin/jtrac")) scripts[rel] = String(tar.readAllBytes(), Charsets.UTF_8)
                else if (e.isFile && e.size >= 64) heads[rel] = tar.readNBytes(1 shl 16)
            }
        }
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for ((name, e) in entries) {
            if (!(name == pfx || name.startsWith("$pfx/")) || name.startsWith("/") || name.split('/').contains("..")) {
                problems += "entry outside $pfx/: $name"
            }
            if (e.isSymbolicLink) {
                val resolved = java.nio.file.Paths.get(name).parent.resolve(e.linkName).normalize().toString().replace('\\', '/')
                if (entries[resolved]?.isFile != true) problems += "symlink $name -> ${e.linkName} does not resolve to a file in the archive"
            }
        }
        val mustExec = mutableListOf("bin/jtrac", "setup.sh", "start.sh", "stop.sh", "runtime/bin/java", "runtime/lib/jspawnhelper")
        if (expectPostgres.get()) mustExec += listOf("pgsql/bin/postgres", "pgsql/bin/initdb", "pgsql/bin/pg_ctl")
        for (rel in mustExec) {
            val e = entries["$pfx/$rel"]
            when {
                e == null -> problems += "missing $rel"
                e.mode and 0b001_001_001 != 0b001_001_001 -> problems += "$rel is not executable (mode ${Integer.toOctalString(e.mode)})"
            }
        }
        for ((rel, text) in scripts) {
            if (!text.startsWith("#!/bin/sh")) problems += "$rel has no #!/bin/sh shebang"
            if (text.contains('\r')) problems += "$rel has CR line endings"
        }
        val binaries = mutableListOf("runtime/bin/java", "runtime/lib/server/libjvm.${if (os.get() == "macos") "dylib" else "so"}")
        if (expectPostgres.get()) binaries += listOf("pgsql/bin/postgres", "pgsql/bin/initdb")
        for (rel in binaries) {
            val head = heads[rel]
            if (head == null) { problems += "missing binary $rel"; continue }
            val found = describe(head)
            notes += "$rel: $found"
            if (!found.startsWith(expected())) problems += "$rel is $found, expected ${expected()}"
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("JTRAC Unix archive check: ${archive.get().asFile.name} (${entries.size} entries)")
            notes.forEach { appendLine("  $it") }
            if (problems.isEmpty()) appendLine("PASSED: layout, permissions, symlinks, scripts, binary architecture")
            else problems.forEach { appendLine("FAIL $it") }
        })
        if (problems.isNotEmpty()) throw GradleException("Unix archive check failed:\n" + problems.joinToString("\n"))
        logger.lifecycle("Unix archive check passed: " + notes.joinToString("; "))
    }

    private fun expected(): String = when (os.get() to arch.get()) {
        "linux" to "x64" -> "ELF 64-bit x86-64"
        "linux" to "arm64" -> "ELF 64-bit AArch64"
        "macos" to "x64" -> "Mach-O 64-bit x86_64"
        else -> "Mach-O 64-bit arm64"
    }

    /** Reads the CPU type from an ELF or Mach-O header (thin or universal). */
    private fun describe(h: ByteArray): String {
        fun le16(o: Int) = (h[o].toInt() and 0xff) or ((h[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        fun be32(o: Int) = ((h[o].toInt() and 0xff) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        fun cpu(t: Int) = when (t) { 0x01000007 -> "x86_64"; 0x0100000C -> "arm64"; else -> "cputype 0x" + Integer.toHexString(t) }
        if (h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) {
            val bits = if (h[4].toInt() == 2) "64-bit" else "32-bit"
            val machine = when (le16(18)) { 0x3E -> "x86-64"; 0xB7 -> "AArch64"; else -> "machine 0x" + Integer.toHexString(le16(18)) }
            return "ELF $bits $machine"
        }
        if (le32(0) == 0xfeedfacf.toInt()) return "Mach-O 64-bit " + cpu(le32(4))
        if (be32(0) == 0xcafebabe.toInt()) {
            val n = be32(4)
            val archs = (0 until n).map { cpu(be32(8 + it * 20)) }
            return "Mach-O 64-bit " + (archs.firstOrNull { it == (if (arch.get() == "x64") "x86_64" else "arm64") } ?: archs.joinToString("+")) + " (universal: ${archs.joinToString(", ")})"
        }
        return "unknown format"
    }
}

/**
 * Release gate: every file must match the allowlist, nothing on the denylist may appear,
 * text files and the server jar must not contain private keys, credentials or local data.
 */
abstract class VerifyRelease : DefaultTask() {
    @get:InputDirectory abstract val root: DirectoryProperty
    @get:Input abstract val label: Property<String>
    @get:Input abstract val forbiddenText: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val base = root.get().asFile.toPath()
        val allowed = listOf(
            Regex("^(setup|start|stop|restart|status|configure|test-email|backup|restore|update|uninstall|logs)\\.(bat|sh)$"),
            Regex("^bin/(jtrac|jtrac\\.cmd)$"),
            Regex("^lib/jtrac-server\\.jar$"),
            Regex("^lib/launcher/[A-Za-z0-9._-]+\\.jar$"),
            Regex("^runtime/.+"),
            Regex("^pgsql/(bin|lib|share)/.+"),
            Regex("^docs/[a-z0-9-]+\\.md$"),
            Regex("^licenses/.+\\.(md|txt|json|html)$"),
            Regex("^config-templates/[a-z0-9.-]+\\.example\\.properties$"),
            Regex("^migrations/V\\d+__[a-z0-9_]+\\.sql$"),
            Regex("^(README\\.txt|VERSION|RELEASE-NOTES\\.md)$"),
        )
        val denied = listOf(
            Regex("(^|/)\\.env(\\..*)?$"), Regex("(^|/)\\.git(/|$)"), Regex("(^|/)node_modules/"),
            Regex("(^|/)(secrets|admin-secrets|jtrac|setup-state)\\.properties$"), Regex("(^|/)setup-code\\.txt$"),
            Regex("(^|/)pgpass(\\.conf)?$"), Regex("\\.(pem|key|p12|pfx|crt|csr|pgdump|eml|log|bak|tmp|partial)$"),
            Regex("(^|/)(PG_VERSION|postmaster\\.pid|postmaster\\.opts|pg_hba\\.conf|postgresql\\.auto\\.conf)$"),
            Regex("(^|/)(base|global|pg_wal|pg_xact)/"), Regex("(^|/)jtrac-backup-[^/]*\\.zip$"), Regex("(^|/)data/"),
            Regex("(^|/)attachments/"), Regex("(^|/)(jtac_dev|jtac_test)"), Regex("\\.jks$"),
        )
        // The runtime's CA bundle is a public trust store, not a secret.
        val deniedExceptions = setOf("runtime/lib/security/cacerts")
        val secretPatterns = listOf(
            Regex("-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
            Regex("AKIA[0-9A-Z]{16}"), Regex("gh[pousr]_[A-Za-z0-9]{36,}"), Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Regex("(?m)^\\s*(spring\\.datasource\\.password|jtac\\.mail\\.smtp\\.password|jtac\\.integrations\\.secret-key|jtrac\\.database\\.(owner|superuser)-password)\\s*=\\s*[^<\\s].*$"),
            Regex("jdbc:postgresql://[^\\s\"']*password="),
        ) + forbiddenText.get().filter { it.length >= 6 }.flatMap {
            listOf(Regex(Regex.escape(it), RegexOption.IGNORE_CASE), Regex(Regex.escape(it.replace('\\', '/')), RegexOption.IGNORE_CASE),
                Regex(Regex.escape(it.replace("\\", "\\\\")), RegexOption.IGNORE_CASE))
        }
        val textExtensions = setOf("md", "txt", "properties", "yaml", "yml", "json", "cmd", "bat", "sh", "sql", "xml", "html", "js", "css", "conf", "sample")
        val problems = mutableListOf<String>()
        var files = 0
        Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                files++
                val rel = base.relativize(file).toString().replace('\\', '/')
                if (allowed.none { it.containsMatchIn(rel) }) problems += "not on the allowlist: $rel"
                if (rel !in deniedExceptions && denied.any { it.containsMatchIn(rel) } && !rel.startsWith("pgsql/share/")) {
                    problems += "prohibited file: $rel"
                }
                val ext = rel.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val scanText = ext in textExtensions || rel.startsWith("bin/") || rel.endsWith("VERSION")
                if (scanText && !rel.startsWith("runtime/") && !rel.startsWith("pgsql/")) {
                    val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                    secretPatterns.firstOrNull { it.containsMatchIn(text) }?.let { problems += "possible secret in $rel (${it.pattern.take(40)})" }
                }
                if (rel.endsWith(".jar")) {
                    ZipFile(file.toFile()).use { zip ->
                        for (entry in zip.entries().asSequence()) {
                            val name = entry.name
                            if (Regex("(^|/)(secrets|admin-secrets)\\.properties$|\\.(pem|key|p12|pfx|jks|eml|pgdump)$|(^|/)\\.env$|application-secrets|application-test\\.ya?ml$|(^|/)data/").containsMatchIn(name)) {
                                problems += "prohibited entry $name in $rel"
                            }
                            val entryExt = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (!entry.isDirectory && entryExt in setOf("properties", "yaml", "yml", "json", "xml", "txt", "html", "js", "sql") && entry.size < 5_000_000) {
                                val text = zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }
                                secretPatterns.firstOrNull { it.containsMatchIn(text) }?.let { problems += "possible secret in $rel!/$name (${it.pattern.take(40)})" }
                            }
                        }
                    }
                }
            }
        }
        val server = base.resolve("lib/jtrac-server.jar")
        if (Files.exists(server)) {
            ZipFile(server.toFile()).use { zip ->
                if (zip.getEntry("BOOT-INF/classes/static/index.html") == null) {
                    problems += "lib/jtrac-server.jar has no web UI (build the frontend and use -PwithFrontend)"
                }
            }
        } else {
            problems += "lib/jtrac-server.jar is missing"
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("JTRAC release verification (${label.get()}): $files files checked")
            if (problems.isEmpty()) appendLine("PASSED: allowlist, denylist and secret scan") else problems.forEach { appendLine("FAIL $it") }
        })
        if (problems.isNotEmpty()) {
            throw GradleException("Release verification failed (${problems.size} problems):\n" + problems.take(50).joinToString("\n"))
        }
        logger.lifecycle("Release verification (${label.get()}) passed: $files files")
    }
}

// --- Inputs from the other projects ------------------------------------------------------

val serverJar = project(":backend").tasks.named<Jar>("bootJar")
val launcherLibs = project(":launcher").tasks.named("launcherLibs")
val migrations = rootProject.layout.projectDirectory.dir("backend/src/main/resources/db/migration")
val docsDir = rootProject.layout.projectDirectory.dir("docs")
val generated = layout.buildDirectory.dir("generated")

val downloadPostgres = tasks.register<DownloadVerified>("downloadPostgres") {
    description = "Downloads the pinned PostgreSQL binaries for $target."
    onlyIf { pgArtifact != null }
    val version = pg.getProperty("version")
    val artifact = pgArtifact ?: "none"
    url = "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-$artifact/$version/embedded-postgres-binaries-$artifact-$version.jar"
    sha1 = pg.getProperty("$target.sha1") ?: "none"
    pg.getProperty("$target.sha256")?.let { sha256 = it }
    output = layout.buildDirectory.file("downloads/postgres-$artifact-$version.jar")
}

val extractPostgres = tasks.register<ExtractPostgres>("extractPostgres") {
    description = "Unpacks the PostgreSQL binaries for $target."
    onlyIf { pgArtifact != null }
    dependsOn(downloadPostgres)
    jar = downloadPostgres.flatMap { it.output }
    output = layout.buildDirectory.dir("pgsql/$target")
}

val downloadJmods = tasks.register<DownloadVerified>("downloadJmods") {
    description = "Downloads the pinned Temurin jmods for $target (cross-linking)."
    onlyIf { crossTarget }
    url = jmods.getProperty("$target.url") ?: "none"
    jmods.getProperty("$target.sha256")?.let { sha256 = it }
    output = layout.buildDirectory.file("downloads/jmods-$target.tar.gz")
}

val extractJmods = tasks.register<Sync>("extractJmods") {
    description = "Unpacks the target jmods."
    onlyIf { crossTarget }
    dependsOn(downloadJmods)
    from(downloadJmods.flatMap { it.output }.map { tarTree(resources.gzip(it)) })
    into(layout.buildDirectory.dir("jmods/$target"))
}

// jlink of the JDK 25 that runs Gradle (JAVA_HOME): the runtime matches the build machine.
val buildJdk: String = System.getProperty("java.home")
require(Runtime.version().feature() == 25) { "Build with JDK 25 (JAVA_HOME); found ${Runtime.version()}" }
val runtimeImage = tasks.register<JlinkRuntime>("runtimeImage") {
    description = "Links the Java runtime for $target."
    val exe = if (hostTarget.startsWith("windows")) "jlink.exe" else "jlink"
    jlink = File(buildJdk, "bin/$exe").absolutePath
    modules = runtimeModules
    if (crossTarget) {
        dependsOn(extractJmods)
        targetJmods = layout.buildDirectory.dir("jmods/$target")
    }
    output = layout.buildDirectory.dir("runtime/$target")
}

val writeVersionFiles = tasks.register("writeVersionFiles") {
    description = "Writes VERSION and README.txt for the package."
    val out = generated.map { it.dir("package") }
    val pgVersion = if (pgArtifact != null) pg.getProperty("version") else "external PostgreSQL 18 required"
    val tgt = target
    val ver = releaseVersion
    val windows = windowsTarget
    outputs.dir(out)
    inputs.property("version", ver)
    inputs.property("target", tgt)
    doLast {
        val dir = out.get().asFile
        dir.mkdirs()
        dir.resolve("VERSION").writeText("JTRAC $ver\ntarget=$tgt\njava=25 (bundled runtime)\npostgresql=$pgVersion\n")
        val cmd = if (windows) "setup.bat" else "./setup.sh"
        val jtrac = if (windows) "bin\\jtrac.cmd" else "bin/jtrac"
        dir.resolve("README.txt").writeText("""
            JTRAC $ver ($tgt)
            ==================

            JTRAC is a self-hosted project and issue tracker. This package contains everything it
            needs: the server, a Java runtime${if (pgArtifact != null) " and PostgreSQL $pgVersion" else ""}. No JDK, Node.js or Gradle is required.

            Install
              1. Unpack this archive into a folder you keep (for example ${if (windows) "C:\\JTRAC" else "/opt/jtrac or ~/jtrac"}).
              2. Run $cmd and answer the questions (database, administrator, port, email).
              3. Open the address it prints and sign in.

            Daily use:  $jtrac status | start | stop | restart | logs | backup | configure | test-email
            Help:       $jtrac help    Documentation: docs/installation.md

            Your data lives in a separate data directory (shown by `jtrac status`) and is kept when you
            update or uninstall JTRAC.
        """.trimIndent() + "\n")
    }
}

val verifyServerJar = tasks.register("verifyServerJar") {
    description = "Fails unless the server jar contains the production web UI."
    dependsOn(serverJar)
    val jar = serverJar.flatMap { it.archiveFile }
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            if (zip.getEntry("BOOT-INF/classes/static/index.html") == null) {
                throw GradleException("The server jar has no web UI. Run `pnpm install --frozen-lockfile && pnpm build` in frontend/, then build with -PwithFrontend.")
            }
        }
    }
}

// --- Licenses and SBOMs ---------------------------------------------------------------------

val serverBom = project(":backend").tasks.named("cyclonedxDirectBom")
val launcherBom = project(":launcher").tasks.named("cyclonedxDirectBom")
val frontendDir = rootProject.layout.projectDirectory.dir("frontend")

val frontendLicenses = tasks.register<Exec>("frontendLicenses") {
    description = "Lists the licenses of the web UI's production dependencies (pnpm)."
    workingDir = frontendDir.asFile
    val out = layout.buildDirectory.file("generated/frontend-licenses.raw.json")
    outputs.file(out)
    inputs.file(frontendDir.file("pnpm-lock.yaml"))
    commandLine(if (hostTarget.startsWith("windows")) listOf("cmd", "/c", "pnpm", "licenses", "list", "--prod", "--json")
        else listOf("pnpm", "licenses", "list", "--prod", "--json"))
    doFirst { standardOutput = out.get().asFile.outputStream() }
}

val licenseNotices = tasks.register("licenseNotices") {
    description = "Writes THIRD-PARTY notices and SBOMs for the package (no local paths)."
    dependsOn(serverBom, launcherBom, frontendLicenses)
    val serverJson = project(":backend").layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")
    val launcherJson = project(":launcher").layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")
    val webJson = layout.buildDirectory.file("generated/frontend-licenses.raw.json")
    val out = generated.map { it.dir("licenses") }
    inputs.files(serverJson, launcherJson, webJson)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.mkdirs()
        serverJson.get().asFile.copyTo(dir.resolve("sbom-server.json"), overwrite = true)
        launcherJson.get().asFile.copyTo(dir.resolve("sbom-launcher.json"), overwrite = true)
        val slurper = groovy.json.JsonSlurper()
        fun javaSection(title: String, file: File): String = buildString {
            @Suppress("UNCHECKED_CAST")
            val bom = slurper.parse(file) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val components = (bom["components"] as List<Map<String, Any?>>? ?: emptyList()).sortedBy { "${it["group"]}:${it["name"]}" }
            appendLine("## $title (${components.size})")
            appendLine()
            appendLine("| Component | Version | License |")
            appendLine("|---|---|---|")
            for (c in components) {
                @Suppress("UNCHECKED_CAST")
                val licenses = (c["licenses"] as List<Map<String, Any?>>? ?: emptyList()).mapNotNull { l ->
                    (l["license"] as Map<String, Any?>?)?.let { it["id"] ?: it["name"] } ?: l["expression"]
                }.joinToString(", ").ifBlank { "see project" }
                appendLine("| ${c["group"] ?: ""}:${c["name"]} | ${c["version"] ?: ""} | $licenses |")
            }
            appendLine()
        }
        dir.resolve("THIRD-PARTY-JAVA.md").writeText("# Java libraries in the JTRAC package\n\n" +
            "Generated from the release build's CycloneDX SBOMs (runtime dependencies only).\n\n" +
            javaSection("Server (lib/jtrac-server.jar)", serverJson.get().asFile) +
            javaSection("Launcher (lib/launcher)", launcherJson.get().asFile))
        @Suppress("UNCHECKED_CAST")
        val web = slurper.parse(webJson.get().asFile) as Map<String, List<Map<String, Any?>>>
        dir.resolve("THIRD-PARTY-WEB.md").writeText(buildString {
            appendLine("# Web UI libraries in the JTRAC package")
            appendLine()
            appendLine("Production dependencies bundled into the web UI (from `pnpm licenses list --prod`).")
            appendLine()
            appendLine("| Package | Version | License | Homepage |")
            appendLine("|---|---|---|---|")
            web.flatMap { (license, packages) -> packages.map { license to it } }.sortedBy { it.second["name"].toString() }.forEach { (license, pkg) ->
                @Suppress("UNCHECKED_CAST")
                val versions = (pkg["versions"] as List<Any?>? ?: emptyList()).joinToString(", ")
                appendLine("| ${pkg["name"]} | $versions | $license | ${pkg["homepage"] ?: ""} |")
            }
        })
    }
}

val stageDir = layout.buildDirectory.dir("stage/$releaseName")
val stageRelease = tasks.register<Sync>("stageRelease") {
    description = "Assembles the $target package from the allowlisted parts only."
    dependsOn(verifyServerJar)
    into(stageDir)
    from(serverJar.flatMap { it.archiveFile }) {
        into("lib")
        rename { "jtrac-server.jar" }
    }
    from(launcherLibs) { into("lib/launcher") }
    from(runtimeImage.map { it.output.get().dir("runtime") }) { into("runtime") }
    if (pgArtifact != null) {
        from(extractPostgres.flatMap { it.output }) {
            into("pgsql")
            include("bin/**", "lib/**", "share/**")
        }
    }
    if (windowsTarget) {
        from("src/windows")
    } else {
        from("src/unix") {
            filePermissions { unix("rwxr-xr-x") }
        }
    }
    from("src/common/config-templates") { into("config-templates") }
    from(docsDir) {
        into("docs")
        include("*.md")
    }
    from(migrations) { into("migrations") }
    from(generated.map { it.dir("package") }) { include("VERSION", "README.txt") }
    from(rootProject.layout.projectDirectory.file("RELEASE-NOTES.md"))
    from(rootProject.layout.projectDirectory.dir("licenses")) { into("licenses") }
    from(generated.map { it.dir("licenses") }) { into("licenses") }
    dependsOn(writeVersionFiles, licenseNotices)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val verifyStage = tasks.register<VerifyRelease>("verifyStage") {
    description = "Scans the staged package (allowlist, denylist, secrets)."
    dependsOn(stageRelease)
    root = stageDir
    label = "stage $releaseName"
    forbiddenText = listOf(System.getProperty("user.home"), rootProject.projectDir.absolutePath, System.getProperty("user.name") + "@")
    report = layout.buildDirectory.file("verification/$releaseName-stage.txt")
}

val packageFileName = "$releaseName.${if (windowsTarget) "zip" else "tar.gz"}"
val packageFile: Provider<RegularFile> = provider { releasesDir.file(packageFileName) }
val overwriteAllowed = providers.gradleProperty("overwriteRelease").isPresent
val archiveTask: TaskProvider<out Task> = if (windowsTarget) {
    tasks.register<Zip>("releaseArchive") {
        from(stageDir) { into(releaseName) }
        destinationDirectory = releasesDir
        archiveFileName.set(packageFileName)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        val existing = releasesDir.file(packageFileName).asFile
        doFirst {
            if (existing.exists() && !overwriteAllowed) {
                throw GradleException("$existing already exists. Releases are never overwritten silently; delete it or pass -PoverwriteRelease.")
            }
        }
    }
} else {
    tasks.register<UnixTarGz>("releaseArchive") {
        stage = stageDir
        if (pgArtifact != null) postgresJar = downloadPostgres.flatMap { it.output }
        prefix = releaseName
        allowOverwrite = overwriteAllowed
        archive = packageFile
    }
}
archiveTask.configure {
    description = "Packs the verified stage into releases/$releaseVersion/$packageFileName."
    dependsOn(verifyStage)
}

val checksums = tasks.register("releaseChecksums") {
    description = "Writes the SHA-256 checksum next to the archive."
    dependsOn(archiveTask)
    val archive = packageFile
    inputs.file(archive)
    outputs.file(archive.map { File(it.asFile.path + ".sha256") })
    doLast {
        val file = archive.get().asFile
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        File(file.path + ".sha256").writeText("${HexFormat.of().formatHex(md.digest())}  ${file.name}\n")
    }
}

val extractedForVerify = layout.buildDirectory.dir("verify-archive/$releaseName")
val extractArchive: TaskProvider<out Task> = if (windowsTarget) {
    tasks.register<Sync>("extractReleaseArchive") {
        dependsOn(archiveTask)
        from(packageFile.map { zipTree(it) }) {
            eachFile { path = path.removePrefix("$releaseName/") }
            includeEmptyDirs = false
        }
        into(extractedForVerify)
    }
} else {
    tasks.register<ExtractTarGz>("extractReleaseArchive") {
        dependsOn(archiveTask)
        archive = packageFile
        stripPrefix = releaseName
        output = extractedForVerify
    }
}
extractArchive.configure { description = "Unpacks the final archive to verify what users actually download." }

val verifyArchive = tasks.register<VerifyRelease>("verifyArchive") {
    description = "Scans the unpacked final archive (allowlist, denylist, secrets)."
    dependsOn(extractArchive)
    root = extractedForVerify
    label = "archive $packageFileName"
    forbiddenText = listOf(System.getProperty("user.home"), rootProject.projectDir.absolutePath, System.getProperty("user.name") + "@")
    report = layout.buildDirectory.file("verification/$releaseName-archive.txt")
}

val checkUnixArchive = tasks.register<UnixArchiveCheck>("checkUnixArchive") {
    description = "Checks permissions, symlinks, scripts and binary architecture of the Unix package."
    onlyIf { !windowsTarget }
    dependsOn(archiveTask)
    archive = packageFile
    prefix = releaseName
    os = target.substringBefore('-')
    arch = target.substringAfter('-')
    expectPostgres = pgArtifact != null
    report = layout.buildDirectory.file("verification/$releaseName-unix-check.txt")
}

tasks.register("release") {
    group = "distribution"
    description = "Builds, verifies and checksums the JTRAC package for $target."
    dependsOn(checksums, verifyArchive, checkUnixArchive)
    val archive = packageFile
    doLast {
        val file = archive.get().asFile
        logger.lifecycle("JTRAC release: ${file.absolutePath} (${file.length() / (1024 * 1024)} MiB)")
    }
}
