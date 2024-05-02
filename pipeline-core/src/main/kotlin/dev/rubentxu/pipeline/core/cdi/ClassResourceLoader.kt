package dev.rubentxu.pipeline.core.cdi

import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceLoader
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.reflect.KClass


class ClassResourceLoader(private val classLoader: ClassLoader, private val includeInnerClasses: Boolean) :
    ResourceLoader<KClass<*>> {

    override fun loadFromJarfile(packageName: String, jarFile: JarFile, entry: JarEntry): Result<KClass<*>> {
        return runCatching {
            loadClassFromFile(packageName, StringUtils.substringAfterLast(entry.name, "/"))
        }
    }

    override fun loadFromFilesystem(packageName: String, directory: File, fileName: String): Result<KClass<*>> {
        return runCatching {
            when {
                (fileName.endsWith(".kts") || fileName.endsWith(".class")) && (includeInnerClasses || fileName.indexOf("\$") < 0) -> {
                    Class.forName("$packageName.${StringUtils.substringBeforeLast(fileName, ".")}", true, classLoader).kotlin
                }
                else -> throw RuntimeException("Unable to load class from file $fileName in package $packageName")
            }
        }
    }

    protected fun loadClassFromFile(packageName: String, fileName: String): KClass<*> {
        return when {
            (fileName.endsWith(".kts") || fileName.endsWith(".class")) && (includeInnerClasses || fileName.indexOf("\$") < 0) -> {
                Class.forName("$packageName.${StringUtils.substringBeforeLast(fileName, ".")}", true, classLoader).kotlin
            }
            else -> throw RuntimeException("Unable to load class from file $fileName in package $packageName")
        }
    }
}