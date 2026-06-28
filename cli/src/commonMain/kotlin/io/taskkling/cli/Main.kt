package io.taskkling.cli

import io.taskkling.contract.ExportDto
import io.taskkling.core.Taskkling
import io.taskkling.core.computeExport
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.serialization.json.Json

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * `export` (stub). The full surface (PRD §10) lands across M0–M2; for now this
 * just serializes an empty [ExportDto] so the JSON contract is exercised
 * end-to-end (`:cli` → `:core` → `:contract`).
 */
private class ExportCommand : Subcommand("export", "Print the JSON export (M0 stub: empty export)") {
    override fun execute() {
        println(json.encodeToString(ExportDto.serializer(), computeExport()))
    }
}

/**
 * Entry point for the native `taskkling` binary. M0 skeleton: `--version` and a
 * no-op `export`. Real arg surface and exit codes per PRD §10.
 */
public fun main(args: Array<String>) {
    val parser = ArgParser("taskkling")
    val version by parser.option(
        ArgType.Boolean,
        fullName = "version",
        description = "Print version and exit",
    ).default(false)

    parser.subcommands(ExportCommand())
    parser.parse(args)

    if (version) {
        println("taskkling ${Taskkling.VERSION}")
    }
}
