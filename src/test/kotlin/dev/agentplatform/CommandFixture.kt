package dev.agentplatform

/** Small cross-platform child process used to exercise LocalCommandExecutor. */
object CommandFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "stdin" -> {
                val input = generateSequence { readlnOrNull() }.joinToString("\n")
                print("$input|${System.getenv("AGENT_TEST_VALUE")}")
            }
            "sleep" -> Thread.sleep(args[1].toLong())
            else -> error("Unknown command fixture mode")
        }
    }
}
