package me.yardena.crawler

import akka.actor.{Actor, ActorRef, Terminated}
import scala.concurrent.duration._

/**
  * Watchdog. will kill actor system after watched actor dies
  *
  * Created by yardena on 2019-08-24 19:34
  */
class Terminator extends Actor {
  import Terminator.Message._
  import context.dispatcher

  override def receive: Receive = {
    case Monitor(ref) =>
      context watch ref
    case Terminated(_) =>
      context.system.scheduler.scheduleOnce(1.seconds)(context.system.terminate())
  }
}

object Terminator {
  object Message {
    case class Monitor(ref: ActorRef)
  }
}
