@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('commons-io:commons-io:2.11.0')
@Grab('org.yaml:snakeyaml:1.30')
@GrabConfig(systemClassLoader=true)
import groovy.transform.Field
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.groovy.PicocliScript2
import org.apache.commons.io.FileUtils
import groovy.json.JsonSlurper

@CommandLine.Command(name='run_tests', mixinStandardHelpOptions=true,
                     description='Run the mkvtool acceptance suite')
@PicocliScript2

@CommandLine.Option(names=['-f', '--filter'], description='Run only tests whose name contains this string')
@Field String filterPattern = null

@CommandLine.Option(names=['-k', '--keep'], description='Preserve work/ directory after run')
@Field boolean keepWork = false

@CommandLine.Option(names=['--mkvmerge-exe'], paramLabel='PATH',
                    description='Path to mkvmerge executable (default: auto-detect from PATH)')
@Field String mkvmergeExeOverride = null

@CommandLine.Option(names=['--app-bin'], paramLabel='PATH',
                    description='The mkvtool binary under test (default: the installDist launcher)')
@Field String appBinOverride = null

// ─── Paths ──────────────────────────────────────────────────────────────────

def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def repoRoot  = scriptDir.parentFile.parentFile          // …/mkv-script
def testMkv   = new File(scriptDir, 'test.mkv')
def workRoot  = new File(scriptDir, 'work')

// Colour helpers, transcribed from the v1 lib this harness used to load in-process. Only red and
// green are ever called from here, so the whole of what is needed is below rather than a copy of a
// 150-line file. (`pluralize` was the third, and its only caller was the batch-label case that
// retired with the library it tested in-process.)
//
// The terminal probe is transcribed too, isTerminal() included: on JDK 21 System.console() is null
// whenever output is redirected, but from JDK 22 it returns a Console for a pipe as well, so the
// null check alone would start colouring redirected output — every CI log and every piped run —
// and a test assertion would begin seeing escapes.
def onTerminal = {
    def console = System.console()
    console != null && (!console.respondsTo('isTerminal') || console.isTerminal())
}
def uiEnabled = onTerminal() && !System.getenv('NO_COLOR')
def paint = { String code, s -> uiEnabled ? "${(char) 27}[${code}m${s}${(char) 27}[0m" : "$s" }
def ui = [
    red  : { s -> paint('31', s) },
    green: { s -> paint('32', s) },
]

def isWindows = System.getProperty('os.name').toLowerCase().contains('win')

// What is under test. Defaults to the installDist launcher; --app-bin / MKVTOOL_APP_BIN points it
// at any other packaging — the native binary and the fat-jar launcher both go through here.
def appBin = {
    def override = appBinOverride ?: System.getenv('MKVTOOL_APP_BIN')
    if (override) return new File(override)
    def launcher = isWindows ? 'build/install/mkvtool/bin/mkvtool.bat'
                             : 'build/install/mkvtool/bin/mkvtool'
    new File(repoRoot, launcher)
}()

// Locate an MKVToolNix executable: PATH first, then the Windows default install location.
// Transcribed from the v1 lib for the same reason as the colour helpers above.
def findMkvTool = { String name ->
    try {
        def probe = [name, '--version'].execute()
        probe.waitFor()
        if (probe.exitValue() == 0) return name
    } catch (ignored) {}
    if (isWindows) {
        def path = "C:\\Program Files\\MKVToolNix\\${name}.exe"
        if (new File(path).exists()) return path
    }
    throw new RuntimeException("'$name' not found on PATH or in default install location. Install MKVToolNix.")
}

def mkvmergeExe = mkvmergeExeOverride ?: findMkvTool('mkvmerge')

assert testMkv.exists() : "test.mkv not found at $testMkv"

// A missing binary is a setup error, not a per-case skip: fail loudly and point at the build step
// instead of silently passing.
assert appBin.exists() : "mkvtool binary not found at $appBin — run './gradlew installDist' first (or pass --app-bin)"

// ─── Helpers (closures so they capture script-scope variables) ───────────────

def failures = []
def passes   = []
def lastMkvOutput = null

/** Run a command list; return [exitCode, stdout+stderr combined].
 *  env: extra environment variables for the child (e.g. NO_COLOR). */
def exec = { cmd, File cwd = null, Map env = [:] ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() })
    pb.redirectErrorStream(true)
    // Ensure JAVA_HOME points at the JVM actually running this process
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    env.each { k, v -> pb.environment().put(k.toString(), v.toString()) }
    if (cwd) pb.directory(cwd)
    def proc = pb.start()
    def out = proc.inputStream.text
    proc.waitFor()
    [proc.exitValue(), out]
}

/** mkvmerge -J on a file; return parsed JSON map.
 *  NOTE: read the "properties" key of the result via .get('properties') — on
 *  Groovy 4+ both map.properties and map['properties'] resolve to the bean
 *  properties of the map object itself, not the JSON key of that name. */
def identify = { File f ->
    def (code, out) = exec([mkvmergeExe, '-J', f.absolutePath])
    assert code == 0 : "mkvmerge -J failed on $f"
    new JsonSlurper().parseText(out)
}

/** Extract a single track from src into destFile.
 *  trackType: 'audio' | 'subtitle'. trackId: integer track id in source. */
def extractTrack = { File src, File destFile, String trackType, int trackId ->
    def flag   = trackType == 'audio' ? '--audio-tracks' : '--subtitle-tracks'
    def noFlag = trackType == 'audio' ? '--no-subtitles' : '--no-audio'
    destFile.parentFile?.mkdirs()
    def (code, out) = exec([
        mkvmergeExe, '--output', destFile.absolutePath,
        '--no-video', noFlag, flag, "$trackId",
        src.absolutePath
    ])
    assert (code == 0 || code == 1) : "extractTrack failed (exit $code):\n$out"
}

/** Build a derivative MKV from test.mkv: choose which tracks survive and
 *  override names/languages/flags, for the consistency-check tests.
 *
 *  opts: audio / subs  -> list of source track IDs to keep, or null to drop the
 *        whole type; names / langs / defaults -> maps keyed by the OUTPUT track
 *        id; noChapters, chaptersFile.
 *
 *  NOTE mkvmerge renumbers the surviving tracks from 0 in source order, so the
 *  output IDs are not the source IDs. Assert on the variant's own identify()
 *  output when the exact id matters. */
def buildVariant = { File dest, Map opts = [:] ->
    def cmd = [mkvmergeExe, '--output', dest.absolutePath]
    if (!opts.containsKey('audio'))   cmd << '--no-audio'
    else if (opts.audio != null)      cmd.addAll(['--audio-tracks', opts.audio.join(',')])
    if (!opts.containsKey('subs'))    cmd << '--no-subtitles'
    else if (opts.subs != null)       cmd.addAll(['--subtitle-tracks', opts.subs.join(',')])
    if (opts.noChapters)              cmd << '--no-chapters'
    opts.names?.each    { id, n -> cmd.addAll(['--track-name',        "$id:$n"]) }
    opts.langs?.each    { id, l -> cmd.addAll(['--language',          "$id:$l"]) }
    opts.defaults?.each { id, v -> cmd.addAll(['--default-track-flag', "$id:${v ? 'yes' : 'no'}"]) }
    if (opts.chaptersFile)            cmd.addAll(['--chapters', opts.chaptersFile.absolutePath])
    cmd << testMkv.absolutePath
    def (code, out) = exec(cmd)
    assert (code == 0 || code == 1) : "buildVariant failed (exit $code):\n$out"
    dest
}

/** Write config.yaml into workDir (mkv.groovy reads it from the CWD). */
def writeConfig = { File workDir, String yaml ->
    new File(workDir, 'config.yaml').text = yaml
}

/** Write episodes.txt into workDir, one title per line.
 *  UTF-8 explicitly, matching what fetch_episodes.groovy writes and what
 *  rename.groovy reads — the harness must not depend on the platform default
 *  any more than the scripts do. */
def writeEpisodes = { File workDir, List<String> titles ->
    new File(workDir, 'episodes.txt').setText(titles.join('\n') + '\n', 'UTF-8')
}

/** Write episodes.yaml into workDir. `episodes` is a map of episode number to
 *  raw (unsanitized) name; the rest of the metadata is optional.
 *  Built with snakeyaml rather than by hand so that the titles under test —
 *  which contain colons and quotes on purpose — are quoted correctly. */
def writeEpisodesYaml = { File workDir, Map opts ->
    def data = new LinkedHashMap()
    // containsKey, not truthiness: an empty show name is a case under test
    data.show = opts.containsKey('show') ? opts.show : 'Stub Show'
    if (opts.year) data.year = opts.year
    data.season = opts.containsKey('season') ? opts.season : 1
    if (opts.seasonName) data.seasonName = opts.seasonName
    if (opts.language) data.language = opts.language
    data.episodes = (opts.episodes ?: [:]).collect { number, name ->
        def entry = new LinkedHashMap()
        entry.episode = number as int
        entry.name = name
        entry
    }
    def dumper = new org.yaml.snakeyaml.DumperOptions()
    dumper.defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
    dumper.allowUnicode = true
    new File(workDir, 'episodes.yaml').setText(new Yaml(dumper).dump(data), 'UTF-8')
}

/** Copy test.mkv into workDir under the given name. */
def stageInput = { File workDir, String name = 'test.mkv' ->
    def dest = new File(workDir, name)
    FileUtils.copyFile(testMkv, dest)
    dest
}

/** Run one mkvtool subcommand in workDir; return [exitCode, output].
 *  The output is also kept for diagnostics if the test fails.
 *
 *  scriptName is a legacy key, not a path: it is the v1 script's file name, mapped to the
 *  subcommand by stripping the suffix and turning underscores into hyphens. The scripts are gone
 *  and every one of these cases is being translated to Kotlin, so the ~60 call sites keep the old
 *  spelling rather than churning a file with a known end date. */
def runScript = { String scriptName, File workDir, List extraArgs = [], Map env = [:] ->
    def subcmd = scriptName.replaceAll(/\.groovy$/, '').replace('_', '-')
    // ProcessBuilder cannot launch a .bat directly, so route it through cmd /c;
    // a native .exe (via --app-bin) is launched directly.
    def prefix = appBin.name.toLowerCase().endsWith('.bat') ? ['cmd', '/c'] : []
    def result = exec(prefix + [appBin.absolutePath, subcmd] + extraArgs, workDir, env)
    lastMkvOutput = result[1]
    result
}

/** Run `mkvtool mux` from workDir; return [exitCode, output]. */
def runMkvGroovy = { File workDir, List extraArgs = [] ->
    runScript('mux.groovy', workDir, extraArgs)
}

/** Run `mkvtool inspect` from workDir; return [exitCode, output]. */
def runInspect = { File workDir, List extraArgs = [] ->
    runScript('inspect.groovy', workDir, extraArgs)
}

/** Find the single output MKV in workDir/<destDir>. */
def findOutput = { File workDir, String destDir = 'mkv' ->
    new File(workDir, destDir).listFiles()?.find { it.name.endsWith('.mkv') }
}

/** Names of the MKVs produced in workDir/mkv, sorted. */
def outputNames = { File workDir ->
    (new File(workDir, 'mkv').listFiles() ?: []).collect { it.name }.sort()
}

def check = { boolean cond, String msg ->
    if (!cond) throw new AssertionError(msg)
}

def checkEquals = { actual, expected, String label ->
    if (actual != expected) throw new AssertionError("$label: expected <$expected> but got <$actual>")
}

/** Run a named test case; handle pass/fail bookkeeping. */
def runTest = { String name, Closure body ->
    if (filterPattern && !name.contains(filterPattern)) return

    def workDir = new File(workRoot, name.replaceAll(/[^a-zA-Z0-9_-]/, '_'))
    if (workDir.exists()) try { FileUtils.deleteDirectory(workDir) } catch (ignored) {}
    workDir.mkdirs()
    lastMkvOutput = null

    try {
        body(workDir)
        passes << name
        println ui.green("[PASS] $name")
    } catch (Throwable t) {
        failures << [name: name, msg: t.message ?: t.toString()]
        println ui.red("[FAIL] $name: ${t.message ?: t}")
        if (lastMkvOutput) {
            println "  --- mkv.groovy output ---"
            lastMkvOutput.eachLine { println "  $it" }
            println "  -------------------------"
        }
    } finally {
        if (!keepWork && !failures.find { it.name == name }) {
            try { FileUtils.deleteDirectory(workDir) } catch (ignored) {}
        }
    }
}

// ─── Config builder ──────────────────────────────────────────────────────────

/** Build a config.yaml string from a map of options. */
def cfg = { Map opts = [:] ->
    def dest   = opts.destinationDir ?: 'mkv'
    def exts   = opts.extensions     ?: ['mkv', 'avi', 'mp4']
    def extStr = exts.collect { "\"$it\"" }.join(', ')

    def sb = new StringBuilder()
    sb << "general:\n"
    sb << "  destinationDir: \"$dest\"\n"
    sb << "  allowedExtensions: [$extStr]\n"
    sb << "  mkvmergeExe: \"${mkvmergeExe.replace('\\', '\\\\')}\"\n"
    // Segment (container) title. Omitted unless asked for, so the default —
    // the file name — stays the thing every other test exercises.
    if (opts.generalTitle) sb << "  title: \"${opts.generalTitle}\"\n"

    sb << "mainSource:\n"
    sb << "  videoTrack:\n"
    sb << "    language: \"${opts.videoLang ?: 'en'}\"\n"
    if (opts.videoTitle) sb << "    title: \"${opts.videoTitle}\"\n"

    if (opts.containsKey('audioTracks')) {
        if (!opts.audioTracks) {
            sb << "  audioTracks: []\n"
        } else {
            sb << "  audioTracks:\n"
            opts.audioTracks.each { t ->
                sb << "    - id: ${t.id}\n"
                sb << "      language: \"${t.language}\"\n"
                sb << "      title: \"${t.title}\"\n"
                sb << "      default: ${t.default}\n"
            }
        }
    }

    if (opts.containsKey('subtitleTracks')) {
        if (!opts.subtitleTracks) {
            sb << "  subtitleTracks: []\n"
        } else {
            sb << "  subtitleTracks:\n"
            opts.subtitleTracks.each { t ->
                sb << "    - id: ${t.id}\n"
                sb << "      language: \"${t.language}\"\n"
                sb << "      title: \"${t.title}\"\n"
                if (t.charset) sb << "      charset: \"${t.charset}\"\n"
                sb << "      default: ${t.default}\n"
            }
        }
    }

    if (opts.mainAdditionalOptions) {
        sb << "  additionalOptions:\n"
        opts.mainAdditionalOptions.each { sb << "    - \"$it\"\n" }
    }

    // Omit the key entirely when not supplied, so tests can exercise derivation
    if (opts.containsKey('trackOrder') && opts.trackOrder != null) {
        sb << "trackOrder: \"${opts.trackOrder}\"\n"
    }

    if (opts.additionalSources) {
        sb << "additionalSources:\n"
        opts.additionalSources.each { src ->
            sb << "  - file: \"${src.file.replace('\\', '\\\\')}\"\n"
            sb << "    tracks:\n"
            src.tracks.each { t ->
                sb << "      - language: \"${t.language}\"\n"
                sb << "        title: \"${t.title}\"\n"
                if (t.charset) sb << "        charset: \"${t.charset}\"\n"
                sb << "        default: ${t.default}\n"
            }
            if (src.additionalOptions) {
                sb << "    additionalOptions:\n"
                src.additionalOptions.each { sb << "      - \"$it\"\n" }
            }
        }
    }

    sb.toString()
}

// ═══════════════════════════════════════════════════════════════════════════
// TEST.MKV layout (track IDs as reported by mkvmerge):
//   0  video   und  H.264
//   1  audio   jpn  AAC    "Audio A"
//   2  audio   eng  AAC    "Audio B"
//   3  audio   rus  AAC    "Audio C"
//   4  sub     eng  SRT    "Subtitle A"
//   5  sub     rus  SRT    "Subtitle B" (forced)
//   6  sub     jpn  SRT    "Subtitle C"
// ═══════════════════════════════════════════════════════════════════════════

// ─── Companion file pre-flight ───────────────────────────────────────────────
// A dub studio releasing 22 of 24 episodes is normal. Without the pre-flight
// that surfaces as an mkvmerge failure partway through a long batch; with it,
// the two bad episodes are named up front and the other 22 still get muxed.

/** Config with one ${fileName} companion source. */
def companionCfg = {
    cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]],
        trackOrder: '0:0,0:1,1:0',
        additionalSources: [[
            file: '${fileName}[Studio].mka',
            tracks: [[language: 'ru', title: 'Studio Dub', default: false]]
        ]])
}

// ─── 46. a missing companion skips that episode and muxes the rest ───────────
runTest('46_missing_companion_skips_that_episode') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    // Only episode 1 has its companion
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('${fileName}[Studio].mka'), 'names the source pattern')
    check(out.contains('Show.S01E02.mkv'), 'names the affected episode')

    checkEquals(outputNames(workDir), ['Show.S01E01.mkv'], 'only the complete episode was muxed')

    // and the one that was muxed really did get its companion track
    def audio = identify(new File(workDir, 'mkv/Show.S01E01.mkv')).tracks.findAll { it.type == 'audio' }
    check(audio.any { it.get('properties').track_name == 'Studio Dub' }, 'companion track muxed in')
}

// ─── 47. every companion present: no message, nothing skipped ────────────────
runTest('47_companions_all_present') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    extractTrack(testMkv, new File(workDir, 'Show.S01E02[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(!out.contains('companion files are missing'), 'no skip message when nothing is missing')
    checkEquals(outputNames(workDir), ['Show.S01E01.mkv', 'Show.S01E02.mkv'], 'both muxed')
}

// ─── 48. the pre-flight reports under --dry-run too ──────────────────────────
runTest('48_missing_companion_reported_in_dry_run') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('Show.S01E01.mkv'),  'still previews the complete episode')
    // The blocked episode must not get a command line printed for it
    check(!out.contains('Show.S01E02.mkv.mkv'), 'no output path previewed for the blocked episode')
    check(!new File(workDir, 'mkv').exists(), 'dry run creates nothing')
}

// ─── 49. every episode blocked: says so rather than printing a bare Done ─────
runTest('49_all_companions_missing') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('Nothing left to mux'), 'says the batch is empty rather than just Done')
    checkEquals(outputNames(workDir), [], 'nothing muxed')
    // The pre-flight bails before the mkdirs, so an empty batch leaves no litter
    check(!new File(workDir, 'mkv').exists(), 'destination directory not created')
}

// ─── Consistency check (--check) ─────────────────────────────────────────────
// Fixtures are synthesised from test.mkv with buildVariant. Keeping all seven
// tracks preserves the source IDs 0-6, so name/flag splits land on a known ID;
// dropping a track produces a genuine absence at that ID.

/** A full 7-track copy of test.mkv with optional per-id overrides. */
def fullCopy = { File dest, Map overrides = [:] ->
    buildVariant(dest, [audio: [1, 2, 3], subs: [4, 5, 6]] + overrides)
}

/** config selecting audio 1,2 and subtitle 6 — the IDs the split tests touch. */
def checkCfg = {
    cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                      [id: 2, language: 'ru', title: 'Russian',  default: false]],
        subtitleTracks: [[id: 6, language: 'ja', title: 'Signs', default: false]])
}

// ─── 70. --strict aborts on a blocking discrepancy ───────────────────────────
runTest('70_check_strict_aborts_on_blocking') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(code, 2, 'exits 2 on a blocking discrepancy under --strict')
    check(out.contains('aborting'), 'says it aborted')
    check(!new File(workDir, 'mkv').exists(), 'nothing muxed, no output dir')
}

// ─── 71. --strict continues when findings are only informational ─────────────
runTest('71_check_strict_continues_on_informational') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(code, 0, 'informational findings do not abort under --strict')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'S01E02.mkv', 'odd.mkv'], 'all three muxed')
}

// ─── 72. --no-check suppresses the report and still muxes ────────────────────
runTest('72_check_no_check_suppresses') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--no-check'])
    checkEquals(code, 0, 'exit code')
    check(!out.contains('Pre-flight'), 'no report printed')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'odd.mkv'], 'both still muxed')
}

// ─── 73. the default run checks first, then muxes ────────────────────────────
runTest('73_check_runs_by_default') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('Pre-flight'), 'the check runs without being asked')
    check(out.indexOf('Pre-flight') < out.indexOf('Processing'), 'check comes before muxing')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'S01E02.mkv'], 'files still muxed')
}

// ─── 79. a muxing run with no config.yaml is a clean error, not a stack trace ─
// The script no longer falls back to the example config next to itself, so a
// media directory with no config gets told what to do rather than silently
// muxing with unrelated selections. (inspect needs no config.)
runTest('79_missing_config_clean_error') { workDir ->
    stageInput(workDir, 'E01.mkv')   // media present, but no config.yaml
    def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(code, 2, 'exits 2 when a muxing run finds no config')
    check(out.contains('No config.yaml'), 'explains the problem')
    check(out.contains('config.example.yaml'), 'points at the shipped template')
    check(!out.contains('Exception'), 'no stack trace')
}

// ═══════════════════════════════════════════════════════════════════════════
// v1.4.0 — substitution variables
// ═══════════════════════════════════════════════════════════════════════════

// ─── 96. segment title is templated, independently of the video track name ───
runTest('96_general_title_substitution') { workDir ->
    stageInput(workDir, 'My Show - S01E03 - Ignored.mkv')
    writeEpisodesYaml(workDir, [show: 'My Show', season: 1, seasonName: 'Season One',
                                episodes: [3: 'Real: Title?']])
    writeConfig(workDir, cfg(
        generalTitle: '${showName} - S${seasonNum}E${episodeNum} - ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)

    def info = identify(findOutput(workDir))
    checkEquals(info.container.get('properties').title, 'My Show - S01E03 - Real: Title?',
                'segment title uses the raw episode name')
    // The video track name is a separate field and keeps its own default
    def video = info.tracks.find { it.type == 'video' }
    checkEquals(video.get('properties').track_name, 'My Show - S01E03 - Ignored',
                'video track name still defaults to the file name')
}

// ─── 97. video track title is templated, and the segment title is not ────────
runTest('97_video_title_substitution') { workDir ->
    stageInput(workDir, 'My Show - S01E04 - From Name.mkv')
    writeConfig(workDir, cfg(
        videoTitle: '${episodeName} [${languageName}]',
        videoLang: 'ja',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)

    def info = identify(findOutput(workDir))
    def video = info.tracks.find { it.type == 'video' }
    // No episodes.yaml here: episodeName falls back to the canonical file name
    checkEquals(video.get('properties').track_name, 'From Name [Japanese]',
                'video track name substituted from the file name and language')
    checkEquals(info.container.get('properties').title, 'My Show - S01E04 - From Name',
                'segment title still defaults to the file name')
}

// ─── 98. language name variables, in English and natively ────────────────────
runTest('98_language_name_variables') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id:2, language:'en',  title:'${languageName}',   default:true],
            [id:3, language:'ru',  title:'${languageNative}', default:false]
        ],
        subtitleTracks: [[id:6, language:'jpn', title:'${languageName}', default:false]],
        trackOrder: '0:0,0:2,0:3,0:6'
    ))
    runMkvGroovy(workDir)

    def tracks = identify(findOutput(workDir)).tracks
    def audio = tracks.findAll { it.type == 'audio' }
    checkEquals(audio[0].get('properties').track_name, 'English', 'English display name')
    // Capitalized in Russian's own rules: the JDK returns "русский"
    checkEquals(audio[1].get('properties').track_name, 'Русский', 'native name, capitalized')
    // A three-letter code resolves the same as a two-letter one
    checkEquals(tracks.find { it.type == 'subtitles' }.get('properties').track_name, 'Japanese',
                'ISO 639-2 code resolves')
}

// ─── 99. ${codec} comes from the probe, and works without the check ──────────
runTest('99_codec_variable') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'${languageName} ${codec}', default:true]],
        subtitleTracks: [[id:4, language:'en', title:'${languageName} ${codec}', default:true]],
        trackOrder: '0:0,0:2,0:4'
    ))
    // --no-check so nothing has pre-populated the probe cache: this exercises
    // the probe-on-demand path rather than the pre-flight's leftovers.
    runMkvGroovy(workDir, ['--no-check'])

    def tracks = identify(findOutput(workDir)).tracks
    checkEquals(tracks.find { it.type == 'audio' }.get('properties').track_name, 'English AAC',
                'audio codec name')
    checkEquals(tracks.find { it.type == 'subtitles' }.get('properties').track_name, 'English SRT',
                'subtitle codec name')
}

// ─── 100. an unknown variable is fatal before anything is muxed ──────────────
// The whole point of failing fast: a typo would otherwise be stamped into the
// track names of an entire season, one file at a time.
runTest('100_unknown_variable_fails_fast') { workDir ->
    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'${languagName}', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('${languagName}'), 'quotes the offending token')
    check(out.contains('mainSource.audioTracks[0].title'), 'names the config path')
    check(out.contains('languageName'), 'lists what would have been valid')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ─── 101. a track variable in a file-scope field is equally fatal ────────────
runTest('101_scope_violation_fails_fast') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        generalTitle: '${languageName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('general.title'), 'names the field')
    check(!new File(workDir, 'mkv').exists(), 'nothing was muxed')
}

// ─── 102. an unresolvable language code is caught up front too ───────────────
runTest('102_bad_language_fails_fast') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'xx', title:'${languageName}', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('no language name'), 'says the code has no display name')
    check(out.contains('xx'), 'quotes the code')
}

// ─── 103. missing episode data drops just that episode ──────────────────────
// Data-shaped and per-file, unlike a typo: the rest of the batch still muxes.
runTest('103_missing_episode_data_drops_file') { workDir ->
    stageInput(workDir, 'My Show - S01E01 - One.mkv')
    stageInput(workDir, 'NoEpisodeNumberHere.mkv')
    writeEpisodesYaml(workDir, [show: 'My Show', episodes: [1: 'One']])
    writeConfig(workDir, cfg(
        generalTitle: '${showName} - S${seasonNum}E${episodeNum} - ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('NoEpisodeNumberHere.mkv'), 'names the dropped file')
    check(out.contains('${episodeNum}'), 'names the variable that had no value')

    def outputs = new File(workDir, 'mkv').listFiles().collect { it.name }
    checkEquals(outputs, ['My Show - S01E01 - One.mkv'], 'only the resolvable episode was muxed')

    // --strict turns the same situation into an abort
    FileUtils.deleteDirectory(new File(workDir, 'mkv'))
    def (strictCode, strictOut) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(strictCode, 2, 'strict exits 2')
    check(!new File(workDir, 'mkv').exists(), 'strict muxed nothing')
}

// ─── 104. episodes.txt is still a usable source for ${episodeName} ──────────
runTest('104_episodes_txt_fallback_in_mux') { workDir ->
    stageInput(workDir, 'Show.s01e02.mkv')
    // Hand-written, raw: the colon is exactly what a file name cannot hold and
    // a title can
    writeEpisodes(workDir, ['First One', 'Second: With Colon?'])
    writeConfig(workDir, cfg(
        generalTitle: '${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).container.get('properties').title, 'Second: With Colon?',
                'episode name taken from the right line of episodes.txt')
}

// ─── 105. companion paths and titles are templated ──────────────────────────
runTest('105_companion_substitution') { workDir ->
    def input = stageInput(workDir, 'My Show - S01E01 - Pilot.mkv')
    extractTrack(input, new File(workDir, 'My Show - S01E01 - Pilot.rus.mka'), 'audio', 3)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        additionalSources: [[file: '${fileName}.rus.mka',
                             tracks: [[language:'ru', title:'${languageNative} ${codec}', default:false]]]],
        trackOrder: '0:0,0:2,1:0'
    ))
    runMkvGroovy(workDir)

    def audio = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(audio.size(), 2, 'companion track was muxed in')
    checkEquals(audio[1].get('properties').track_name, 'Русский AAC',
                'companion title substituted from its own language and codec')
}

// ─── 107. --dry-run resolves; --check keeps the raw template ────────────────
runTest('107_dry_run_and_check_rendering') { workDir ->
    stageInput(workDir, 'My Show - S01E01 - Pilot.mkv')
    writeConfig(workDir, cfg(
        generalTitle: '${showName}: ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'${languageName}', default:true]],
        trackOrder: '0:0,0:2'
    ))

    def (dryCode, dryOut) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(dryCode, 0, "dry-run exit code (output was:\n$dryOut\n)")
    check(dryOut.contains('My Show: Pilot'), 'dry run shows the resolved title')
    check(!dryOut.contains('${showName}'), 'dry run leaves no unresolved token')
    check(!new File(workDir, 'mkv').exists(), 'dry run muxed nothing')

    // The check report identifies which config entry a finding refers to, and
    // the literal template is what does that — the check runs over the whole
    // batch and so cannot resolve a per-file variable at all.
    //
    // Keeping every track preserves the source ids, so the name override below
    // lands on id 2 — the audio track the config selects, which is what makes
    // the difference blocking and gets the config title printed.
    buildVariant(new File(workDir, 'My Show - S01E02 - Second.mkv'),
                 [audio: [1, 2, 3], subs: [4, 5, 6], names: [2: 'Different']])
    def (checkCode, checkOut) = runInspect(workDir, ['--check'])
    checkEquals(checkCode, 0, 'check exit code')
    check(checkOut.contains('${languageName}'), "check prints the raw template; got:\n$checkOut")
}

// ─── 109. mux.groovy rejects the inspection flags it no longer owns ──────────
// They moved to mkv-inspect outright rather than living on as aliases: two entry
// points for one report is surface to keep in sync, and picocli already rejects
// an unknown option clearly.
runTest('109_mux_rejects_inspection_flags') { workDir ->
    stageInput(workDir, 'a.mkv')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'jpn', title: 'JP', default: true]]))

    ['--identify', '--check', '--check-verbose'].each { flag ->
        def (code, out) = runMkvGroovy(workDir, [flag])
        check(out.contains("Unknown option: '${flag}'"), "mux names ${flag} as not its own; got:\n${out}")
        check(!out.contains('Processing'), "mux muxed nothing for ${flag}")
        check(!new File(workDir, 'mkv').exists(), "mux created no output directory for ${flag}")
    }

    // NOTE: picocli's script runner reports a usage error and returns without
    // setting an exit code, so this — like every unknown option in every picocli
    // script here — still exits 0. Asserted on the message and the absence of
    // output instead, rather than pinning behaviour worth changing separately.
}

// ─── 119. a config that cannot be parsed is a clean error in mux ─────────────
// mux cannot mux against a config it did not understand, so this stays fatal —
// but as the same clean exit 2 a missing config gets, never a stack trace.
runTest('119_mux_rejects_unusable_config') { workDir ->
    stageInput(workDir, 'a.mkv')

    ["general:\n  destinationDir: mkv\n   bad indent: [\n", "", "- just\n- a list\n"].each { text ->
        new File(workDir, 'config.yaml').setText(text, 'UTF-8')
        def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
        checkEquals(code, 2, 'exits 2 on a config it cannot use')
        check(out.contains('*** Error:'), 'says what is wrong')
        check(!out.contains('Exception'), 'no stack trace')
        check(!new File(workDir, 'mkv').exists(), 'muxed nothing')
    }
}

// ─── 119b. mux refuses a broken episodes.yaml, cleanly ───────────────────────
// The mirror of 118b: the same four failures, the opposite policy. A title
// stamped from metadata that could not be read is the same confidently-wrong
// output as a mux against a config that was never understood, so it is exit 2 —
// but an error message, never a stack trace.
runTest('119b_mux_rejects_broken_episodes_yaml') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    ["show: Test\nepisodes:\n  - episode: 1\n   name: [\n",
     "",
     "- just\n- a list\n",
     "show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n"].each { text ->
        new File(workDir, 'episodes.yaml').setText(text, 'UTF-8')

        def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
        checkEquals(code, 2, "exits 2 on episode metadata it cannot use; got:\n${out}")
        check(out.contains('*** Error:'), 'says what is wrong')
        check(out.contains('episodes.yaml'), 'and names the file it is talking about')
        check(!out.contains('Exception'), "no stack trace; got:\n${out}")
        check(!new File(workDir, 'mkv').exists(), 'muxed nothing')
    }
}

// ─── Summary ─────────────────────────────────────────────────────────────────

println()
println '═' * 50
def summary = "${passes.size()} passed, ${failures.size()} failed"
if (failures) {
    println ui.red(summary)
    println()
    println 'FAILURES:'
    failures.each { println ui.red("  [FAIL] ${it.name}: ${it.msg}") }
    System.exit(1)
}
println ui.green(summary)
