# Reference

The details behind the [README](../README.md): output conventions, how to read
the consistency check report, and the full `config.yaml` reference. This
document is about *using* the tool; implementation internals (the output seam,
the test tiers) live in the repo's `CLAUDE.md`.

## Console output

Every command shares one output convention, and every colour has a single
meaning, the same everywhere:

| Colour | Meaning |
|--------|---------|
| red    | errors and failure summaries |
| green  | success: `*** Done`, clean batch summaries |
| yellow | warnings, and the differing-cell highlight in the check report's tables |
| cyan   | section, per-file and table column headers — for finding your place in long scrollback |
| gray   | de-emphasis: the file-evidence lists in the check report, so the table rows stand out (the `<-` marker keeps the default colour); also a guessed language (`rus?`) and the name of an external file matched only by episode number |

Probing a batch prints a progress meter: a bar that updates in place on a terminal, and appended dots when the output is redirected or piped, so a log stays readable. Colour is applied only when writing to a terminal. Every command
takes `--color auto|always|never` (default `auto`). The
[`NO_COLOR`](https://no-color.org/) environment variable disables
auto-detection; an explicit `--color always` wins over it. `propedit` is the one
exception and has no options of its own — every argument is passed through to
`mkvpropedit` — so it follows auto-detection and `NO_COLOR` only.
`find-unused-fonts` prints bare file names meant for piping and is never
coloured.

In a legacy Windows console window (conhost, not Windows Terminal), colour
prints as raw escape codes such as `←[33m`, because that console does not
interpret them by default. Pass `--color never` or set `NO_COLOR` there.

Errors and warnings go to **stderr**, progress and summaries to **stdout**, so
either can be redirected without losing the other. Every batch command ends with
a one-line summary (`*** 4 converted, 2 skipped, 1 failed`) and exits non-zero
if anything failed. The exception is `mux`, which continues on errors
and always exits 0 — a partially-successful batch mux is a normal outcome —
except under `--strict`, which exits 2 on a blocking discrepancy. `inspect`
writes nothing at all and **exits 0 unless `--strict` is given**, or there is no
mkvmerge to run — none on `PATH`, or a `mkvmergeExe` that cannot be run — which
exits 2. That `mkvmergeExe` is the one config problem that stops it. A config
that is missing, empty, malformed or otherwise unusable is reported as a warning
and the run continues without it — inspection is the thing you reach for *because*
something is wrong, so it does not refuse to describe your files over a config it
was not required to have in the first place. Under `--strict` those same problems
exit 2, since the report was then classified without the selections the config
was supposed to supply. That holds in a directory with no media files too: the
problem belongs to the config, not to the batch.

## Reading the consistency check report

This is what a bare `mkvtool inspect` prints, and the same report `mkvtool mux`
runs as its pre-flight before muxing (`--no-check` skips it there). Under
`inspect` the header reads `*** Consistency check: 5 files`; run from `mux` it
reads `*** Pre-flight check: 5 files`. Everything below applies to both.

The check works in two layers. First it groups files by **track layout** — the
type at each ID. Files that share a layout are the same release and get one
table; files whose layout differs (a different track order, or a track missing)
are a different release and get their own, largest group first. This keeps a
shifted-track-order release from being smeared across the per-ID table, where
the same file would otherwise appear at every shifted ID. Then, within each
group, it compares the **values** at each ID — codec, language, name,
default/forced flags — stacking a row per distinct value and naming the files
that carry it:

```
*** Consistency check: 5 files

*** Layout 1 (3 files - episodes 01-03): video, audio, subs
    ID   TYPE   CODEC                LANG  DEF  FOR  NAME
    0    video  AVC/H.264/MPEG-4p10  und   no   no   Video
    1    audio  AAC                  jpn   yes  no   Audio A
    2    subs   SubRip/SRT           eng   yes  no   Subtitle A

*** Layout 2 (2 files - episodes 04-05): subs, video, audio
    ID   TYPE   CODEC                LANG  DEF  FOR  NAME
    0    subs   SubRip/SRT           eng   yes  no   Subtitle A
    1    video  AVC/H.264/MPEG-4p10  und   no   no   Video
    2    audio  AAC                  jpn   yes  no   Audio A

*** 1 discrepancy affects a track that the config selects:
      2 files use a different track layout, at selected tracks 0, 1, 2
```

How to read it:

- **Structural groups, largest first.** Files with a different track order or a
  missing track form their own group, listed once — not once per shifted ID. The
  `*** Layout N` headers appear only when there is more than one layout: a batch
  that agrees with itself is one table with no header above it.
- **Every group says which files are in it**, the largest included, because the
  groups *are* your muxing passes and an unnamed one is a missing answer. Where the
  names carry an `SxxEyy` that rides in the header as **episode ranges**, as above,
  so the whole plan reads off the `***` lines; a batch whose names yield no number
  gets the file names listed under the header instead, in gray. External files
  appear in the same header as their variant labels (`… video, audio, subs + A B`),
  decoded by the legend `--identify` prints.
- **One row per distinct value, not per file** — a 200-episode batch is as compact
  as a 3-episode one. Every track is listed, so the table doubles as the map you
  check `config.yaml`'s IDs against. The `DEF`/`FOR` columns make a flag-only
  difference legible, and on a terminal the differing cell is highlighted.
- **Within a group, `<-` names the files that deviate.** Where one ID carries more
  than one value, the majority row is printed unnamed and each further row is
  preceded by the files that carry it, one per line, the last marked `<-`:

  ```
      1    audio  AAC                  jpn   yes  no   Audio A
      2    audio  AAC                  eng   no   no   Audio B
             <- Twin Peaks - S01E03 - Zen, or the Skill to Catch a Killer.mkv
      2    audio  AAC                  eng   no   no   Other Studio
  ```

  So episode 3 is the one whose track 2 is named `Other Studio`, and the rest of
  the batch calls it `Audio B`. The lists sit *above* the row they belong to, name
  files rather than episode numbers — a value difference is about one file, not a
  range — and are gray so the table rows stand out. Layout headers never carry the
  marker: `<-` answers "these rows deviate", which is a different question from
  "these files are here".
- **Blocking vs informational.** A difference only corrupts output when it lands on
  a track the config selects by ID *and* not every track of that type is being
  copied. Everything else — unselected tracks, chapters, a type copied wholesale —
  is reported as informational, under a second heading:

  ```
  *** 1 informational (does not affect what gets muxed):
        track 2 (subtitles, config title "Signs") - default differs across 2 groups
  ```

What is compared: per layout, the type at each ID; then per ID, codec, language,
name and default/forced flags, plus chapter presence and genuinely ambiguous
same-language duplicates (two tracks that match on type, language, codec *and*
name, where ID selection cannot tell them apart). What is ignored: the video
track's title, which carries the episode name and differs by design, along with
duration, file size and muxing metadata.

The video title is still *shown*, though, since it is worth seeing even when it is
not worth comparing. A video row stands for every file in its group, so there is
only one title to print when they all agree — and when they do not, the cell reads
`(per file)` rather than presenting one file's title as the group's. Either way it
never produces a finding.

`-` is the one glyph for "nothing here": an unnamed track, an absent language, a
video row whose files carry no title at all.

A config is optional. Without one, nothing is "selected", so the report prints
the structure plus a count of differences and skips the blocking/informational
classification entirely — that is the mode you use to *write* the config.

By default the check **warns and continues** — muxing the wrong tracks is
recoverable, the source files survive. Pass `--strict` to exit 2 when any
discrepancy affects a selected track (from `mux` that also means nothing
is muxed). File lists are truncated to a few names; `--check-verbose` prints them
in full. Highlighting follows `--color` — see [Console output](#console-output).

## External file discovery

External files — dubs, alternative subtitle tracks — often do not sit next to the
main media file. `mkvtool inspect` finds them by name, groups them into
**variants**, and reports what each variant covers. Nothing here feeds muxing:
`mux` is driven by `additionalSources` in the config and nothing else. Discovery is what
you read in order to *write* those entries.

Two layouts are recognised, and they combine:

| Layout | Looks like | The variant is |
|--------|-----------|----------------|
| directory | `Rus sound/[Studio]/<same base name as the main file>.mka` | the directory |
| suffix | `<main base name>.rus.srt` | the trailing text |

The scan is recursive from the current directory and unbounded in depth, since
release layouts nest by category and then by group (`Rus subs/[Studio]/…`).
Directories whose name starts with `.` are skipped, as is `destinationDir` when a
config names one — muxed output carries the same base names as its sources and
would otherwise come back as an external file of itself. **The main-file scan
stays flat**: a media file in a subdirectory is an extra (a menu, a trailer, a
creditless opening), never an episode. Every top-level media file is a main
file, whether or not it has an episode.

### How files are matched

1. **By name** — the file's base name starts with a main file's base name, and
   whatever trails it is the suffix (empty in the directory layout). Separators
   are whatever the release used: `[`, `.`, `(`, `{`, `!`, a bare space. The
   *longest* matching main file wins, so `Show - S01E01 - Title 2.srt` attaches to
   `…Title 2.mkv` rather than being read as `…Title.mkv` plus the suffix `" 2"`.
   A suffix may not continue a number the main file's name ends in: with no
   `Ep 10.mkv`, an `Ep 10.srt` is not `Ep 1.mkv` plus `0`, nor a shorter
   `Ep.mkv` plus `" 10"`. Letters may (`Ep 1a`, `Ep 01v2`). A name two main
   files share (`X.mkv` and `X.mp4`) is ambiguous: a file named after it is
   listed under *External files named after more than one main file*, with both
   of them, and paired with neither. `rename --external` still renames it, since
   both mains take one new base name.
2. **By episode number** — no name relation, but both sides carry the same
   `SxxEyy` and exactly one main file claims it. This is lower confidence by
   construction: it is reported as `(episode match)` with the file's own name, and
   it never drives a rename. Ambiguity (two main files, same episode) disqualifies
   the match.
3. **Unmatched** — everything else with an external-file extension, listed so it
   is not a surprise, never paired with anything.

Extensions treated as external files: `mka mks mp3 aac ac3 dts flac ass ssa srt
sup idx sub vtt`. Anything else in the tree is ignored entirely, so release notes
and screenshots do not become a wall of unmatched entries.

### Variants, and when they merge

A variant is identified by its leaf directory and its suffix. Same-named leaf
directories under different parents **merge into one variant**:
`Rus sound/[Studio]` and `Rus subs/[Studio]` are one dub group with two kinds of
file, and the parents are read as category directories, contributing only
language hints. The merge is undone automatically when it would be ambiguous — if
one episode ends up with two files of the same kind from different directories,
those directories are shown separately, by path.

Each variant gets a short label (`A`, `B`, …) and one legend row per kind of file
it holds:

```
*** External files: 3 variants discovered
  LBL  TYPE       VARIANT       PATTERN                          FILES
  A    audio      [Studio]      Rus sound/[Studio]/<name>.mka    10
  A    subtitles  [Studio]      Rus subs/[Studio]/<name>.ass     4
  B    audio      [Other]       Rus sound/[Other]/<name>.mka     6
```

The label is what the per-file tables use, so a long path is printed once here
rather than under every episode. `<name>` stands for the main file's base name;
where a section holds only episode-number matches the pattern reads
`<episode number>` instead, since those names bear no relation to the main file's.

### What is probed, and what is guessed

Probing an external file costs one `mkvmerge -J` each, which is the expensive
operation on a network share, so it is only done where it can return something.
Established by probing real files of every supported format:

| Extension | Probed | What comes back |
|-----------|--------|-----------------|
| `mka` `mks` `mkv` | yes | language, track name, codec |
| `idx` | yes | language (from the index text) |
| `flac` | yes | language and title, from the Vorbis comments |
| `srt` `vtt` `sup` `ass` `ssa` | no | nothing — these carry no metadata mkvmerge exposes (an ASS *Script Info* title is not surfaced) |
| `mp3` `aac` `ac3` `dts` | no | nothing — an ID3 `TLAN` frame is ignored |
| `sub` | **never** | on its own it identifies as a zero-track MPEG stream; the `.idx` of the pair is what gets probed |

Where a file is not probed, or is probed but carries no language, the language is
**guessed** from the directory path and the suffix and displayed with a trailing
`?` — `rus?` — so a guess is never mistaken for a tag. A probed value always wins
over a guess, field by field: an `.mka` with a language but no track name shows
the real language and an empty name, not a guessed one. Codecs for unprobed files
come from the extension, and the track id is `0`, which is what mkvmerge calls
the single track of a raw subtitle or audio file — which is why an
`additionalSources` track takes no `id`.

`und` counts as *not tagged*, not as a language. Matroska has no other way to
spell "no language set", and an untagged `.mka` is the common case in exactly the
releases this exists for: a folder of Russian dubs where most files report `und`
would otherwise tell you nothing the folder name did not already say.

Languages are recognised by **any of their spellings** — two- and three-letter
codes, the English name, and the language's own name for itself — all matched as
**whole words** in the path and suffix. `Rus sound`, `Ru subs`, `Ru.subs`,
`Russian`, `Русский` and `РУССКИЙ` are all Russian; `Rusubs` is not, because the
whole-word rule is what stops a short code from firing on any release group or
show title that happens to contain the letters. The spellings come from the JDK's
CLDR data, the same source as `${languageName}`/`${languageNative}`, so they stay
correct without a table to maintain.

Only the **citation form** of a native name is known — `русский`, not `русская`
or `русские`. In languages where an adjective agrees with its noun, a folder like
`Русская озвучка` or `Русские субтитры` therefore does not match, and the LANG
column shows `-` rather than a guess. That is deliberate: a missing guess costs
you nothing, while a wrong one in a report you are using to write `config.yaml`
costs you a season. Name the folder with a code (`Rus`, `RUS`) if you want the
guess to fire, or ignore it — nothing depends on it.

The set of languages is curated rather than "everything CLDR knows", because
obscure codes collide with ordinary English words — `new` is Newari, `sun` is
Sundanese, `no` is Norwegian — and `New subs` is not a Newari release. For the
same reason the bare two-letter form of a code that is an ordinary word is not
matched at all: `no` (Norwegian), `it` and `he` and `hi`, `uk` — `UK BluRay` is a
region, not Ukrainian — and `el`, `et` and `da`, which are everyday words in
Spanish, French, Italian and Portuguese. Those languages are still found by their
three-letter code and by their name, in English or their own, so `Ukr sound` and
`Українська` both resolve to `ukr` while `UK BluRay` resolves to nothing.

### External files in the check report

External files are **part of the layout**, not a separate report. The check groups
files by what a muxing pass would need — the track types at each ID *and* the set
of external files attached — so two episodes with identical `.mkv` files but
different dubs available land in different groups, because they are two jobs:

```
*** Layout 1 (6 files - episodes 05-10): video, audio, subs + B C E F
*** Layout 2 (4 files - episodes 01-04): video, audio, subs + A C E F
```

A variant counts once per *kind and format* of file it contributes to an episode,
so a group that ships both an `.ass` and an `.srt` for one episode and only an
`.ass` for the rest splits them into two groups. That is not pedantry: mkvmerge is
being handed a different set of files, which is a different pass. A VobSub
`.idx`/`.sub` pair is one track, so it is one row; an episode with only one half of
it is a group of its own, because mkvmerge cannot mux half a pair.

Every group names its files, the largest included — each group is one config, so
each is an answer. Membership is printed as **episode ranges** where the names
allow it, which is far easier to read than ten file names. The numbering is taken
from an `SxxEyy` token when there is one, and otherwise from the batch itself: the
longest prefix all the file names share, trailing digits trimmed off it, and the
run of digits that follows. That is what turns `[Salender-Raws] Hellsing OVA - 07
(BD 1920x1080 x264 5.1 FLAC).mkv` into `07` without any risk of reading `1080p` or
`x264` as an episode — those sit inside the shared prefix. Names that yield no
number fall back to a plain file list.

This numbering is **display only.** It never renames anything and never resolves
`${episodeNum}`: it is batch-relative by nature — it needs the other files to know
where the number starts — while identity has to be answerable for one file alone.
A wrong guess here is a slightly odd line in a report; a wrong guess in identity
would stamp the wrong title into a file.

That is the answer to the question the report is actually asked: **how many
configs will this season need, and which files go with each.** Two groups, two
passes — fewer if you drop the tracks that distinguish them, which the tables
below each header let you judge.

Inside a group, external files appear as rows alongside the tracks, labelled by
variant (they have no ID — nothing selects them by position) with the variant name
in the NAME column:

```
    ID   TYPE   CODEC                LANG  DEF  FOR  NAME
    0    video  AVC/H.264/MPEG-4p10  jpn   yes  no   -
    1    audio  FLAC                 jpn   yes  no   FLAC
    A    audio  AC-3                 rus   yes  no   [MC-Ent] - Reanimedia
    C    subs   ASS                  rus?  no   no   [Омикрон]
```

Their values are compared exactly like a track's, so a dub tagged Russian for half
a season and untagged for the rest is reported the same way a flag flipping
mid-season is. External differences are **never blocking**: nothing selects an
external file by ID, so they cannot mux the wrong track — they mean another pass,
which is what the grouping already says. (`mux`'s pre-flight is the part
that acts on missing files, and only on the *configured* `additionalSources` of an
episode, which it drops.)

Because the grouping now covers both halves, so does the all-clear: `Track
structure and external files are consistent across 23 files` means one config will
do the whole season.

## to-utf8 safety

Three things make it safe to point at a directory more than once:

- **Files that are already UTF-8 are skipped** — detected by BOM or by decoding
  cleanly as UTF-8. Pure ASCII lands here too, correctly: it is already valid
  UTF-8. Double-converting is precisely how a previously fixed file gets ruined.
- **Decoding is strict.** Java's default decoder *replaces* bytes that are invalid
  in the source charset, so a wrong `--encoding` would otherwise succeed quietly
  and produce mojibake. Instead the file is reported and left alone, and the run
  exits non-zero.
- **UTF-16 input is detected and skipped** — otherwise it would decode to
  mojibake that looks valid.

`.sub` is deliberately excluded: the extension is ambiguous between MicroDVD text
and the binary half of a VobSub `.idx`/`.sub` pair, and rewriting the binary one
would destroy it.

Line endings are preserved — the file is decoded and re-encoded whole rather than
line by line, so CRLF subtitles stay CRLF.

## Configuration

`mux` is driven by a YAML configuration file (`config.yaml` in the media
directory, or `--config <path>`); copy the template
[`config.example.yaml`](config.example.yaml) as a starting point.

### General settings

```yaml
general:
  destinationDir: "mkv"                                # Output directory
  allowedExtensions: ["mkv", "avi", "mp4"]            # File extensions to process
  # mkvmergeExe is optional: omit it to auto-detect mkvmerge from PATH,
  # or set it to a full path to override. One that cannot be run exits 2
  # in both mux and inspect; it is never replaced by auto-detection.
  mkvmergeExe: "C:\\Program Files\\MKVToolNix\\mkvmerge.exe"
  # title is optional: the segment (container) title, defaulting to the file
  # name. Separate from the video track name in mainSource below.
  title: "${showName} - S${seasonNum}E${episodeNum} - ${episodeName}"
  # outputName is optional: the output's base name, templated like title,
  # with ".mkv" appended. Omit it to keep each main file's own name.
  outputName: "${showName} - S${seasonNum}E${episodeNum} - ${episodeName}"
```

`outputName` drops the characters a file name cannot hold (`\ / : * ? " < > |`)
and trailing dots and spaces, on every platform, as `rename` does; titles keep
the original spelling. Two files resolving to one output path stop the batch
before anything is written, whatever names them — an `outputName` that names no
episode, or two main files sharing a base name (`a.mkv` and `a.mp4`), which
would otherwise overwrite one another. Names that differ only in case count as
one, as Windows and macOS see them.

The matcher keys that also live under `general` are in
[Binding files to episodes](#binding-files-to-episodes).

### Main source settings

Defines tracks from the primary source file. The track IDs are the ones
`mkvtool inspect --identify` lists (`mkvmerge -i` reports the same numbers);
track 0 is always the video track.

```yaml
mainSource:
  videoTrack:
    language: "en"                                    # Video track language
    # title will be set to filename by default
    # title: "Original Japanese"                      # Optional: override video track name

  audioTracks:
    - id: 2                                           # Track ID from mkvmerge -i
      language: "en"                                  # Track language
      title: "English"                                # Track title
      default: true                                   # Is default track (omit = false)
    - id: 1
      language: "ru"
      title: "${languageNative} ${codec}"             # -> "Русский DTS"
      default: false

  subtitleTracks:
    - id: 6
      language: "en"
      title: "English"
      charset: "UTF-8"                                # Optional: character set override
      default: true

  # additionalOptions:
  #   - "--compression"
  #   - "0:none"
```

- Omitting `audioTracks` entirely (or setting it to `[]`) copies no audio tracks.
- Omitting `subtitleTracks` entirely (or setting it to `[]`) copies no subtitle tracks.
- `charset` is optional; omit it to let mkvmerge use the subtitle file's detected encoding.
- `default` defaults to `false`/`no` when omitted.
- `general.title` and `videoTrack.title` are separate fields: the first is the
  segment (container) title, the second the video track's own name. Each falls
  back to the file name on its own.

### Substitution variables

Every `title`, and every `additionalSources` file path, is a template. See
[Substitution variables](../README.md#substitution-variables) in the README for
the full tables; in short:

- **File scope**, valid in any of those fields: `fileName`, `extension`,
  `showName`, `seasonNum`, `episodeNum`, `seasonN`, `episodeN` (the two numbers
  unpadded), `episodeRead` (the number as the main file's name spells it, before
  `episodeMap`), `episodeName`, `seasonName`, `showYear`.
  Values come from `episodes.yaml` (or `episodes.txt`) in the media directory,
  falling back to a canonical `Show - SxxEyy - Title` file name.
- **Track scope**, valid only in a track's `title`: `language`, `languageName`
  (`Russian`), `languageNative` (`Русский`), `codec` (`DTS`, `SRT`, `H.264`).

An unknown name, or a track variable used outside a track title, is reported and
exits 2 before anything is probed or muxed. A known variable with no data for a
given episode drops that episode from the batch and muxes the rest; `--strict`
turns that into an abort. There is no escape syntax, so a literal `${...}`
cannot currently be written into a title.

### Track order

Controls the order of tracks in the output. Each entry is `sourceIndex:trackId`,
where source 0 is the main source.

**`trackOrder` is optional.** When omitted, it is derived from the tracks you
configured above, in the order you listed them:

1. the video track (`0:0`),
2. `mainSource.audioTracks`, in listed order,
3. `mainSource.subtitleTracks`, in listed order,
4. one entry per `additionalSources` file (`1:0`, `2:0`, …).

For most configs that is exactly what you want, and `mux` prints the derived value
as it runs. Set `trackOrder` explicitly only to override it:

```yaml
trackOrder: "0:0,0:2,0:1,0:6"
```

An explicit `trackOrder` is checked against the configured tracks, and any
mismatch is reported as a warning — muxing still proceeds. This matters because
mkvmerge itself **silently ignores** entries that match no muxed track, so a
stale ID left behind after editing the track lists would otherwise have no
visible effect at all.

### Additional sources

Include tracks from extra files (audio dubs, external subtitles). Each
additional source must contain exactly one track. Track ID is always 0, so a
track here takes no `id`: a config that sets one, even `id: 0`, is refused by
`mux` and warned about by `inspect`.

The `file` field takes the file-scope substitution variables above, resolved per
episode. `${fileName}` — the base name, no extension, of the current main source
— is the usual one, but a companion kept in a per-studio folder works the same
way: `"Rus sound/[Studio]/${fileName}.mka"`. A track's `title` here also takes
the track-scope variables, describing the companion's own language and codec.

**Wildcards** cover a name a template cannot spell, such as a per-file CRC:
`"Subs/* - ${episodeNum} [*].ass"`. `*` is any run within a folder, `**` any run
across folders, `?` one character; brackets are literal, and a variable's value
always matches as written. A wildcard matches paths relative to the media
directory, with no `./`, `../` or folder starting with a dot (the tree it is
matched against skips those), and must find exactly one file: none drops that
episode like a missing companion, several stop the batch. A file that exists
under the template's exact name is that file, even if the name holds a `*` or
`?`. A VobSub `.idx` and its `.sub` count as one file. One file two sources
pick for the same episode stops the batch too, since it would be muxed twice.

A glob has no notion of where a number ends, and the tool does not second-guess
one: `*E${episodeN}*` matches `S01E10.ass` for episode 1, and `*${episodeNum}*`
matches the season's `S01` as readily as an episode `01`. Write the number the
way the names do: `"Subs/*E${episodeNum}*.ass"`, or
`"Subs/*S??E${episodeNum}*.ass"` to pin the whole token.

A companion numbered like the release rather than like the episode —
`Subs/13.srt` beside `[Grp] Show - 13.mkv`, which `episodeMap` makes episode 1
— is named by `${episodeRead}`: `"Subs/${episodeRead}.srt"`.

`mkvtool inspect --identify` lists each configured source with the path its
pattern resolved to for that episode, which is the quickest way to check one. It also
finds external files that no config mentions — see
[External file discovery](#external-file-discovery), which is how you work out
what to put here in the first place.

```yaml
additionalSources:
  - file: "${fileName}[Dub Studio].mka"               # ${fileName} = base name of main source
    tracks:
      - language: "en"
        title: "English"
        charset: "UTF-8"                              # Optional: charset for subtitle tracks
        default: false
    # additionalOptions:
    #   - "--compression"
    #   - "0:none"
```


### Binding files to episodes

Every `mux` and `inspect` run binds its files to episodes. By default every main
file's episode is the `SxxEyy` in its name, which is what `rename` writes. A few
keys let `mux` work on raw release names as they are, without renaming anything
first. None of them is needed for renamed files.

```yaml
general:
  episodeRegex: " - (\\d+) \\("   # group 1 of the first match is the number
  seasonRegex: "S(\\d+)"          # optional; with it, both must match
  episodeMap:
    13-24: 1-12                   # release numbers to real ones
additionalSources:
  - file: "Subs/${episodeRead}.srt"   # named by the number the main file's name gave
    tracks:
      - language: "en"
        title: "English"
```

**Main files get their episode by one rule per run**: `episodeRegex` when set,
`SxxEyy` otherwise. A regex is a search over the base name (no extension), and
its group 1 is the number. A release with no readable number at all is renamed
by hand: matching files by their position in a list is the one rule that can
pair the wrong files without a sign, so the tool does not offer it.

**A main file the rule cannot read is still a main file** — a creditless
opening (`NCOP.mkv`) beside the episodes, say, or a movie. It is muxed under its
own name, inspected and checked; only a title, `outputName` or companion
template that needs its episode drops it, with the usual warning.

**One season per run.** The season comes from what the files read (`SxxEyy`,
`seasonRegex`) and from `episodes.yaml`; when nothing names one, or two
seasons appear, the batch stops — with no key set too, since binding runs on
every run. A regex without `seasonRegex` needs `episodes.yaml` for that reason.
Main files with no episode name no season, so a directory of them needs none.

**`episodeMap`** turns release numbers into real ones, range to range with equal
lengths: `13-24: 1-12` makes a season-two file numbered 13 episode 1. A number
no range covers stays itself, and with no map at all the number read is the
episode. The release side may start at 0 (a `00` prologue), the real side at 1.
`${episodeRead}` keeps the number as the name spelled it, for companions
numbered like the release. The map lives under `general` only.

**An additional source's `file`** is resolved per episode, wildcards included
(see [Additional sources](#additional-sources)). An episode it finds no file
for is dropped with a warning, and the rest of the batch is muxed.

**Anything else stops the batch with exit 2 before anything is written**, with
every problem listed: two main files on one episode, a season conflict, a
wildcard matching several files, or one file two sources pick for one episode.
Fix the names or the config and run again; nothing is guessed. The checks are
about the files the run selects, so a sample beside its episode can be left out
with `--exclude` instead.

An episode that `episodes.yaml` does not list is not a binding problem: a title
asking for its name drops that file, as it always has.

`mux --dry-run` and `inspect --identify` print what binding decided, once per
batch: each main file's episode (and the number it read, when `episodeMap`
changed it), each source's file beneath it, and `-` for a main file no rule
read:

```
*** Binding: 3 main files, season 01, by episodeRegex
  EP   READ  MAIN
  01   13    [Grp] Show - 13 (BD).mkv
             additionalSources[0]  Subs/13.srt
  02   14    [Grp] Show - 14 (BD).mkv
             additionalSources[0]  Subs/14.srt
  -    -     NCOP.mkv
```

`inspect` binds exactly as `mux` does, but never stops: a problem is a warning
there, and `--identify` lists each episode's resolved file with its tracks, or
says it found none or several. With no config it binds by `SxxEyy`, and over a
config whose matcher keys have a mistake, by `SxxEyy` as well. Under
`inspect --strict`, a binding problem fails the run, since it is exactly what
`mux` would refuse the batch for.

### Groups

A group is the set of episodes one mkvmerge command line can mux, with only the
file names changing from one episode to the next. A season that needs two such
command lines needs two groups; that is the only reason they exist. Typical
cases, not an exhaustive list:

- a dub joins at episode 11: one more external file from there on;
- the dub studio changes at episode 13: the same files, different track titles;
- the release reorders or adds tracks mid-season: different track IDs;
- half the season came from another release: its tracks, or its companions'
  naming, differ.

A group is never a second copy of an episode, such as a BD and a TV version:
one episode has one main file and one output, whatever the groups.

A config without `groups` is one group made of its top level, so a simple season
needs none of this. With `groups`, each group claims a range of episodes and
takes `mainSource`, `additionalSources` and `trackOrder` from the top level
unless it writes its own:

```yaml
general:
  destinationDir: "mkv"                # general stays at the top level
  allowedExtensions: ["mkv"]           # so the .mka dubs are not main files themselves
mainSource:                            # what every group takes unless it writes its own
  videoTrack:
    language: "ja"
  audioTracks:
    - id: 1
      language: "ja"
      title: "${languageNative} ${codec}"
      default: true
groups:
  - name: "raw"                        # optional; output calls it "group 1 (raw)"
    episodes: [1-10]                   # episode ranges, as binding reads them
  - name: "dubbed"
    episodes: [11-24]
    additionalSources:                 # this group's own, in place of the top level's
      - file: "${fileName}[Studio].mka"
        tracks:
          - language: "ru"
            title: "Studio Dub"
```

**A group takes a field it does not write whole**, never merged: a group that
writes `additionalSources` has exactly the sources it lists. A key a group writes
is its own whatever its value, so `additionalSources: []` muxes no external file
(a dub that stops mid-season) and an empty `trackOrder:` is the order derived
from the group's own tracks. A group that writes its own tracks usually wants its
own `trackOrder` too, or an empty one: an inherited order names the top level's
tracks, and `mux` warns about every entry that no longer matches. A top-level
field every group writes for itself is an error, since nothing would read it.

**A group claims a main file** when the file's episode falls in one of its
`episodes` ranges (`7`, `1-6`, or a list of them). The episode is the one binding
decided, after `episodeMap`, which is why the matcher keys live in `general`
rather than in a group — not the episode ranges the check report prints, which
are for display and need no `SxxEyy`. A release without one needs an
`episodeRegex`. Two groups' ranges overlapping is a config error.

**`sharedTracks`** are named track entries, for a track several groups write
for themselves. A track entry with `use: <name>` is that definition with the
entry's own keys laid over it, so an override is just the key you want
different: `default: false` beats the definition's `true`, and an empty `title:`
clears its title. A shared track cannot `use` another.

```yaml
sharedTracks:
  - name: "jpn"
    id: 1
    language: "ja"
groups:
  - episodes: [1-12]
    mainSource:
      audioTracks:
        - use: "jpn"
          default: true                # the entry's own keys win over jpn's
```

**A file no group claims is skipped** with a warning, and the rest of the
season is muxed — a main whose name gives no episode (`NCOP.mkv`) included.

**One pass per group, in config order.** Each pass has its own drops and its
own pre-flight check against the group's selection, and every pass is checked
before any is muxed, so a stop in the second pass leaves nothing written by the
first. Two passes resolving to one output stop the batch as two files of one
pass would. Output names a group by number, and by name when it has one:

```
*** Pass 1 of 2: group 1 (raw), 10 files
*** Pre-flight check: 10 files
...
*** Pass 2 of 2: group 2 (dubbed), 14 files
...
*** Pass 1 of 2: group 1 (raw)
*** Processing Show - S01E01.mkv
```

`inspect` does not report per group yet: over a grouped config it says so,
does not classify findings against the groups' selections, and `--identify`
does not list the groups' sources.

### Example configurations

#### Basic configuration with English audio and subtitles

`trackOrder` is omitted here, so it is derived as `0:0,0:1,0:2`:

```yaml
mainSource:
  videoTrack:
    language: "en"
  audioTracks:
    - id: 1
      language: "en"
      title: "English"
      default: true
  subtitleTracks:
    - id: 2
      language: "en"
      title: "English"
      default: true
```

#### Configuration with multiple audio tracks

Titles here are templates rather than fixed strings, which is the point of having
several of them: each track describes itself, so the same config works for a whole
season without editing.

```yaml
general:
  title: "${showName} - ${episodeName}"   # segment title, e.g. "Twin Peaks - Pilot"
mainSource:
  videoTrack:
    language: "en"
    title: "${episodeName}"               # video track name, distinct from the above
  audioTracks:
    - id: 1
      language: "en"
      title: "${languageName} ${codec}"   # -> "English AC-3"
      default: true
    - id: 2
      language: "ja"
      title: "${languageNative} ${codec}" # -> "日本語 FLAC"
      default: false
    - id: 3
      language: "fr"
      title: "${languageNative}"          # -> "Français"
      default: false
  additionalOptions:
    - "--compression"
    - "0:none"
trackOrder: "0:0,0:1,0:2,0:3"
```

#### Configuration with an external subtitle file

```yaml
mainSource:
  videoTrack:
    language: "en"
  audioTracks:
    - id: 1
      language: "en"
      title: "English"
      default: true
additionalSources:
  - file: "${fileName}.srt"
    tracks:
      - language: "en"
        title: "English"
        charset: "UTF-8"
        default: true
trackOrder: "0:0,0:1,1:0"
```

### Key assumptions

1. The first track in the main source is the video track (track ID 0). `mux`
   hardcodes `0:` for video everywhere, so a release that orders its tracks
   differently needs a group of its own ([Groups](#groups)). `inspect` reports
   exactly that as a layout outlier, which is what the layout grouping in the
   check report is for.
2. Each additional source contains exactly one track (audio or subtitle, never video).
3. Track ID 0 is assumed for all tracks in additional sources.
