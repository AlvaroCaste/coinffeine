package coinffeine.headless.commands

import java.io.{PrintWriter, StringWriter}

import coinffeine.common.test.UnitTest
import coinffeine.headless.shell.Command

trait CommandTest extends UnitTest {

  protected def executeCommand(command: Command, arguments: String = ""): String = {
    val outputWriter = new StringWriter()
    command.apply(new PrintWriter(outputWriter), arguments)
    outputWriter.toString
  }
}
