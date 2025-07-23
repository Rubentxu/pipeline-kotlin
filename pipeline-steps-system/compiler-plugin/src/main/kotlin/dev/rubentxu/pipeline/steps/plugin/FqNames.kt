package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * FQ names for compiler plugin to avoid circular dependencies.
 * 
 * Using hardcoded strings instead of class references to prevent
 * circular dependency between compiler-plugin and core modules.
 */
object FqNames {
    
    // Annotation classes
    val STEP_ANNOTATION = FqName("dev.rubentxu.pipeline.annotations.Step") 
    val STEP_CATEGORY = FqName("dev.rubentxu.pipeline.annotations.StepCategory")
    val SECURITY_LEVEL = FqName("dev.rubentxu.pipeline.annotations.SecurityLevel")
    
    // Core DSL and context classes  
    val STEPS_BLOCK = ClassId.topLevel(FqName("dev.rubentxu.pipeline.dsl.StepsBlock"))
    val PIPELINE_CONTEXT = FqName("dev.rubentxu.pipeline.context.PipelineContext")
    val LOCAL_PIPELINE_CONTEXT = FqName("dev.rubentxu.pipeline.context.LocalPipelineContext")
    
    // ClassId versions for IR transformations
    val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(STEP_ANNOTATION)
    val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(PIPELINE_CONTEXT)
    val LOCAL_PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(LOCAL_PIPELINE_CONTEXT)
    
    // String representations for logging and debugging
    val STEP_ANNOTATION_STRING = STEP_ANNOTATION.asString()
    val STEPS_BLOCK_STRING = STEPS_BLOCK.asString()
    val PIPELINE_CONTEXT_STRING = PIPELINE_CONTEXT.asString()
    val LOCAL_PIPELINE_CONTEXT_STRING = LOCAL_PIPELINE_CONTEXT.asString()
}