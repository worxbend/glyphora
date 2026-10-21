package io.worxbend.tui.examples.procmon

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Random, Try, Using}

/** One row of the process table, in the units the screen shows them in.
  *
  * `cpuPercent` and `memPercent` are percentages of one CPU and of total RAM respectively, so on a multi-core machine
  * the CPU column can legitimately exceed 100.
  */
final case class ProcessInfo(pid: Int, user: String, cpuPercent: Double, memPercent: Double, command: String)

/** Where procmon's rows come from.
  *
  * Everything the app cannot control lives behind this one method, so the whole UI can be driven from a deterministic
  * fake — the same shape `examples/weather` uses for its HTTP client. `sample()` is allowed to block; the app never
  * calls it on the render thread.
  */
trait ProcessSource:

  /** Shown in the header, so a reader can tell live data from the fallback at a glance. */
  def name: String

  def sample(): Seq[ProcessInfo]

object ProcessSource:

  /** Live data if this machine can provide it, synthetic data otherwise.
    *
    * The probe is a real sample rather than a `File("/proc").exists()` style guess: `ps` can be present and still fail
    * (a stripped container image, a sandbox that forbids `fork`), and finding that out at startup is much kinder than
    * finding it out once a second for the lifetime of the app.
    */
  def detect(): ProcessSource =
    val live = PsProcessSource()
    if Try(live.sample()).toOption.exists(_.sizeIs > 1) then live else SyntheticProcessSource()

/** Live process data, read by shelling out to `ps` once per refresh.
  *
  * Why `ps` and not `/proc` directly: `/proc` is Linux-only, and building these five fields from it means reading
  * `/proc/<pid>/stat` for jiffy counters, `/proc/<pid>/statm` for resident pages, `/proc/meminfo` for the total to
  * divide by, and the file owner for the user name — roughly eighty lines of kernel-format parsing that teach nothing
  * about the toolkit. `ps` computes all of it, and the same command works on macOS, so more readers see live rows
  * instead of the fallback.
  *
  * The cost, stated plainly: `ps` reports `%cpu` as an average over each process's whole lifetime, not as a delta since
  * the last sample the way `top` does, so the numbers drift rather than jump. A real monitor would read
  * `/proc/<pid>/stat` twice and divide.
  */
final class PsProcessSource extends ProcessSource:

  def name: String = "ps"

  def sample(): Seq[ProcessInfo] =
    // The trailing `=` on each field suppresses its header, so every line of output is a row. stderr goes to the
    // bin: nothing here would read it, and an undrained pipe is a place a sample can hang for ever.
    val process = ProcessBuilder("ps", "-eo", "pid=,user=,pcpu=,pmem=,comm=")
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    PsProcessSource.read(process, 2L, TimeUnit.SECONDS)

object PsProcessSource:

  private[procmon] def read(process: Process, timeout: Long, unit: TimeUnit): Seq[ProcessInfo] =
    val lines =
      Using.resource(scala.io.Source.fromInputStream(process.getInputStream, "UTF-8"))(_.getLines().toVector)
    if !process.waitFor(timeout, unit) then
      val _ = process.destroyForcibly()
    lines.flatMap(parse)

  /** `"  1842 worxbend   28.4  1.9 firefox"` — the limit of 5 keeps a command containing spaces in one piece. */
  private def parse(line: String): Option[ProcessInfo] =
    line.trim.split("\\s+", 5) match
      case Array(pid, user, cpu, mem, command) =>
        for
          parsedPid <- pid.toIntOption
          parsedCpu <- cpu.toDoubleOption
          parsedMem <- mem.toDoubleOption
        yield ProcessInfo(parsedPid, user, parsedCpu, parsedMem, command)
      case _                                   => None

/** A stand-in process list: plausible rows whose CPU and memory drift slowly, with no machine underneath.
  *
  * Two different kinds of determinism, and the split matters. The *shape* of the table — how many rows, which pids,
  * users and commands, and how busy each process is on average — comes from a seeded [[Random]], so two runs with the
  * same seed produce the same table. The *movement* is a pure function of the sample number, not of the RNG, so a test
  * can take three samples and know exactly what it is comparing. Values wander far enough to reorder a CPU-sorted table
  * between refreshes, which is the situation the app exists to survive.
  */
final class SyntheticProcessSource(seed: Long = 20260807L, processCount: Int = 48) extends ProcessSource:

  def name: String = "synthetic"

  // `sample()` runs on an Async worker thread, so the counter must not be a plain `var`
  private val samplesTaken = AtomicLong(0L)

  private val processes: Vector[SyntheticProcessSource.Template] =
    val random = Random(seed)
    Vector.tabulate(processCount) { index =>
      SyntheticProcessSource.Template(
        // strictly increasing across the vector, so every row has a distinct pid to pin a selection to
        pid = 100 + index * 7 + random.nextInt(6),
        user = SyntheticProcessSource.Users(random.nextInt(SyntheticProcessSource.Users.size)),
        command = SyntheticProcessSource.Commands(index % SyntheticProcessSource.Commands.size),
        // A handful of named busy processes over a steep long tail, which is what a real machine looks like: one
        // browser eating a third of a core and three hundred things at 0.0. The magnitudes matter as well as the
        // shape — the header sums this column, so a tail with a fat mean would report a permanently pegged 600%.
        baseCpu = SyntheticProcessSource.BusyProcesses.getOrElse(index, math.pow(random.nextDouble(), 4) * 1.5),
        baseMem = 0.4 + math.pow(random.nextDouble(), 3) * 4.0,
        phase = random.nextDouble() * math.Pi * 2,
      )
    }

  def sample(): Seq[ProcessInfo] =
    val step = samplesTaken.incrementAndGet()
    processes.map { template =>
      ProcessInfo(
        pid = template.pid,
        user = template.user,
        cpuPercent = SyntheticProcessSource.drift(template.baseCpu, template.phase, step),
        memPercent = SyntheticProcessSource.drift(template.baseMem, template.phase * 0.5, step),
        command = template.command,
      )
    }

object SyntheticProcessSource:

  private final case class Template(
      pid: Int,
      user: String,
      command: String,
      baseCpu: Double,
      baseMem: Double,
      phase: Double,
  )

  private val Users = Vector("root", "worxbend", "postgres", "www-data", "systemd")

  /** Row index to base CPU percentage. Index 7 is `chrome` and indices 22 and 38 are `java`, which is exactly the shape
    * of a working laptop.
    */
  private val BusyProcesses = Map(7 -> 34.0, 22 -> 12.5, 38 -> 5.5)

  private val Commands = Vector(
    "systemd",
    "kthreadd",
    "sshd",
    "postgres",
    "nginx",
    "node",
    "java",
    "chrome",
    "zsh",
    "dockerd",
    "containerd",
    "Xorg",
    "pipewire",
    "code",
    "rustc",
    "mill",
  )

  /** A sine wobble around `base`, plus a small absolute term so idle processes still move a little. */
  private def drift(base: Double, phase: Double, step: Long): Double =
    val swing = math.sin(step * 0.35 + phase)
    math.max(0.0, base * (1.0 + 0.3 * swing) + 0.15 * swing)
