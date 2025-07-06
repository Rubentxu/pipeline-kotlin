package dev.rubentxu.pipeline.cli

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = SimplePipelineCli().subcommands(
    SimpleRunCommand(),
    ValidateCommand(),
    ListCommand(),
    CleanCommand(),
    SimpleVersionCommand()
).main(args)