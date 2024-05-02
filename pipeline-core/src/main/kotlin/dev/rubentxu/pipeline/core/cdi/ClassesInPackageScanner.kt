package dev.rubentxu.pipeline.core.cdi

import dev.rubentxu.pipeline.core.cdi.filters.AnnotatedClassResourceFilter
import dev.rubentxu.pipeline.core.cdi.filters.ChainedResourceFilter
import dev.rubentxu.pipeline.core.cdi.filters.ExtendsClassResourceFilter
import dev.rubentxu.pipeline.core.cdi.filters.Mode
import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter
import java.io.IOException
import kotlin.reflect.KClass

class ClassesInPackageScanner : PackageScanner<KClass<*>> {
    constructor() : super(getDefaultClassLoader(), ClassResourceLoader(getDefaultClassLoader(), false))

    constructor(classLoader: ClassLoader, includeInnerClasses: Boolean) : super(
        classLoader,
        ClassResourceLoader(classLoader, includeInnerClasses)
    )

    fun findAnnotatedClasses(packageName: String, annoClass: KClass<out Annotation>): Result<Set<KClass<*>>> {
        return scanWithExtraFilter<Annotation>(packageName, AnnotatedClassResourceFilter(annoClass))
    }

    @Throws(IOException::class)
    fun <T : Any> findImplementers(packageName: String, baseClass: KClass<T>): Result<Set<KClass<out T>>> {
        return scanWithExtraFilter<T>(packageName, ExtendsClassResourceFilter(baseClass, true))
    }

    @Throws(IOException::class)
    fun findDefaultImplementers(packageName: String): Result<Set<KClass<*>>> {
        return scan(packageName)
    }

    protected fun <T : Any> scanWithExtraFilter(
        packageName: String,
        extraFilter: ResourceFilter<KClass<*>>,
    ): Result<Set<KClass<out T>>> {
        val currentFilter = this.resourceFilter

        this.resourceFilter = ChainedResourceFilter(Mode.And, extraFilter, currentFilter)
        val classes: Result<Set<KClass<out T>>> = scan(packageName) as Result<Set<KClass<out T>>>

        this.resourceFilter = currentFilter

        return classes
    }

    companion object {
        fun getDefaultClassLoader(): ClassLoader {
            return PackageScanner::class.java.classLoader
        }
    }
}