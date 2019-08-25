package me.yardena.crawler

import java.util.concurrent.{TimeUnit, TimeoutException}

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.http.scaladsl.model.Uri
import com.codahale.metrics.Slf4jReporter
import me.yardena.util.Configurable
import nl.grons.metrics4.scala.DefaultInstrumented
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Main actor for crawler
  *
  * Created by yardena on 2019-08-22 18:58
  */
class Crawler(home: String, maxDepth: Int) extends Actor with ActorLogging with Configurable with DefaultInstrumented {
  import Crawler.Message._

  private val crawlerConf = config getConfig "crawler"

  //Creates fetcher and parser, each on dedicated (and appropriately selected for the job) thread pool
  protected val fetcher : ActorRef = context actorOf Props[Fetcher]().withDispatcher("client-dispatcher")
  protected val parser  : ActorRef = context actorOf Props[Parser]().withDispatcher("parser-dispatcher")

  //all encountered pages and their status
  private var pagesStatus = Map.empty[String,Crawler.PageProcessingStatus]

  //"in-flight" pages
  protected val maxConcurrentPages: Int = crawlerConf getInt "max-concurrent-pages"
  protected def inProgressPagesCount: Int = pagesStatus.values count (_ == Crawler.InProgressPage)

  private val reporter = Slf4jReporter.forRegistry(metricRegistry)
    .outputTo(LoggerFactory.getLogger("me.yardena.crawler.Metrics"))
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  protected def validateInput(siteUrl: String): Try[String] = {
    val urlWithProtocol =
      if (siteUrl.startsWith("http://") || siteUrl.startsWith("https://")) {
        siteUrl
      } else if (siteUrl.startsWith("//")) {
        "http".concat(siteUrl)
      } else {
        "http://".concat(siteUrl)
      }
    Try(Uri(urlWithProtocol)) map (_.withoutFragment.toString)
  }

  protected def processPage(msg: HandleLink): Unit = {
    if (inProgressPagesCount >= maxConcurrentPages) { //too many pages "in flight"
      //queue page for processing
      pagesStatus = pagesStatus + (msg.link -> Crawler.QueuedPage(msg.depth))
    } else {
      //process page and mark it "in flight"
      pagesStatus = pagesStatus + (msg.link -> Crawler.InProgressPage)
      log.info(s"Crawling to ${msg.link}")

      val page = context actorOf Props(new PageHandler(maxDepth, fetcher, parser))
      context watch page
      page ! msg
    }
  }

  protected def pageProcessed(msg: LinkHandled): Unit = {
    //determine appropriate status
    val result: Crawler.PageProcessingStatus = msg.error match {
      case None =>
        Crawler.SuccessfullyFinishedPage
      case Some(e) =>
        Crawler.FailedProcessingPage(e)
    }
    //update page status
    pagesStatus = pagesStatus + (msg.link -> result)
    //dequeue any pages that may be awaiting processing, as a spot freed up
    pagesStatus collectFirst { //find one
      case (url, Crawler.QueuedPage(depth)) => url -> depth
    } foreach { case (url, depth) =>
      processPage(HandleLink(url, depth))
    }

    //output result
    msg.ratio foreach { r =>
      println(msg.link.concat("\t").concat(msg.depth.toString).concat("\t").concat(r.toString))
    }

  }

  override def preStart(): Unit = {
    super.preStart()
    //print headline
    println("url\tdepth\tratio")
  }

  override def receive: Receive = {
    case Crawl => //start here
      validateInput(home) match {
        case scala.util.Success(siteUrl) => //proceed
          self ! HandleLink(siteUrl, 1)

        case scala.util.Failure(e) => //exit
          log.error(e, s"Cannot crawl site $home")
          context stop self
      }

    case msg@HandleLink(link, _) =>
      //if link hasn't been processed or is in progress/enqueued, process it
      if (!pagesStatus.contains(link)) processPage(msg)

    case msg: LinkHandled =>
      //update link status and output result
      pageProcessed(msg)

    case Terminated(_) =>
      val activeActors = context.children.toList.size
      //no more workers, except fetcher and parser
      if (activeActors <= 2) {
        log.info(s"Finished crawling $home")
        reporter.report()
        context stop self
      }
  }

}

object Crawler {

  object Message {
    case object Crawl
    case class HandleLink(link: String, depth: Int)
    case class LinkHandled(link: String, depth: Int, error: Option[Throwable] = None, ratio: Option[Double] = None, lastModified: Option[Long] = None)
  }

  // -- statuses --
  sealed trait PageProcessingStatus

  case object  InProgressPage extends PageProcessingStatus
  case class   QueuedPage(depth: Int) extends PageProcessingStatus
  case object  SuccessfullyFinishedPage extends PageProcessingStatus
  case class   FailedProcessingPage(e: Throwable) extends PageProcessingStatus
  // --------------

}
