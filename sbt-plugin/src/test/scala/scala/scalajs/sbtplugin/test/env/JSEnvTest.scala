package scala.scalajs.sbtplugin.test.env

import scala.scalajs.tools.env.JSEnv
import scala.scalajs.tools.io.MemVirtualJSFile

import org.junit.Assert._

abstract class JSEnvTest {

  protected def newJSEnv: JSEnv

  implicit class RunMatcher(codeStr: String) {
    def hasOutput(expectedOut: String) = {

      val console = new StoreJSConsole()
      val logger  = new StoreLogger()
      val code    = new MemVirtualJSFile("testScript.js").withContent(codeStr)

      val res = newJSEnv.runJS(EmptyJSClasspath, code, logger, console)

      val log = logger.getLog

      assertTrue("VM shouldn't fail on snippet. Msg: " + res, res.isEmpty)
      assertTrue("VM shouldn't procude log. Log:\n" +
          log.mkString("\n"), log.isEmpty)
      assertEquals("Output should match", expectedOut, console.getLog)
    }
  }

}
