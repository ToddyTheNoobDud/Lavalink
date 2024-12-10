package lavalink.server.bootstrap

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.channels.Channels
import java.util.*
import java.util.jar.JarFile

@SpringBootApplication
class PluginManager(private val config: PluginsConfig) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(PluginManager::class.java)
    }

    private val pluginManifests: MutableList<PluginManifest> = mutableListOf()
    private var classLoader = javaClass.classLoader

    init {
        manageDownloads()
        pluginManifests.apply {
            addAll(readClasspathManifests())
            addAll(loadJars())
        }
    }

    private fun manageDownloads() {
        if (config.plugins.isEmpty()) return

        val directory = File(config.pluginsDir).apply { mkdirs() }
        val pluginJars = directory.listFiles()?.filter { it.extension == "jar" }
            ?.flatMap { file -> JarFile(file).use { loadPluginManifests(it).map { manifest -> PluginJar(manifest, file) } } }
            ?.onEach { log.info("Found plugin '${it.manifest.name}' version ${it.manifest.version}") }
            ?: return

        val declarations = config.plugins.mapNotNull { declaration ->
            declaration.dependency?.let { dep ->
                val fragments = dep.split(":")
                if (fragments.size != 3) throw RuntimeException("Invalid dependency \"$dep\"")
                val repository = declaration.repository ?: config.defaultPluginRepository
                Declaration(fragments[0], fragments[1], fragments[2], "${repository.removeSuffix("/")}/")
            }
        }.distinctBy { "${it.group}:${it.name}" }

        declarations.forEach { declaration ->
            val jars = pluginJars.filter { it.manifest.name == declaration.name }.ifEmpty {
                pluginJars.filter { matchName(it, declaration.name) }
            }

            val hasCurrentVersion = jars.any { it.manifest.version == declaration.version }

            jars.forEach { jar ->
                if (jar.manifest.version != declaration.version) {
                    if (!jar.file.delete()) throw RuntimeException("Failed to delete ${jar.file.path}")
                    log.info("Deleted ${jar.file.path} (new version: ${declaration.version})")
                }
            }

            if (!hasCurrentVersion) {
                downloadJar(File(directory, declaration.canonicalJarName), declaration.url)
            }
        }
    }

    private fun downloadJar(output: File, url: String) {
        log.info("Downloading $url")
        URL(url).openStream().use { inputStream ->
            FileOutputStream(output).use { outputStream ->
                Channels.newChannel(inputStream).use { channel ->
                    outputStream.channel.transferFrom(channel, 0, Long.MAX_VALUE)
                }
            }
        }
    }

    private fun readClasspathManifests(): List<PluginManifest> {
        return PathMatchingResourcePatternResolver()
            .getResources("classpath*:lavalink-plugins/*.properties")
            .mapNotNull { parsePluginManifest(it.inputStream) }
            .onEach { log.info("Found plugin '${it.name}' version ${it.version}") }
    }

    private fun loadJars(): List<PluginManifest> {
        val directory = File(config.pluginsDir)

        if (!directory.isDirectory) return emptyList()

        val jarsToLoad = directory.listFiles()?.filter { it.isFile && it.extension == "jar" }.orEmpty()
        classLoader = URLClassLoader.newInstance(
            jarsToLoad.map { URL("jar:file:${it.absolutePath}!/") }.toTypedArray(),
            javaClass.classLoader
        )

        return jarsToLoad.flatMap { loadJar(it, classLoader) }
    }

    private fun loadJar(file: File, cl: ClassLoader): List<PluginManifest> {
        JarFile(file).use { jar ->
            val manifests = loadPluginManifests(jar)
            if (manifests.isEmpty()) throw RuntimeException("No plugin manifest found in ${file.path}")

            val allowedPaths = manifests.map { it.path.replace(".", "/") }
            var classCount = 0

            jar.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !entry.name.endsWith(".class") || allowedPaths.none(entry.name::startsWith)) return@forEach
                cl.loadClass(entry.name.dropLast(6).replace("/", "."))
                classCount++
            }

            log.info("Loaded ${file.name} ($classCount classes)")
            return manifests
        }
    }

    private fun loadPluginManifests(jar: JarFile): List<PluginManifest> {
        return jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("lavalink-plugins/") && it.name.endsWith(".properties") }
            .mapNotNull { parsePluginManifest(jar.getInputStream(it)) }
            .toList()
    }

    private fun parsePluginManifest(stream: InputStream): PluginManifest? {
        return Properties().apply {
            load(stream)
        }.let { props ->
            val name = props.getProperty("name") ?: return null
            val path = props.getProperty("path") ?: return null
            val version = props.getProperty("version") ?: return null
            PluginManifest(name, path, version)
        }
    }

    private fun matchName(jar: PluginJar, name: String): Boolean {
        val jarName = jar.file.nameWithoutExtension.takeWhile { !it.isDigit() }
            .removeSuffix("-v")
            .removeSuffix("-")
        return name == jarName
    }

    private data class PluginJar(val manifest: PluginManifest, val file: File)

    private data class Declaration(val group: String, val name: String, val version: String, val repository: String) {
        val canonicalJarName = "$name-$version.jar"
        val url = "$repository${group.replace(".", "/")}/$name/$version/$name-$version.jar"
    }
}
