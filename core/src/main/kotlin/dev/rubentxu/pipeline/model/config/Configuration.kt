package dev.rubentxu.pipeline.model.config
interface Configuration

interface IPipelineConfig: Configuration

interface ConfigurationBuider

interface MapConfigurationBuilder<T: Configuration> : ConfigurationBuider {
    fun build(data: Map<String, Any>): T?
}

interface ListMapConfigurationBuilder <T: Configuration> : ConfigurationBuider {
    fun build(data: List<Map<String, Any>>): T?
}
