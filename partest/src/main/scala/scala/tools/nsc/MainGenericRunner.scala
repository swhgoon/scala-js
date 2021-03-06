package scala.tools.nsc

/* Super hacky overriding of the MainGenericRunner used by partest */

import scala.scalajs.ir

import scala.scalajs.tools.classpath._
import scala.scalajs.tools.logging._
import scala.scalajs.tools.io._
import scala.scalajs.tools.optimizer.ScalaJSOptimizer
import scala.scalajs.tools.optimizer.ScalaJSClosureOptimizer
import scala.scalajs.tools.env.JSConsole

import scala.scalajs.sbtplugin.env.rhino.RhinoJSEnv
import scala.scalajs.sbtplugin.env.nodejs.NodeJSEnv
import scala.scalajs.sbtplugin.JSUtils._

import java.io.File
import scala.io.Source

import Properties.{ versionString, copyrightString }
import GenericRunnerCommand._

class ScalaConsoleJSConsole extends JSConsole {
  def log(msg: Any) = scala.Console.out.println(msg.toString)
}

class MainGenericRunner {
  def errorFn(ex: Throwable): Boolean = {
    ex.printStackTrace()
    false
  }
  def errorFn(str: String): Boolean = {
    scala.Console.err println str
    false
  }

  val fullOpt = sys.props.contains("scalajs.partest.fullOpt")
  val fastOpt = sys.props.contains("scalajs.partest.fastOpt")

  def noWarnMissing = {
    import ScalaJSOptimizer._

    for {
      fname <- sys.props.get("scala.partest.noWarnFile").toSeq
      line  <- Source.fromFile(fname).getLines
      if !line.startsWith("#")
    } yield line.split('.') match {
      case Array(className) =>             NoWarnClass(className)
      case Array(className, methodName) => NoWarnMethod(className, methodName)
    }
  }

  def process(args: Array[String]): Boolean = {
    val command = new GenericRunnerCommand(args.toList, (x: String) => errorFn(x))
    import command.{ settings, howToRun, thingToRun }

    if (!command.ok) return errorFn("\n" + command.shortUsageMsg)
    else if (settings.version) return errorFn("Scala code runner %s -- %s".format(versionString, copyrightString))
    else if (command.shouldStopWithInfo) return errorFn("shouldStopWithInfo")

    if (howToRun != AsObject)
      return errorFn("Scala.js runner can only run an object")

    // Load basic Scala.js classpath (used for running or further packaging)
    val usefulClasspathEntries = for {
      url <- settings.classpathURLs
      f = urlToFile(url)
      if (f.isDirectory || f.getName.startsWith("scalajs-library"))
    } yield f
    val classpath = ScalaJSClasspath.fromClasspath(usefulClasspathEntries)

    val logger    = new ScalaConsoleLogger(Level.Warn)
    val jsConsole = new ScalaConsoleJSConsole

    val mainObjName = ir.Definitions.encodeClassName(thingToRun)
    val baseRunner  = runnerJSFile(mainObjName, command.arguments)

    def fastOpted = fastOptimize(classpath, mainObjName, logger)
    def fullOpted = fullOptimize(fastOpted, mainObjName, logger, baseRunner)

    val runner = if (fullOpt) fullOptRunner() else baseRunner
    val env    = if (fullOpt || fastOpt) new NodeJSEnv else new RhinoJSEnv
    val runClasspath = {
      if (fullOpt)      ScalaJSPackedClasspath(Seq(fullOpted), Nil)
      else if (fastOpt) ScalaJSPackedClasspath(Seq(fastOpted), Nil)
      else classpath
    }

    env.runJS(runClasspath, runner, logger, jsConsole)

    true
  }

  private def runnerJSFile(mainObj: String, args: List[String]) = {
    val jsObj = "ScalaJS.m." + mainObj
    val jsArgs = argArray(args)
    new MemVirtualJSFile("Generated launcher file").
      withContent(s"$jsObj().main__AT__V($jsArgs);")
  }

  /** constructs a scala.Array[String] with the given elements */
  private def argArray(args: List[String]) = {
    s"""ScalaJS.makeNativeArrayWrapper(
          ScalaJS.d.T.getArrayOf(),
          ${listToJS(args)})"""
  }

  private def fastOptimize(
      classpath: ScalaJSClasspath,
      mainObjName: String,
      logger: Logger) = {
    import ScalaJSOptimizer._

    val optimizer = new ScalaJSOptimizer

    val fileName = "partest fastOpt file"
    val jsFileWriter = new MemVirtualJSFileWriter

    try {
      optimizer.optimize(
          Inputs(classpath,
              manuallyReachable = Seq(
                ReachObject(mainObjName),
                ReachMethod(mainObjName + '$', "main__AT__V", static = false)
              ),
              noWarnMissing = noWarnMissing
          ),
          OutputConfig(
              name          = fileName,
              writer        = jsFileWriter,
              wantSourceMap = false
              ),
          logger
      )
    } finally {
      jsFileWriter.close()
    }

    jsFileWriter.toVirtualFile(fileName)
  }

  private def fullOptimize(
      jsFile: VirtualJSFile,
      mainObjName: String,
      logger: Logger,
      runner: VirtualJSFile) = {
    import ScalaJSClosureOptimizer._

    val optimizer = new ScalaJSClosureOptimizer

    val fileName = "partest fullOpt file"
    val jsFileWriter = new MemVirtualJSFileWriter

    val exportFile = fullOptExportFile(runner)

    try {
      optimizer.optimize(
        Inputs(Seq(jsFile, exportFile)),
        OutputConfig(fileName, jsFileWriter),
        logger
      )
    } finally {
      jsFileWriter.close()
    }

    jsFileWriter.toVirtualFile(fileName)
  }

  /** generates an exporter statement for the google closure compiler that runs
   *  what the normal test would
   */
  private def fullOptExportFile(runnerFile: VirtualJSFile) = {
    new MemVirtualJSFile("partest fullOpt exports").withContent(
      s"""this["runFullOptPartest"] = function() { ${runnerFile.content} };"""
    )
  }

  private def fullOptRunner() = new MemVirtualJSFile("partest fullOpt runner").
    withContent("runFullOptPartest();")

  private def urlToFile(url: java.net.URL) = {
    try {
      new File(url.toURI())
    } catch {
      case e: java.net.URISyntaxException => new File(url.getPath())
    }
  }
}

object MainGenericRunner extends MainGenericRunner {
  def main(args: Array[String]) {
    if (!process(args))
      sys.exit(1)
  }
}
