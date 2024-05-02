package dev.rubentxu.pipeline.core.cdi.filters

import dev.rubentxu.pipeline.core.cdi.interfaces.ResourceFilter

class AcceptEverythingResourceFilter<T> : ResourceFilter<T> {
    override fun acceptScannedResource(item: T): Boolean {
        return true
    }
}