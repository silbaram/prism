package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.service.SdkGuideService
import io.github.silbaram.prism.sdk.AssignmentOutcome
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class SdkGuideServiceTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest(name = "Kotlin = {0}")
    @ValueSource(booleans = [false, true])
    fun `copied guide compiles against real SDK and forwards exact names`(kotlin: Boolean) {
        // Include source-injection syntax, a literal Unicode escape, Kotlin interpolation, and controls.
        val key = " checkout\"; throw new RuntimeException(); //\\u000a\n\r\t\b\u000c한글\${'$'}{error(\"bad\")} "
        val event = "</code>\"\\\$value\u0000\u007f purchase "
        val examples = SdkGuideService().examples(key, event)
        val source = temporary.resolve(if (kotlin) "ExperimentTracking.kt" else "ExperimentTracking.java")
        Files.writeString(source, if (kotlin) examples.kotlin else examples.java)
        val output = Files.createDirectory(temporary.resolve("classes"))
        val classpath = System.getProperty("prism.guide.test.classpath")
        val diagnostics = ByteArrayOutputStream()
        if (kotlin) {
            val exit = K2JVMCompiler().exec(PrintStream(diagnostics), "-no-stdlib", "-no-reflect", "-jvm-target", "21",
                "-classpath", classpath, "-d", output.toString(), source.toString())
            assertEquals(ExitCode.OK, exit, diagnostics.toString())
        } else {
            val exit = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", output.toString(), source.toString())
            assertEquals(0, exit, diagnostics.toString())
        }
        URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
            // Java calls the generated three-argument overload; let it delegate to the stubbed Kotlin method.
            val client = spyk(PrismExperimentClient(mockk()))
            val attributes = mapOf<String, Any>("country" to "KR")
            val outcome = AssignmentOutcome("user", key, "A", true, "0000", "OK")
            every { client.assign("user", key, attributes) } returns outcome
            every { client.trackIfAssigned("user", key, event) } returns true
            val type = loader.loadClass("ExperimentTracking")
            val tracker = type.getConstructor(PrismExperimentClient::class.java).newInstance(client)
            assertEquals(outcome, type.getMethod("expose", String::class.java, Map::class.java).invoke(tracker, "user", attributes))
            assertEquals(true, type.getMethod("recordEvent", String::class.java).invoke(tracker, "user"))
            verify(exactly = 1) { client.assign("user", key, attributes) }
            verify(exactly = 1) { client.trackIfAssigned("user", key, event) }
        }
    }
}
