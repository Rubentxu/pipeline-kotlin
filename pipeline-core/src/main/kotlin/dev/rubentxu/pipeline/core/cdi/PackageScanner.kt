package dev.rubentxu.pipeline.core.cdi

import dev.rubentxu.pipeline.core.cdi.filters.AcceptEverythingResourceFilter
import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter
import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceLoader
import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceVisitor
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.regex.Pattern

abstract class PackageScanner<T> {
    protected var classLoader: ClassLoader
    protected var resourceLoader: ResourceLoader<T>
    protected var recursive = true
    protected var resourceFilter: ResourceFilter<T> = AcceptEverythingResourceFilter()

    constructor(resourceLoader: ResourceLoader<T>) {
        this.classLoader = getDefaultClassLoader()
        this.resourceLoader = resourceLoader
    }

    constructor(classLoader: ClassLoader, resourceLoader: ResourceLoader<T>) {
        this.classLoader = classLoader
        this.resourceLoader = resourceLoader
    }

    fun setResourceFilter(resourceFilter: ResourceFilter<T>): PackageScanner<T> {
        this.resourceFilter = resourceFilter
        return this
    }

    fun scan(packageName: String): Result<Set<T>> {
        return runCatching {
            val result = mutableSetOf<T>()
            visit(object : ResourceVisitor<T> {
                override fun visit(resource: T) {
                    result.add(resource)
                }
            }, packageName)
            result
        }
    }

    fun visit(visitor: ResourceVisitor<T>, packageName: String): Result<Unit> {
        return runCatching {
            var packageName = packageName
            packageName = packageName.replace('.', '/')
            val packageDirMatcher = Pattern.compile("(" + Pattern.quote(packageName) + "(/.*)?)\$")
            val dirs: Sequence<URL> = classLoader.getResources(packageName).toList().asSequence()
            dirs.forEach { url ->
                val path = URLDecoder.decode(url.path, "UTF-8")
                if (path.contains(".jar!") || path.contains("zip!")) {
                    var jarName = path.substring("file:".length)
                    jarName = jarName.substring(0, jarName.indexOf('!'))
                    JarFile(jarName).use { jarFile ->
                        visitJarFile(jarFile, packageName, visitor)
                    }
                } else {
                    val dir = File(path)
                    val dirMatcher = packageDirMatcher.matcher(path)
                    if (dirMatcher.find()) {
                        visitDirectory(dir, packageDirMatcher, visitor)
                    }
                }
            }
        }
    }

    protected fun visitJarFile(jarFile: JarFile, packageNameForJarPath: String, visitor: ResourceVisitor<T>): Result<Unit> {
        return runCatching {
            val entries: Sequence<JarEntry> = jarFile.entries().toList().asSequence()
            entries.forEach { entry ->
                val entryPackage = StringUtils.substringBeforeLast(entry.name, "/")
                if (packageNameForJarPath == entryPackage || recursive && entryPackage.startsWith(packageNameForJarPath)) {
                    val packageName = entryPackage.replace('/', '.')
                    if (!entry.isDirectory) {
                        resourceLoader.loadFromJarfile(packageName, jarFile, entry).getOrNull()?.let { resource ->
                            if (resourceFilter.acceptScannedResource(resource)) {
                                visitor.visit(resource)
                            }
                        }
                    }
                }
            }
        }
    }

    protected fun visitDirectory(dir: File, packageDirMatcher: Pattern, visitor: ResourceVisitor<T>): Result<Unit> {
        return runCatching {
            Files.walk(dir.toPath(), if (recursive) Int.MAX_VALUE else 1)
                .filter { path -> Files.isRegularFile(path) }
                .forEach { path ->
                    val file = path.toFile()
                    var absolutePath = file.parentFile.absolutePath
                    if (File.separatorChar != '/') {
                        absolutePath = absolutePath.replace(File.separatorChar, '/' as Char)
                    }
                    val dirMatcher = packageDirMatcher.matcher(absolutePath)
                    if (dirMatcher.find()) {
                        val packageNameForDir = dirMatcher.group(1).replace('/', '.')
                        resourceLoader.loadFromFilesystem(packageNameForDir, file.parentFile, file.name).getOrNull()
                            ?.let { resource ->
                                if (resourceFilter.acceptScannedResource(resource)) {
                                    visitor.visit(resource)
                                }
                            }
                    }
                }
        }
    }

    companion object {
        fun getDefaultClassLoader(): ClassLoader {
            return PackageScanner::class.java.classLoader
        }
    }
}