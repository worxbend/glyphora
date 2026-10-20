package io.worxbend.tui.dsl

import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration

import org.scalatest.funsuite.AnyFunSuite

/** A 0.19-style services double with exactly the migration guide's AppServices additions. */
final class MigrationServicesSpec extends AnyFunSuite:
  private abstract class PreviousServices extends AppServices:
    def pushScreen(screen: Screen): Unit                                            = ()
    def popScreen(): Unit                                                           = ()
    def openPalette(): Unit                                                         = ()
    def closePalette(): Unit                                                        = ()
    def runEffect(effect: Effect): Unit                                             = ()
    def requestRedraw(): Unit                                                       = ()
    def copyToClipboard(text: String): Unit                                         = ()
    def quit(): Unit                                                                = ()
    def notify(message: String, level: NoticeLevel, duration: FiniteDuration): Unit = ()
    def dismissToasts(): Unit                                                       = ()
    def desktop(title: String, body: String): Unit                                  = ()

  private final class MigratedServices extends PreviousServices:
    def runEffect(key: String, effect: Effect): Unit                    = ()
    def editExternally(initial: String, suffix: String): Option[String] = None
    def openInEditor(path: Path, line: Option[Int]): Unit               = ()
    def keyMode: Signal[ModeId]                                         = Signal(ModeId.Default)
    def colorScheme: Reactive[ColorScheme]                              = Signal(ColorScheme.Unknown)
    def taskbarProgress(progress: Reactive[Option[Double]]): Unit       = ()
    def taskbarState(state: Reactive[TaskbarState]): Unit               = ()
    def clearTaskbarProgress(): Unit                                    = ()

  test("the documented AppServices additions make a concrete double"):
    val services: AppServices = MigratedServices()
    assert(services.keyMode.peek == ModeId.Default)
    assert(services.colorScheme.peek == ColorScheme.Unknown)
    services.taskbarProgress(Signal(Some(0.5)))
    services.taskbarState(Signal(TaskbarState.Indeterminate))
    services.clearTaskbarProgress()
    val root                  = DocumentationSources.repoRoot.getOrElse(cancel("not running from a checkout"))
    val guide                 = Files.readString(root.resolve("website/docs/versioning.md")).replaceAll("\\s+", "")
    Seq(
      "def runEffect(key: String, effect: Effect): Unit = ()",
      "def editExternally(initial: String, suffix: String): Option[String] = None",
      "def openInEditor(path: Path, line: Option[Int]): Unit = ()",
      "def keyMode: Signal[ModeId] = Signal(ModeId.Default)",
      "def colorScheme: Reactive[ColorScheme] = Signal(ColorScheme.Unknown)",
      "def taskbarProgress(progress: Reactive[Option[Double]]): Unit = ()",
      "def taskbarState(state: Reactive[TaskbarState]): Unit = ()",
      "def clearTaskbarProgress(): Unit = ()",
    ).foreach(line => assert(guide.contains(line.replaceAll("\\s+", "")), s"migration snippet lost: $line"))
