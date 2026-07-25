package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The mode rule and the option set, parsed through the shipping wiring rather than a replica of it.
 */
class InspectCommandTest : FunSpec({

    context("the mode rule") {
        data class Case(val label: String, val identify: Boolean, val check: Boolean, val verbose: Boolean, val expected: Boolean)

        withData(
            nameFn = { it.label },
            // The batch report is the question one usually arrives with, so it is what a bare run answers.
            Case("a bare run checks", false, false, false, true),
            Case("--identify replaces the check", true, false, false, false),
            Case("--check alone checks", false, true, false, true),
            // Naming both is the only way to get both: --identify would otherwise suppress the report.
            Case("--identify --check runs both", true, true, false, true),
            // Verbose is a report modifier, and there is nothing else it could have asked for.
            Case("--check-verbose implies the check", false, false, true, true),
            Case("--identify --check-verbose runs both", true, false, true, true),
            Case("--check --check-verbose checks", false, true, true, true),
            Case("all three run both", true, true, true, true),
        ) { wantCheck(it.identify, it.check, it.verbose) shouldBe it.expected }
    }

    context("options") {
        test("masks are positional and repeatable, and --exclude may be given more than once") {
            val cmd = mkvtoolCommandLine()
            cmd.parseArgs("inspect", "a.mkv", "b*.mkv", "-x", "*.sample.mkv", "--exclude", "c.mkv")
            val inspect = cmd.subcommands["inspect"]!!.getCommand<InspectCommand>()

            inspect.fileMasks shouldContainExactly listOf("a.mkv", "b*.mkv")
            inspect.excludeMasks shouldContainExactly listOf("*.sample.mkv", "c.mkv")
        }

        test("the mode and config flags bind") {
            val cmd = mkvtoolCommandLine()
            cmd.parseArgs("inspect", "--identify", "--check", "--check-verbose", "--strict", "-c", "other.yaml")
            val inspect = cmd.subcommands["inspect"]!!.getCommand<InspectCommand>()

            inspect.identifyOnly shouldBe true
            inspect.checkOnly shouldBe true
            inspect.checkVerbose shouldBe true
            inspect.strict shouldBe true
            inspect.configPath shouldBe "other.yaml"
        }

        test("defaults are a bare check of the current directory") {
            val cmd = mkvtoolCommandLine()
            cmd.parseArgs("inspect")
            val inspect = cmd.subcommands["inspect"]!!.getCommand<InspectCommand>()

            inspect.identifyOnly shouldBe false
            inspect.strict shouldBe false
            inspect.configPath shouldBe null
            inspect.fileMasks.shouldContainExactly(emptyList())
        }
    }
})
