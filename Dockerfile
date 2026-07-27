# The Linux build-and-test environment: everything needed to compile the native binary and run
# the acceptance suite against it. Building a native image needs a C toolchain, which Windows does
# not have here (native-image wants MSVC's cl.exe), so this image is also how a Windows machine
# builds a Linux binary at all.
#
# What lives where: this file owns the versions of everything the *environment* installs, and
# gradle.properties owns the versions of everything the *build resolves*. Nothing appears in both.
# They move on different cadences through different mechanisms - a version here changes by rebuild,
# new digest, and a pin commit someone reviews.
#
# See docs/building.md for how to use it.

# Pinned by digest, not by tag: a tag moves and a rebuild would then silently change what a commit
# was tested against. 24.04 specifically, because it is what the CI runners use - the image's glibc
# becomes the released binary's minimum supported version, so a newer distro here would raise that
# floor for every user without anything failing in CI.
ARG BASE_IMAGE=ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90
FROM ${BASE_IMAGE}

# The last GraalVM CE for JDK 21. Community builds stopped at 21.0.2, which is also why the Gradle
# plugin's reachability-metadata repository is disabled - see build.gradle.kts.
ARG GRAALVM_VERSION=21.0.2
ARG GRAALVM_SHA256_AMD64=b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48
ARG GRAALVM_SHA256_ARM64=a34be691ce68f0acf4655c7c6c63a9a49ed276a11859d7224fd94fc2f657cd7a

# Runs the acceptance suite. Kept in step with the Groovy the CI legs install until they adopt this
# image, at which point this becomes the only place it is written down.
ARG GROOVY_VERSION=5.0.6
ARG GROOVY_SHA256=14300bca33dc6a911ed5e2c6bc5b83b63fa8a5278e791820ff7e70fc76d06e1d

# Pinned against noble's *release* pocket, which is immutable for the life of 24.04: a newer version
# arriving in noble-updates adds an index entry rather than removing this one, so the pin keeps
# resolving. If universe ever rebuilds the package out from under it, the escape hatch is
# snapshot.ubuntu.com - freezing the whole index at a timestamp, at the cost of freezing security
# patches with it, which is why it is not the default.
ARG MKVTOOLNIX_VERSION=82.0-1build2

# Supplied by BuildKit; re-declared here because an ARG before FROM does not survive into the stage.
ARG TARGETARCH

# Ubuntu images default to the C locale, which gives the JVM an ASCII sun.jnu.encoding - non-ASCII
# file names then stop round-tripping and the Cyrillic acceptance cases fail, eight minutes into a
# run. C.UTF-8 is built into glibc, so no locales package is needed.
ENV LANG=C.UTF-8

# The toolchain is deliberately not version-pinned: the base digest fixes the starting point, and a
# pinned toolchain means the next security patch breaks the image build.
#
# --no-install-recommends everywhere, and the mkvtoolnix pin is asserted afterwards rather than
# trusted: a dropped '=', an epoch appearing, or someone relaxing the pin to fix a build would
# otherwise install a different version silently.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential \
        zlib1g-dev \
        ca-certificates \
        curl \
        unzip \
        "mkvtoolnix=${MKVTOOLNIX_VERSION}"; \
    rm -rf /var/lib/apt/lists/*; \
    gcc --version; \
    test -f /usr/include/zlib.h; \
    mkvmerge --version; \
    mkvpropedit --version; \
    mkvmerge --version | grep -qF "mkvmerge v${MKVTOOLNIX_VERSION%%-*} "

# GraalVM. The tarball is checksum-verified before it is unpacked, so a corrupted or substituted
# download fails here rather than producing a subtly different binary later.
RUN set -eux; \
    case "${TARGETARCH}" in \
        amd64) graal_arch=x64;      graal_sha="${GRAALVM_SHA256_AMD64}" ;; \
        arm64) graal_arch=aarch64;  graal_sha="${GRAALVM_SHA256_ARM64}" ;; \
        *) echo "no GraalVM build mapped for TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-${graal_arch}_bin.tar.gz"; \
    curl -fsSL -o /tmp/graalvm.tar.gz "$url"; \
    echo "${graal_sha}  /tmp/graalvm.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/graalvm; \
    tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1; \
    rm /tmp/graalvm.tar.gz

# Groovy is architecture-independent, so one checksum covers both builds.
RUN set -eux; \
    url="https://archive.apache.org/dist/groovy/${GROOVY_VERSION}/distribution/apache-groovy-binary-${GROOVY_VERSION}.zip"; \
    curl -fsSL -o /tmp/groovy.zip "$url"; \
    echo "${GROOVY_SHA256}  /tmp/groovy.zip" | sha256sum -c -; \
    unzip -q /tmp/groovy.zip -d /tmp/groovy-dist; \
    mv "/tmp/groovy-dist/groovy-${GROOVY_VERSION}" /opt/groovy; \
    chmod +x /opt/groovy/bin/*; \
    rm -rf /tmp/groovy.zip /tmp/groovy-dist

# JAVA_HOME is the load-bearing one: with it set and GRAALVM_HOME unset, nativeCompile works, because
# GraalVM is the only JDK here. GRAALVM_HOME is set anyway because it is the variable the plugin names
# first and what CI's GraalVM action exports. Do not set either to an *empty* value - the plugin
# prefers GRAALVM_HOME whenever it is present and then looks for a relative bin/native-image.
ENV JAVA_HOME=/opt/graalvm \
    GRAALVM_HOME=/opt/graalvm \
    GROOVY_HOME=/opt/groovy \
    GRADLE_USER_HOME=/gradle \
    PATH=/opt/graalvm/bin:/opt/groovy/bin:$PATH

# Grape's cache goes to a fixed path rather than under a home directory, so it stays usable when the
# container runs as some other uid - which is what a CI runner does. Grape appends its own "grapes"
# subdirectory, so the cache itself lands in /grapes/grapes. JAVA_OPTS also reaches the Gradle
# wrapper's launcher JVM, where it is an unused system property; that is harmless, not an oversight.
ENV JAVA_OPTS=-Dgrape.root=/grapes

RUN set -eux; \
    java -version; \
    native-image --version; \
    groovy --version; \
    java -version 2>&1 | grep -qF "${GRAALVM_VERSION}"; \
    groovy --version | grep -qF "Groovy Version: ${GROOVY_VERSION}"

# 24.04 ships a stock user at uid 1000, which is the uid a Linux developer usually has; take it over
# so files written through a bind mount belong to them.
#
# Every path that will be a named volume has to exist here, owned by the right user: Docker seeds a
# fresh volume from the image's content *and ownership* at that path, so a missing mountpoint comes
# out root-owned and the first run dies with a permission error that reads like a Gradle bug.
# /work/src/test/work mirrors the source tree on purpose - it is the acceptance suite's scratch
# directory, and it is a volume because it is the hottest I/O in the loop.
#
# The two shared caches are world-writable so that running as a foreign uid needs no chown dance.
RUN set -eux; \
    userdel -r ubuntu; \
    useradd --create-home --uid 1000 --shell /bin/bash builder; \
    mkdir -p /work/build /work/.gradle /work/.kotlin /work/src/test/work /gradle /grapes; \
    chown -R builder:builder /work /gradle /grapes; \
    chmod 0777 /gradle /grapes

USER builder
WORKDIR /work

# Resolve the harness's @Grab dependencies into the image, so the suite runs offline and does not
# make a round trip per invocation - the smoke subset alone invokes it nine times. Deliberately runs
# the real script rather than naming its coordinates here, which would put them in a second place;
# picocli's help handling returns before the script body, but the @Grabs resolve at compile time
# regardless. That also makes this a compile check of the harness against this image's Groovy.
COPY --chown=builder:builder src/test/run_tests.groovy /tmp/probe/run_tests.groovy
RUN set -eux; \
    groovy /tmp/probe/run_tests.groovy --help > /dev/null; \
    rm -rf /tmp/probe; \
    test -d /grapes/grapes

# Unpack the Gradle distribution the wrapper asks for, so the first real build does not spend a
# 130 MB download on it. Only the distribution - not the project's dependency cache, which would tie
# this image to gradle.properties and invalidate it on every dependency bump. The rule: the image
# holds the environment, the volumes hold the project's caches.
COPY --chown=builder:builder gradlew /tmp/wrapper/gradlew
COPY --chown=builder:builder gradle /tmp/wrapper/gradle
RUN set -eux; \
    cd /tmp/wrapper; \
    sh gradlew --version > /dev/null; \
    rm -rf /tmp/wrapper; \
    test -d /gradle/wrapper/dists

# No ENTRYPOINT: with one, `docker compose run build ./gradlew nativeLoop` would append the command
# to it instead of replacing it.
CMD ["bash"]

# Re-declared for the label below, because an ARG before FROM does not survive into the stage.
# Declared here rather than at the top so that changing it invalidates one layer instead of the
# whole image.
ARG BASE_IMAGE

# So `docker inspect` answers "what is in this image" without anyone reading this file.
LABEL org.opencontainers.image.title="mkvtool build environment" \
      org.opencontainers.image.description="Linux toolchain for building and testing the mkvtool native binary." \
      org.opencontainers.image.source="https://github.com/vdenisov/mkvtool" \
      org.opencontainers.image.licenses="MIT" \
      org.plukh.mkvtool.base-image="${BASE_IMAGE}" \
      org.plukh.mkvtool.graalvm="${GRAALVM_VERSION}" \
      org.plukh.mkvtool.groovy="${GROOVY_VERSION}" \
      org.plukh.mkvtool.mkvtoolnix="${MKVTOOLNIX_VERSION}"
