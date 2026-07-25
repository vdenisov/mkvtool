package org.plukh.mkvtool.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import picocli.CommandLine

/**
 * `--color` is declared once, in [OutputOptions], and mixed into every command that can afford to parse
 * options. These parse through the real shipping wiring ([mkvtoolCommandLine]), so they pin the mixin as
 * users meet it: the same name and the same default on every command that has it.
 *
 * `propedit` is the exception and is tested as one — it forwards every token to mkvpropedit, so it must
 * not recognize `--color` at all.
 */
class OutputOptionsTest : FunSpec({

    fun optionsOf(name: String, vararg args: String): OutputOptions {
        val cmd = mkvtoolCommandLine()
        cmd.parseArgs(name, *args)
        return when (val sub = cmd.subcommands[name]!!.getCommand<Any>()) {
            is ToUtf8Command -> sub.output
            is FixSrtCommand -> sub.output
            is FindUnusedFontsCommand -> sub.output
            is FilenameToTitleCommand -> sub.output
            is InspectCommand -> sub.output
            is MuxCommand -> sub.output
            else -> error("$name does not mix in OutputOptions")
        }
    }

    context("every command carrying the mixin binds it identically") {
        withData("to-utf8", "fix-srt", "find-unused-fonts", "filename-to-title", "inspect", "mux") { name ->
            optionsOf(name).color shouldBe "auto"
            optionsOf(name, "--color", "never").color shouldBe "never"
            optionsOf(name, "--color", "always").color shouldBe "always"
        }
    }

    test("help still documents the option in full, as it read when each command declared its own") {
        // Ansi.OFF: picocli styles the usage message when it believes the terminal supports it, and the
        // escapes land mid-token.
        val help = mkvtoolCommandLine().subcommands["to-utf8"]!!.getUsageMessage(CommandLine.Help.Ansi.OFF)
        help shouldContain "--color=WHEN"
        help shouldContain "Colorize output: auto (default, only on a terminal"
    }

    test("propedit does not recognize --color: every token belongs to mkvpropedit") {
        val cmd = mkvtoolCommandLine()
        cmd.parseArgs("propedit", "--color", "never")
        val propedit = cmd.subcommands["propedit"]!!.getCommand<PropeditCommand>()
        propedit.passthrough shouldBe listOf("--color", "never")
    }

    test("an unknown option is still a usage error on a command that parses options") {
        shouldThrow<CommandLine.UnmatchedArgumentException> {
            mkvtoolCommandLine().parseArgs("fix-srt", "--colour", "never")
        }
    }
})
