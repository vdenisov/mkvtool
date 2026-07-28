package org.plukh.mkvtool.core

import java.io.File
import java.io.IOException

/**
 * External-file discovery: finding the audio and subtitle files that belong to a main media file but do
 * not sit next to it. The language guesser it leans on to name a
 * variant's language lives beside it in `LanguageGuess.kt`.
 *
 * Two layouts are in the wild and both are supported, including combined:
 *
 *  - **directory** — `Rus sound/[Омикрон]/<same base name>.mka`: the variant is the directory, and the
 *    file name matches the main file exactly.
 *  - **suffix** — `<main base name>.rus.srt`: the variant is the trailing text, and the separator is
 *    whatever the release used.
 *
 * `inspect` reports these matches and `rename` renames by them, which is exactly why the rules live in
 * one place: a drifted second copy would rename files that inspection never showed.
 *
 * It **probes nothing** — matching is pure name work, which is what keeps discovery free on a network
 * share. [PROBE_EXTENSIONS] only says which formats would repay a probe if a caller wants one.
 *
 * The engine returns data, never prose: a variant's display name and a section's path pattern are
 * composed by the renderer from the fields here, the same split the episode-membership labels use.
 */

/**
 * What can be muxed in as an external track. Deliberately narrower than "any file": a directory full of
 * release notes, screenshots and NFO files must not turn into a wall of unmatched entries.
 */
val COMPANION_EXTENSIONS = setOf(
    "mka", "mks", "mp3", "aac", "ac3", "dts", "flac",
    "ass", "ssa", "srt", "sup", "idx", "sub", "vtt",
)

/**
 * The extensions worth one `mkvmerge -J` each, established by probing real files of every format in
 * [COMPANION_EXTENSIONS] against mkvmerge v99:
 *
 *  - `mka`/`mks`/`mkv` — language, track name, codec id (Matroska carries it all)
 *  - `idx` — language (`id: ru` in the index text)
 *  - `flac` — language and title from the Vorbis comment block
 *
 * Everything else surfaces nothing a name does not already tell us: `.srt`, `.sup` and `.vtt` have no
 * metadata at all, `.ass`/`.ssa` carry a Script Info title that mkvmerge does not expose, and `.mp3`'s
 * ID3 TLAN frame is ignored. A lone `.sub` is worse than useless — without its `.idx` it identifies as a
 * zero-track "MPEG program stream" — so the `.idx` is always the one probed of the pair.
 */
val PROBE_EXTENSIONS = setOf("mka", "mks", "mkv", "idx", "flac")

/** Displayed for files that are never probed, so a codec column says something useful without a
 *  subprocess per file. An unlisted extension is the caller's problem to fall back on. */
val CODEC_BY_EXTENSION = mapOf(
    "mka" to "Matroska", "mks" to "Matroska", "mp3" to "MP3", "aac" to "AAC", "ac3" to "AC-3",
    "dts" to "DTS", "flac" to "FLAC", "ass" to "ASS", "ssa" to "SSA", "srt" to "SRT",
    "sup" to "PGS", "idx" to "VobSub", "sub" to "VobSub", "vtt" to "WebVTT",
)

/** The companion extensions that carry sound; everything else in [COMPANION_EXTENSIONS] is subtitles. */
private val AUDIO_EXTENSIONS = setOf("mka", "mp3", "aac", "ac3", "dts", "flac")

/**
 * What kind of track a companion file holds.
 *
 * [MIXED] is a **variant-level aggregate only** — the answer for a dub group that supplies audio from
 * one directory and subtitles from another. No entry and no section is ever [MIXED]. Declaration order
 * is load-bearing: it sorts a variant's sections audio-before-subtitles, as v1's string sort did.
 */
enum class CompanionType { AUDIO, MIXED, SUBTITLES }

/** The kind of track [ext] holds. Only [CompanionType.AUDIO] and [CompanionType.SUBTITLES] are reachable. */
fun typeClassOf(ext: String): CompanionType =
    if (ext in AUDIO_EXTENSIONS) CompanionType.AUDIO else CompanionType.SUBTITLES

/** Leading punctuation on a raw suffix — `.rus` reads better as `rus`, `[Studio]` as `Studio`. */
private val LEADING_NON_ALPHANUMERIC = Regex("""^[^\p{L}\p{N}]+""")

/** The trailing half of the same trim. */
private val TRAILING_NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+$""")

/**
 * [suffix] with its leading and trailing punctuation stripped, for display only.
 *
 * Suffixes are matched **verbatim** — a release may separate them with anything, including `[`, `(`,
 * `{`, `!` or a bare space — but reading `[Омикрон]` is easier than reading `.[Омикрон]`. Identity and
 * renaming always use the raw suffix: trimming for identity would fuse `[x]` and `(x)`, and renaming
 * needs the original bytes.
 */
fun trimForDisplay(suffix: String?): String? =
    suffix?.replace(LEADING_NON_ALPHANUMERIC, "")?.replace(TRAILING_NON_ALPHANUMERIC, "")

/**
 * One file found by [walkTree].
 *
 * [dirRel] is `""` for a file at the top level, never null, while [leaf] is null there — the asymmetry is
 * v1's and both halves are relied on: [dirRel] sorts variants, [leaf] is half of a variant's identity.
 * [ext] is lower-cased and carries no dot; [base] is everything before the last dot, so a file named
 * `.mkv` has an empty one (see the guard in [discoverCompanions]).
 */
data class TreeEntry(
    val file: File,
    val relPath: String,
    val dirRel: String,
    val leaf: String?,
    val base: String,
    val ext: String,
)

/**
 * Every file under [root], depth-first, children sorted by name.
 *
 * One recursive walk, reused by every mode. Directories starting with `.` are skipped, as is anything in
 * [excluded] — canonical paths, the output directory above all, since muxed results carry the same base
 * names as their sources and would come back as externals of themselves. Canonical paths are remembered
 * as directories are entered, so a symlink or junction loop on a network share cannot spin forever.
 *
 * Two v1 details reproduced deliberately: a subdirectory recurses **in its sorted position** rather than
 * after all sibling files, because the resulting order decides which entry names a variant; and [root]
 * itself is never tested against [excluded], so excluding the very directory being walked does nothing.
 * A path that cannot be canonicalized, and a directory that cannot be listed, are skipped silently.
 */
fun walkTree(root: File, excluded: Set<String> = emptySet()): List<TreeEntry> {
    val out = ArrayList<TreeEntry>()
    val visited = HashSet<String>()

    fun recurse(dir: File, prefix: String) {
        if (!visited.add(canonicalOrNull(dir) ?: return)) return
        val kids = dir.listFiles() ?: return

        for (child in kids.sortedBy { it.name }) {
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                if ((canonicalOrNull(child) ?: continue) in excluded) continue
                recurse(child, rel)
            } else {
                out += TreeEntry(
                    file = child,
                    relPath = rel,
                    dirRel = prefix,
                    leaf = if (prefix.isEmpty()) null else prefix.substringAfterLast('/'),
                    base = baseNameOf(child.name),
                    ext = extensionOf(child.name),
                )
            }
        }
    }

    recurse(root, "")
    return out
}

/** Which rule placed a companion with its main file. */
enum class MatchTier {
    /** The candidate's base name starts with the main file's — the strongest evidence, and the only tier
     *  that yields a suffix, so it is the only one a rename may act on. */
    NAME,

    /** No name relation, but both sides carry the same SxxEyy and exactly one main file claims it. Lower
     *  confidence by construction: it is reported as such and never drives a rename. */
    EPISODE,
}

/**
 * One companion file bound to its main file.
 *
 * [suffix] is `""` when the base names match exactly (the directory layout), the trailing text when they
 * do not, and null for a [MatchTier.EPISODE] match, which has no name relation to take a suffix from.
 */
data class CompanionEntry(
    val entry: TreeEntry,
    val main: File,
    val tier: MatchTier,
    val suffix: String?,
)

/**
 * The files of one kind within a variant. A merged variant holds audio in one directory and subtitles in
 * another, so one path pattern cannot describe it — everything displayed per kind of file reads these
 * sections rather than the variant as a whole.
 *
 * [dir] is the directory the pattern anchors on (the smallest of [dirs], null when the files sit at the
 * top level) and [suffix] is the first non-null suffix among the files **in discovery order**; together
 * with [extensions] they are what a renderer composes `Rus sound/[GroupA]/<name>.mka` from. A null
 * [suffix] means the section holds nothing but episode-number matches, which have no name to extend.
 *
 * Only what cannot be derived is stored: the aggregates below are computed from [entries] once, so they
 * cannot come to disagree with the files they describe. They are order-independent (distinct and sorted),
 * which is why reading the sorted [entries] gives the same answer as the discovery-order list they were
 * built from.
 */
data class VariantSection(
    val type: CompanionType,
    val suffix: String?,
    val entries: List<CompanionEntry>,
) {
    val dirs: List<String> = directoriesOf(entries)
    val dir: String? = dirs.firstOrNull()
    val extensions: List<String> = extensionsOf(entries)
}

/**
 * A set of companion files that travel together — a dub group, or one release's suffixed siblings.
 *
 * Identity is ([leaf], [suffix]): same-named leaves under different category parents (`Rus sound/[MC-Ent]`
 * and `Rus subs/[MC-Ent]`) are **one** dub group with two kinds of file, which is what makes the report
 * read the way the directory was meant to. [collision] marks the exception — see [discoverCompanions].
 *
 * [first] is the first entry in **discovery** order, which is what v1 builds a variant's display name and
 * its language guess from, while [entries] is sorted by relative path — so it is deliberately not
 * `entries.first()`. The two disagree only when an episode-number match sorts first inside a leaf that
 * adopted a suffix; that is reproduced rather than quietly repaired, since the report is the
 * specification until the port is done.
 *
 * [languageGuess] is what the variant's own words say its language is, or null when they say nothing. It
 * is a *guess* and must be presented as one — the files it describes carry no tag, which is the only
 * reason it exists.
 *
 * As in [VariantSection], only independent state is stored: [dirs], [type] and [extensions] are computed
 * from [entries] once and so cannot disagree with them.
 */
data class Variant(
    val label: String,
    val leaf: String?,
    val suffix: String?,
    val first: CompanionEntry,
    val collision: Boolean,
    val languageGuess: String?,
    val sections: List<VariantSection>,
    val entries: List<CompanionEntry>,
) {
    val dirs: List<String> = directoriesOf(entries)
    val type: CompanionType = typeOf(entries)
    val extensions: List<String> = extensionsOf(entries)
}

/**
 * How a variant identifies itself.
 *
 * Carried instead of the whole [Variant] so that an episode holding one of its files does not drag every
 * other episode's entries along with it. The display name itself is composed by the renderer from these
 * fields — the discovery engine returns ingredients, never prose.
 *
 * The three descriptive fields come from [Variant.first] — the first entry in **discovery** order — not
 * from the variant's own identity, which is what v1 named a variant after. The two
 * disagree in one case: a variant whose path-sorted first entry is not its discovery-first one displays
 * without the suffix its identity carries.
 */
data class VariantIdentity(
    val label: String,
    val leaf: String?,
    val suffix: String?,
    val dirRel: String,
    val collision: Boolean,
) {
    constructor(variant: Variant) : this(
        label = variant.label,
        leaf = variant.first.entry.leaf,
        suffix = variant.first.suffix,
        dirRel = variant.first.entry.dirRel,
        collision = variant.collision,
    )
}

/**
 * Everything one walk found: the [variants], the companion-extension files that belong to no main file,
 * and the main-type files sitting in subdirectories (BD menus, trailers, creditless openings), which are
 * reported so they are not a surprise but are never treated as sources.
 */
data class DiscoveryResult(
    val variants: List<Variant>,
    val unmatched: List<TreeEntry>,
    val extras: List<TreeEntry>,
)

/**
 * Match every candidate in [tree] to one of [mains], then group the matches into variants.
 *
 * Tier 1 is the name relation: the candidate's base name starts with a main file's base name, and
 * whatever trails it is the suffix (empty for the directory layout). The **longest** such main wins,
 * which is what stops `Show - S01E01 - Title 2.srt` from being read as main `…Title` plus suffix `" 2"`
 * when `…Title 2.mkv` also exists.
 *
 * Tier 2 is the episode number: no name relation, but both sides carry the same SxxEyy and exactly one
 * main file claims it. Two main files for one episode make that episode ambiguous by design, and
 * ambiguous means unmatched — never guessed.
 *
 * [mainExtensions] is what counts as a main-type file for the [DiscoveryResult.extras] rule; pass none to
 * report no extras at all.
 */
fun discoverCompanions(
    mains: List<File>,
    tree: List<TreeEntry>,
    mainExtensions: Set<String> = emptySet(),
): DiscoveryResult {
    val mainPaths = mains.mapTo(HashSet()) { it.absolutePath }

    // Longest first, so the first match found below is the longest match.
    val candidates = mains
        .map { MainFile(it, baseNameOf(it.name)) }
        .sortedWith(compareByDescending<MainFile> { it.base.length }.thenBy { it.base })

    // "season/episode" -> the main file that claims it, or null once a second one does.
    val episodeIndex = HashMap<String, MainFile?>()
    for (main in candidates) {
        val parsed = parseSeasonEpisode(main.base) ?: continue
        val key = "${parsed.season}/${parsed.episode}"
        episodeIndex[key] = if (episodeIndex.containsKey(key)) null else main
    }

    val matched = ArrayList<CompanionEntry>()
    val unmatched = ArrayList<TreeEntry>()
    val extras = ArrayList<TreeEntry>()

    for (entry in tree) {
        if (entry.file.absolutePath in mainPaths) continue

        if (entry.ext !in COMPANION_EXTENSIONS) {
            if (entry.dirRel.isNotEmpty() && entry.ext in mainExtensions) extras += entry
            continue
        }

        val lower = entry.base.lowercase()
        // The empty base name guard is not hypothetical pedantry: a file called ".mkv" has one, and every
        // string starts with the empty string, so without it that file would silently adopt every
        // otherwise-unmatched companion in the tree.
        val byName = candidates.firstOrNull { it.lower.isNotEmpty() && lower.startsWith(it.lower) }
        if (byName != null) {
            matched += CompanionEntry(entry, byName.file, MatchTier.NAME, entry.base.substring(byName.base.length))
            continue
        }

        val parsed = parseSeasonEpisode(entry.base)
        val byEpisode = parsed?.let { episodeIndex["${it.season}/${it.episode}"] }
        if (byEpisode == null) unmatched += entry
        else matched += CompanionEntry(entry, byEpisode.file, MatchTier.EPISODE, null)
    }

    return DiscoveryResult(
        variants = groupIntoVariants(matched),
        unmatched = unmatched.sortedBy { it.relPath },
        extras = extras.sortedBy { it.relPath },
    )
}

/** A main file with its base name, cached in both cases so the tier-1 scan does no work per candidate. */
private class MainFile(val file: File, val base: String) {
    val lower: String = base.lowercase()
}

/** One group of matches before it becomes a [Variant]: identity, members, and whether it was split apart. */
private class MatchGroup(
    val leaf: String?,
    val suffix: String?,
    val entries: List<CompanionEntry>,
    val collision: Boolean,
)

/**
 * Turn flat matches into labelled variants: merge by identity, unmerge where the merge was wrong, order
 * deterministically, then label.
 */
private fun groupIntoVariants(matched: List<CompanionEntry>): List<Variant> {
    // An episode-number match has no suffix to key on, so on its own it would form a twin of the
    // directory's real variant. When the directory has exactly one name-matched suffix, the odd file
    // belongs to it — that is the whole point of the looser tier: one file in the set was named
    // differently from its siblings.
    val suffixesByLeaf = matched.filter { it.tier == MatchTier.NAME }
        .groupBy { it.entry.leaf }
        .mapValues { (_, items) -> items.map { it.suffix }.distinct() }

    val groups = LinkedHashMap<Pair<String?, String?>, MutableList<CompanionEntry>>()
    for (match in matched) {
        val adopted = match.suffix ?: suffixesByLeaf[match.entry.leaf]?.singleOrNull()
        groups.getOrPut(match.entry.leaf to adopted) { ArrayList() } += match
    }

    // ...except when the merge is ambiguous: if one episode ends up with two files of the same kind from
    // different directories, those directories are not the same variant after all. Split them back apart;
    // the renderer then names each by its path rather than by the shared leaf.
    val split = ArrayList<MatchGroup>()
    for ((key, items) in groups) {
        val collides = items
            .groupBy { it.main.absolutePath to typeClassOf(it.entry.ext) }
            .any { (_, sameKind) -> sameKind.map { it.entry.dirRel }.distinct().size > 1 }
        if (collides) {
            items.groupBy { it.entry.dirRel }.forEach { (_, subset) ->
                split += MatchGroup(key.first, key.second, subset, collision = true)
            }
        } else {
            split += MatchGroup(key.first, key.second, items, collision = false)
        }
    }

    // Deterministic order, then short labels. Directory variants sort before bare-suffix ones simply
    // because "" sorts first among relative paths. The keys come from the first entry in discovery order,
    // before any per-variant sorting rearranges the members.
    val ordered = split.sortedWith(
        compareBy<MatchGroup> { it.entries[0].entry.dirRel }
            .thenBy { it.entries[0].suffix ?: "" }
            .thenBy { it.entries[0].entry.relPath }
    )

    return ordered.mapIndexed { index, group ->
        val first = group.entries[0]
        Variant(
            label = labelFor(index),
            leaf = group.leaf,
            suffix = group.suffix,
            first = first,
            collision = group.collision,
            languageGuess = guessLanguage(languageTokensOf(first)),
            sections = sectionsOf(group.entries),
            entries = group.entries.sortedBy { it.entry.relPath },
        )
    }
}

/** One section per kind of file, audio first. The pattern's suffix is the first one found in **discovery**
 *  order, not the first alphabetically, so it is read before [VariantSection.entries] is sorted — that
 *  being the order the files are listed in. */
private fun sectionsOf(entries: List<CompanionEntry>): List<VariantSection> =
    entries.groupBy { typeClassOf(it.entry.ext) }
        .map { (type, items) ->
            VariantSection(
                type = type,
                suffix = items.firstOrNull { it.suffix != null }?.suffix,
                entries = items.sortedBy { it.entry.relPath },
            )
        }
        .sortedBy { it.type }

/** A set of companions is audio, subtitles, or — when it holds both — mixed. */
private fun typeOf(entries: List<CompanionEntry>): CompanionType {
    val types = entries.map { typeClassOf(it.entry.ext) }.distinct().sorted()
    return if (types.size > 1) CompanionType.MIXED else types.firstOrNull() ?: CompanionType.SUBTITLES
}

/** The distinct extensions these files carry, sorted — order-independent, so a caller may pass either
 *  the discovery-order list or the sorted one. */
private fun extensionsOf(entries: List<CompanionEntry>): List<String> =
    entries.map { it.entry.ext }.distinct().sorted()

/**
 * The texts that describe a variant, most specific first: its own suffix, then its directories from the
 * leaf upwards. The order is the precedence — a suffix or the file's own folder describes it better than
 * a category directory three levels up, so `Rus subs/[Омикрон]` answers `[Омикрон]` before `Rus subs`.
 */
private fun languageTokensOf(first: CompanionEntry): List<String?> =
    listOf(first.suffix) +
        if (first.entry.dirRel.isEmpty()) emptyList() else first.entry.dirRel.split("/").reversed()

/** The distinct directories these files sit in, top-level ones excluded (they have no path to show). */
private fun directoriesOf(entries: List<CompanionEntry>): List<String> =
    entries.map { it.entry.dirRel }.filter { it.isNotEmpty() }.distinct().sorted()

/** `A`, `B`, ... `Z`, `AA`, `AB` — short enough to prefix every line without crowding out the file name
 *  it labels. Bijective base 26, so there is no `A`-as-zero gap between `Z` and `AA`. */
private fun labelFor(index: Int): String {
    val label = StringBuilder()
    var n = index
    while (n >= 0) {
        label.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    }
    return label.toString()
}

/** [file]'s canonical path, or null when the filesystem will not give one — a dead junction, a denied
 *  directory. The caller skips the node, which is what keeps a walk over a network share from throwing. */
private fun canonicalOrNull(file: File): String? =
    try {
        file.canonicalPath
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/** Everything before the last dot, or the whole name when it has none. */
private fun baseNameOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot < 0) name else name.substring(0, dot)
}
