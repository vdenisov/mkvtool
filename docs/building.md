# Building mkvtool from source

This page is for building and testing the tool itself. If you only want to *use* mkvtool, download a
release archive — there is nothing to compile and no runtime to install.

Building the jar needs nothing but a JDK 21 and works everywhere:

```
./gradlew build           # compile, unit tests, the fat jar
./gradlew installDist     # a launcher in build/install/mkvtool/bin/
```

Building the **native binary** is the part that needs an environment. GraalVM's `native-image`
invokes a C toolchain, so it needs one that matches the target platform: `build-essential` and
`zlib1g-dev` on Linux, Visual Studio Build Tools on Windows, the Command Line Tools on macOS. A
Windows machine without MSVC cannot build a native binary at all — which is the usual reason to
reach for the Linux container below even when the host is not Linux.

GraalVM is pinned to **Community Edition 21.0.2** rather than "some GraalVM 21". It is the last
community build for JDK 21, and it is also why the Gradle plugin's reachability-metadata repository
is switched off — the metadata schema wants a newer GraalVM than 21.0.2 ships.

## The container

Everything is described by `Dockerfile` and `compose.yaml` at the repository root, so the only
prerequisite is Docker with a Linux engine.

```
docker build -t mkvtool-buildenv:local .
docker compose run --rm build ./gradlew nativeLoop
```

`nativeLoop` is the whole loop: compile the binary, run its built-in probes, check
`find-unused-fonts`, run the acceptance suite against it, and measure startup against a ceiling.
From cold that is about three minutes, most of it compilation — the full suite of 132 cases takes
around half a minute, because the binary starts in roughly 13 ms where the Groovy scripts it
replaced took seconds apiece. When the binary is already current, the whole thing is well under a
minute.

To narrow it while iterating:

```
docker compose run --rm build ./gradlew acceptanceTest -Pfilter=94_rename    # one case
docker compose run --rm build ./gradlew nativeCheck -Pacceptance=smoke       # one case per command
docker compose run --rm build bash                                           # a shell, to poke around
```

To use the published image rather than a local build, point `MKVTOOL_IMAGE` at it, or edit the
default in `compose.yaml`:

```
docker pull ghcr.io/vdenisov/mkvtool-buildenv@sha256:<digest>
MKVTOOL_IMAGE=ghcr.io/vdenisov/mkvtool-buildenv@sha256:<digest> docker compose run --rm build ./gradlew nativeLoop
```

The image is always referred to **by digest**, never by tag. A tag can be moved, and rebuilding an
unchanged `Dockerfile` produces different bytes regardless, because the distribution hands out
whatever security patches have landed since. Moving the pin is meant to be a commit somebody reviews.

CI runs its Linux native leg in the same image (the pin is spelled a second time in
`.github/workflows/native.yml`, kept in step with `compose.yaml`), so a green run here and a green
Linux native leg there mean the same thing — and the released Linux binary is compiled against the
glibc this image fixes rather than whatever `ubuntu-latest` happens to be.

### What is in the image, and what is not

The image holds the *environment*: the C toolchain, GraalVM, Groovy, a pinned MKVToolNix, the Gradle
distribution the wrapper asks for, and the acceptance harness's `@Grab` dependencies. `docker
inspect` reports the pinned versions as labels, so you never have to read the Dockerfile to find out
what you are running.

The project's own caches live in named volumes instead — `build/`, `.gradle/`, `.kotlin/`, Gradle's
dependency cache, and the acceptance suite's scratch directory. That split is what keeps the image
stable across dependency bumps, and the volumes are also why a Linux build never collides with
whatever the host has in `build/`: inside the container those paths are different storage entirely.
The source tree itself is bind-mounted, so edits on the host are visible immediately.

Two consequences worth knowing:

- `git status` on the host stays clean after a container build. That is the point, but it also means
  the Linux binary is not where the host can see it; it is at `build/native/nativeCompile/mkvtool`
  *inside* the container.
- The acceptance suite's `--keep` no longer leaves anything you can open in a file manager. To
  inspect a failed case, start a shell in the container and look in `src/test/work/<case>`.

`docker compose down -v` throws all of it away and starts clean.

### If something goes wrong

**`image build request failed with exit status 137`** during `nativeCompile` means the container ran
out of memory — `native-image` wants several GB. Raise it in Docker Desktop under Settings →
Resources → Memory; 8 GB is comfortable.

**On Apple Silicon**, build the image for the native architecture. The Dockerfile handles both, and
an emulated `linux/amd64` build turns a ninety-second compile into a coffee break:

```
docker build --platform linux/arm64 -t mkvtool-buildenv:local .
```

Note that this leaves the published pin behind: the image published to GHCR is `linux/amd64` only,
because that is what CI and the release binary need.

## Without Docker

On a plain Linux host, or WSL. The packages, on Debian or Ubuntu:

```
sudo apt-get install build-essential zlib1g-dev mkvtoolnix
```

Then GraalVM Community Edition 21.0.2, from the
[graalvm-ce-builds releases](https://github.com/graalvm/graalvm-ce-builds/releases/tag/jdk-21.0.2):

```
curl -fsSLO https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz
echo "b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48  graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz" | sha256sum -c -
sudo mkdir -p /opt/graalvm && sudo tar -xzf graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz -C /opt/graalvm --strip-components=1
export JAVA_HOME=/opt/graalvm GRAALVM_HOME=/opt/graalvm
export PATH="$JAVA_HOME/bin:$PATH"
```

And Groovy 5, which is what runs the acceptance suite — any 3.0 or newer works; the container pins
5.0.6. Unpack it anywhere and put its `bin` on `PATH`.

One environment variable matters beyond the toolchain:

```
export LANG=C.UTF-8
```

Without a UTF-8 locale the JVM reports an ASCII `sun.jnu.encoding`, non-ASCII file names stop
round-tripping, and the acceptance cases covering Cyrillic titles fail — eight minutes into a run,
for a reason that looks nothing like the cause.

Then the same command as in the container:

```
./gradlew nativeLoop
```

The exact package list, GraalVM version and checksums the container uses are in the `Dockerfile`; if
this section and that file ever disagree, the Dockerfile is the one that gets built and tested.

## The verification tasks

`nativeLoop` is `nativeCompile` followed by `nativeCheck`. Everything under `nativeCheck` inspects a
binary that already exists rather than producing one, which is what lets the same tasks verify a
binary built somewhere else — a downloaded release asset, or a copy signed after the fact:

```
./gradlew nativeCheck -PnativeBinary=/path/to/mkvtool
```

| Task | What it does |
|---|---|
| `nativeSmoke` | Runs the binary's built-in probes: charsets, locales, JSON, YAML, HTTPS. They self-assert, so running them is the check. |
| `unusedFontsCheck` | End-to-end check of `find-unused-fonts`, the one command the acceptance suite has no case for. |
| `acceptanceTest` | The full acceptance suite against the binary. `-Pfilter=<substring>` narrows it to matching cases. |
| `acceptanceSmoke` | One representative case per command — what the per-platform CI legs run. |
| `startupCheck` | First and mean `--version` time, asserted against a ceiling. |
| `nativeCheck` | All of the above. `-Pacceptance=full\|smoke\|none` picks which suite pass, if any. |
| `nativeLoop` | `nativeCompile`, then `nativeCheck`. |

Properties they accept: `-PnativeBinary` (which binary), `-Pfilter` (narrow the suite), `-Pacceptance`
(which pass), `-PgroovyBin` and `-PmkvmergeExe` (tools not on `PATH`), `-PstartupRuns` and
`-PstartupCeilingMs` (the measurement).

None of these is wired into `check` or `build`, on purpose: an ordinary `./gradlew build` has to stay
green on a machine with no GraalVM, no Groovy and no MKVToolNix.

The acceptance suite keys its scratch directories by case name and recreates them as each case
starts, so two runs at once delete each other's fixtures. That is enforced with a lock rather than
left to discipline — a second run refuses to start instead of corrupting the first.
