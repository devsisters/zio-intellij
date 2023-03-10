package zio.intellij.actions

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.JavaValue
import com.intellij.execution.filters.{ExceptionFilters, TextConsoleBuilderFactory}
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.impl.RunnerContentUi
import com.intellij.notification.NotificationGroup
import com.intellij.openapi.actionSystem.{AnActionEvent, DefaultActionGroup}
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.project.{DumbAwareAction, Project}
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.text.DateFormatUtil
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.{XNamedValue, XValue}
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.xdebugger.impl.frame.XThreadsView
import com.intellij.xdebugger.impl.ui.tree.nodes.XEvaluationCallbackBase
import com.sun.jdi._
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.compiler.references.ModuleSbtExtensions
import org.jetbrains.plugins.scala.project.{LibraryExt, ModuleExt, ProjectExt, ScalaLanguageLevel}
import zio.intellij.utils
import zio.intellij.utils.Version

import scala.annotation.tailrec

final class MyTestAction extends DumbAwareAction {
  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    if (project == null)
      return;

    val sourceModules = project.modulesWithScala.filter(_.isSourceModule)

    // should work for non-sbt projects
    val zioVersion = (for {
      module  <- sourceModules
      library <- module.libraries
      url     <- library.getUrls(OrderRootType.CLASSES)
      if url.contains("/dev/zio/zio_")
      trimmedUrl  = utils.trimAfterSuffix(url, ".jar")
      versionStr <- LibraryExt.runtimeVersion(trimmedUrl)
      version    <- Version.parse(versionStr)
    } yield version).headOption

    implicit val scalaLanguageLevel: ScalaLanguageLevel =
      sourceModules
        .flatMap(_.scalaLanguageLevel)
        .headOption
        .getOrElse(ScalaLanguageLevel.getDefault)

    zioVersion.fold(warningNotification(project, "ZIO dependency not found")) { implicit version =>
//      if (version >= Version.ZIO.RC21) {
//        warningNotification(
//          project,
//          "Dump Fibers functionality is not yet implemented for ZIO version 1.0.0-RC21 and above " +
//            "due to changes in fiber tracking"
//        )
//      } else if (version < Version.ZIO.RC18) {
//        warningNotification(
//          project,
//          "Dump Fibers functionality is not implemented for ZIO versions below 1.0.0-RC18 " +
//            "due to the fact that the Fiber.dumpAll was added in RC18"
//        )
//      } else {
        invokeMyAction(project)
//      }
    }
  }

  override def update(event: AnActionEvent): Unit = {
    val presentation = event.getPresentation
    val project      = event.getProject
    if (project == null) {
      presentation.setEnabled(false)
    } else {
      val debuggerSession = DebuggerManagerEx.getInstanceEx(project).getContext.getDebuggerSession
      presentation.setEnabled(debuggerSession != null && debuggerSession.isAttached)
    }
  }

  def warningNotification(project: Project, message: String): Unit =
    MyTestAction.NOTIFICATION_GROUP
      .createNotification(message, MessageType.WARNING)
      .notify(project)

  private def invokeMyAction(
    project: Project
  )(implicit zioVersion: Version, languageLevel: ScalaLanguageLevel): Unit = {
    val debuggerContext = DebuggerManagerEx.getInstanceEx(project).getContext
    val session         = debuggerContext.getDebuggerSession
    if (session == null || !session.isAttached) {
      return ()
    }

    val process = debuggerContext.getDebugProcess

    val evaluator = process.getXdebugProcess.getEvaluator
    val expr = new XExpressionImpl(
      // "new zio.BootstrapRuntime {}.unsafeRun(zio.Fiber.dumpAll).toArray",
      "1 + 2 + 3",
      ScalaLanguage.INSTANCE,
      null,
      EvaluationMode.EXPRESSION
    )

    process.getManagerThread.invoke { () =>
      val vmProxy = process.getVirtualMachineProxy
      evaluator.evaluate(
        expr,
        new XEvaluationCallbackBase {
          override def evaluated(result: XValue): Unit = {
            vmProxy.suspend()
            try {
              val dump = buildValue(result)
              ApplicationManager.getApplication.invokeLater(
                new Runnable {
                  override def run: Unit = warningNotification(project, dump)
                }
//                  val xSession = session.getXDebugSession
//                  if (xSession != null) {
//                    MyTestAction.addFiberDump(project, dump, xSession.getUI, session.getSearchScope)
//                  }
                ,
                ModalityState.NON_MODAL
              )
            } finally vmProxy.resume()
          }

          override def errorOccurred(errorMessage: String): Unit =
            MyTestAction.NOTIFICATION_GROUP
              .createNotification(
                s"Error during evaluation of Fiber.dumpAll: $errorMessage",
                MessageType.ERROR
              )
              .notify(project)
        },
        null
      )
    }
  }

  private def buildValue(
    xValue: XValue
  )(implicit zioVersion: Version, languageLevel: ScalaLanguageLevel): String = {
    (xValue match {
      case value: JavaValue => {
        val v = value.getDescriptor.getFullValueDescriptor.calcValue(value.getEvaluationContext)
        v.toString
      }
      case _ => "ERROR ON GETTING getDescriptor()"
    })
//
//    @tailrec
//    def inner(fiberDumps: List[Value], acc: List[FiberInfo]): List[FiberInfo] =
//      if (fiberDumps.isEmpty) acc
//      else {
//        val rawResults           = fiberDumps.flatMap(convertFiberInfoWithChildren(_))
//        val currentLevelDump     = rawResults.map(_.info)
//        val currentLevelChildren = rawResults.flatMap(_.children)
//        inner(currentLevelChildren, acc ++ currentLevelDump)
//      }
//
//    xValue match {
//      case javaValue: JavaValue =>
//        val valueDescriptor = javaValue.getDescriptor
//        if (valueDescriptor == null) Nil
//        else {
//          val fullValueDescriptor = valueDescriptor.getFullValueDescriptor
//          if (fullValueDescriptor == null) Nil
//          else {
//            val dumpValues = convertScalaSeq(fullValueDescriptor.calcValue(javaValue.getEvaluationContext))
//            inner(dumpValues, Nil)
//          }
//        }
//      case _ => Nil
//    }
  }

//  def convertScalaSeq(value: Value)(implicit languageLevel: ScalaLanguageLevel): List[Value] = {
//    @tailrec
//    def convertList(value: Value, acc: List[Value]): List[Value] = value match {
//      case obj: ObjectReference if obj.`type`().name() == ListRef.ConsName =>
//        val head = getFieldValue(obj, ListRef.HeadField)
//        val tail = getFieldValue(obj, ListRef.TailField)
//        convertList(tail, head :: acc)
//      case _ => acc.reverse
//    }
//
//    value match {
//      case obj: ObjectReference if obj.`type`().name() == ListRef.ConsName => convertList(value, Nil)
//      case array: ArrayReference => array.getValues.asScala.toList
//      case _ => Nil
//    }
//  }

}

object MyTestAction {

  val NOTIFICATION_GROUP: NotificationGroup = NotificationGroup.balloonGroup("Fiber Dump Notifications")
//  def addFiberDump(
//    project: Project,
//    fibers: List[FiberInfo],
//    ui: RunnerLayoutUi,
//    searchScope: GlobalSearchScope
//  ): Unit = {
//    val consoleView = TextConsoleBuilderFactory
//      .getInstance()
//      .createBuilder(project)
//      .filters(ExceptionFilters.getFilters(searchScope))
//      .getConsole
//    consoleView.allowHeavyFilters()
//    val toolbarActions = new DefaultActionGroup()
//    val panel          = new FiberDumpPanel(project, consoleView, toolbarActions, fibers)
//
//    val id      = s"Fiber Dump ${DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis)}"
//    val content = ui.createContent(id, panel, id, null, null)
//    content.putUserData(RunnerContentUi.LIGHTWEIGHT_CONTENT_MARKER, java.lang.Boolean.TRUE)
//    content.setCloseable(true)
//    content.setDescription("Fiber Dump")
//    ui.addContent(content)
//    ui.selectAndFocus(content, true, true)
//
//    Disposer.register(content, consoleView)
//    ui.selectAndFocus(content, true, false)
//  }
}
