package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

final class RenderQueueFairnessSpec extends AnyFunSuite:

  test("a drain yields after a finite FIFO batch without losing queued successors"):
    val loop                    = RenderThread.register(Thread.currentThread())
    val seen                    = ArrayBuffer.empty[Int]
    def successor(i: Int): Unit =
      seen.append(i)
      if i < 10000 then loop.enqueue(() => successor(i + 1))
    try
      loop.enqueue(() => successor(1))
      loop.enqueue(() => { val _ = seen.append(-1) })
      loop.drain()
      assert(seen.size <= 256, s"one turn ran ${seen.size} callbacks")
      assert(seen.take(3).toList == List(1, -1, 2))
      while seen.size < 10001 do loop.drain()
      assert(seen.toList == 1 :: -1 :: (2 to 10000).toList)
      assert(loop.dropCount == 0)
    finally
      loop.close()
      RenderThread.unregister()

  test("owned and detached successor chains both receive a finite share"):
    val detached             = RenderThread.capture()
    var unattributed         = 0
    def detachedWork(): Unit =
      unattributed += 1
      if unattributed < 1000 then detached.enqueue(() => detachedWork())
    detached.enqueue(() => detachedWork())
    val loop                 = RenderThread.register(Thread.currentThread())
    var owned                = 0
    def ownedWork(): Unit    =
      owned += 1
      if owned < 1000 then loop.enqueue(() => ownedWork())
    try
      loop.enqueue(() => ownedWork())
      RenderThread.drainPending(loop)
      assert(owned == 256)
      assert(unattributed == 256)
      assert(RenderThread.hasPending(loop))
      while RenderThread.hasPending(loop) do RenderThread.drainPending(loop)
      assert(owned == 1000 && unattributed == 1000)
    finally
      loop.close()
      RenderThread.unregister()
      while detached.hasPending do detached.drain()

  test("failures consume budget while later FIFO work remains deliverable"):
    var failures  = 0
    val loop      = RenderThread.register(Thread.currentThread(), onError = _ => failures += 1)
    var delivered = false
    try
      (1 to 256).foreach(_ => loop.enqueue(() => throw IllegalStateException("queued")))
      loop.enqueue(() => delivered = true)
      loop.drain()
      assert(failures == 256)
      assert(!delivered)
      loop.drain()
      assert(delivered)
    finally
      loop.close()
      RenderThread.unregister()
