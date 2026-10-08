package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Creates `<vault>/llm-wiki` only after the user confirms.
 * Existing files are left untouched, including an existing `schema.md`.
 */
fun scaffoldWiki(vault: Path): Path {
    if (!vault.isDirectory()) error("Vault folder does not exist.")
    val root = vault.resolve("llm-wiki")
    if (root.exists() && !root.isDirectory()) error("llm-wiki exists and is not a folder.")
    Files.createDirectories(root.resolve("raw"))
    Files.createDirectories(root.resolve("wiki").resolve("sources"))
    Files.createDirectories(root.resolve("wiki").resolve("concepts"))
    Files.createDirectories(root.resolve("wiki").resolve("entities"))
    Files.createDirectories(root.resolve("wiki").resolve("queries"))
    writeIfAbsent(root.resolve("wiki").resolve("index.md"), SEED_INDEX)
    writeIfAbsent(root.resolve("wiki").resolve("log.md"), SEED_LOG)
    writeIfAbsent(root.resolve("wiki").resolve("schema.md"), SEED_SCHEMA)
    return root
}

fun plannedScaffoldLines(vault: Path): List<String> {
    val root = vault.resolve("llm-wiki")
    return listOf(
        root.resolve("raw").toString() + "/",
        root.resolve("wiki").resolve("index.md").toString(),
        root.resolve("wiki").resolve("log.md").toString(),
        root.resolve("wiki").resolve("schema.md").toString(),
        root.resolve("wiki").resolve("sources").toString() + "/",
        root.resolve("wiki").resolve("concepts").toString() + "/",
        root.resolve("wiki").resolve("entities").toString() + "/",
        root.resolve("wiki").resolve("queries").toString() + "/",
    )
}

private fun writeIfAbsent(path: Path, text: String) {
    if (path.exists()) return
    atomicWrite(path, text)
}

val SEED_INDEX: String = """
# Index

## Sources

## Concepts

## Entities

## Queries
""".trimStart()

val SEED_LOG: String = """
# Log
""".trimStart()

val SEED_SCHEMA: String = """
# Schema

This folder is an llm-wiki tree inside an Obsidian vault.
Pages are Markdown, YAML frontmatter, and [[wikilinks]] only.

## Directories

- `raw/` is immutable source material. After a file is stored there, do not edit it, move it, or overwrite it.
- `wiki/index.md` is the catalog. Each entry has a wikilink, a one-line summary, a type, and sources.
- `wiki/log.md` is append-only. New entries use `## [YYYY-MM-DD] ingest | title`, `query`, or `lint`.
- `wiki/schema.md` is this file. Do not replace it to match a different tool.
- `wiki/sources/` has one summary page per raw source.
- `wiki/concepts/`
- `wiki/entities/`
- `wiki/queries/` holds optional filed answers.

Do not create Clippings, MOCs, `concept-table.md`, `overview.md`, or `backlinks.md`.

## Frontmatter

Every page under sources, concepts, entities, and queries has:

- title
- type: `source`, `concept`, `entity`, or `query`
- tags
- aliases
- sources
- updated

`created` may also be present.

## Links

Use [[wikilinks]] only. Do not use a Markdown relative link as the only link.

## Conflicts

If a new claim conflicts with an existing page, flag it. Do not silently overwrite existing sentences.
An existing page receives an appended sourced section and a wikilink.
""".trimStart()
