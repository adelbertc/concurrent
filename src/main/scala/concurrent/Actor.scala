package scalaz
package concurrent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

sealed case class Actor[A](e: A => Unit, onError: Throwable => Unit = throw (_))(implicit val strategy: Strategy) {
  private val suspended = new AtomicBoolean(true)
  private val mbox = new ConcurrentLinkedQueue[A]

  private def work = {
    val mt = mbox.isEmpty
    if (mt) () => ()
    else if (suspended.compareAndSet(!mt, false)) act ! (())
    else () => ()
  }

  val toRun: Run[A] = Run.run[A]((a) => this ! a)

  def !(a: A) = if (mbox offer a) work else toRun ! a

  def apply(a: A) = this ! a

  import Actor._

  def contramap[B](f: B => A): Actor[B] =
    actor[B]((b: B) => (this ! f(b))(), onError)(strategy)

  private val act: Run[Unit] = Run.run((u: Unit) => {
    var go = true
    var i = 0
    while (go && i < 1000) {
      val m = mbox.poll
      if (m != null) try {
        e(m)
        i = i + 1
      } catch {
        case e => onError(e)
      }
      else {
        suspended.set(true)
        work
        go = false
      }
    }
    if (mbox.peek != null) act ! u else ()
  })
}

object Actor extends Actors

trait Actors {
  def actor[A](e: A => Unit, err: Throwable => Unit = throw (_))(implicit s: Strategy): Actor[A] =
    Actor[A](e, err)

  implicit def actorContravariant: Contravariant[Actor] = new Contravariant[Actor] {
    def contramap[A, B](r: Actor[A])(f: (B) => A): Actor[B] = r contramap f
  }

  implicit def ActorFrom[A](a: Actor[A]): A => Unit = a ! _
}
