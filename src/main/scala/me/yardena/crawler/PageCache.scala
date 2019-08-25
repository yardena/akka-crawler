package me.yardena.crawler

import java.math.BigInteger

import akka.persistence.PersistentActor
import me.yardena.util.Configurable
import org.pico.hashids.Hashids

/**
  * This actor is responsible for saving state of a page on disk
  *
  * Created by yardena on 2019-08-26 00:39
  */
class PageCache(url: String) extends PersistentActor with Configurable {

  private val hashids = Hashids.reference(config getString "page-cache.salt") //for id generation

  override def persistenceId: String = hashids.encodeHex(PageCache.toHex(url)) //unique id for url

  import PageCache.Message._
  private var state: Option[PageEntry] = None

  override def receiveRecover: Receive = { //on start-up, read last state from journal
    case entry: PageEntry =>
      state = Some(entry)
    case _ =>
      //ignore everything else
  }

  override def receiveCommand: Receive = {
    //request for state
    case PageRetrieve =>
      sender ! (state getOrElse PageMiss)

    //save state to journal
    case entry: PageEntry => persist(entry) { it =>
      state = Some(it)
    }
  }
}

object PageCache {
  case class State()

  object Message {
    case object PageRetrieve
    case object PageMiss
    case class PageEntry
    (
      links: List[String] = List.empty,
      savePath: Option[String] = None,
      ratio: Option[Double] = None,
      retrieved: Option[Long] = None
    )
  }

  // -- utility --
  def toHex(arg: String): String = String.format("%040x", new BigInteger(1, arg.getBytes("UTF-8")))

}
