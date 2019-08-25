package me.yardena.crawler

import java.time
import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink, Source}
import akka.util.ByteString
import me.yardena.util.Configurable
import nl.grons.metrics4.scala.DefaultInstrumented

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * This actor represents a state machine of handling a link/page
  *
  * Created by yardena on 2019-08-22 19:01
  */
class PageHandler(maxDepth: Int, fetcher: ActorRef, parser: ActorRef) extends Actor with ActorLogging with Configurable with DefaultInstrumented {
  import PageHandler.Message._
  import Crawler.Message.{HandleLink, LinkHandled}
  import Fetcher.Message.{Fetch,ResponsePage}
  import Parser.Message.{ExtractLinks,LinkExtracted,AllLinksFinished}

  implicit val mat = ActorMaterializer()
  import context.dispatcher

  private val pageConf = config getConfig "page-handler"
  private val expireAfter: time.Duration = pageConf getDuration "check-modified-after"
  private val processingTimeout: time.Duration = pageConf getDuration "processing-timeout"

  private var pageUrl: String = _
  private var depth: Int = 0
  private def keepCrawling: Boolean = depth < maxDepth

  private var redirectCount = 0
  private var savePath: Option[String] = None
  private var links: List[String] = List.empty
  private var lastRetrieved: Option[Long] = None

  //stats calculation
  private def ratio: Option[Double] = {
    if (links.isEmpty) None else {
      //split the links into 2 categories: to same domain and the rest
      val (domestic,foreign) = links.map(Uri(_)).partition(linkUri =>
        Uri(pageUrl).authority.host.equalsIgnoreCase(linkUri.authority.host)
      )
      val domesticLinkCounter = domestic.size
      val foreignLinkCounter = foreign.size
      Some(domesticLinkCounter.toDouble / (domesticLinkCounter + foreignLinkCounter))
    }
  }

  private var cache: ActorRef = _

  override def receive: Receive = { //state machine step
    case HandleLink(url, d) =>
      pageUrl = url
      depth = d

      cache = context actorOf Props(new PageCache(url))
      cache ! PageCache.Message.PageRetrieve
      //move to the next step in the state machine, step comprised of handling cache behavior and error handling behavior
      context become (handleCacheResponse orElse handleError)

      //if page is not processed after specified time, mark it as failed with timeout
      val timeout = PageHandler.asScalaDuration(processingTimeout)
      context.system.scheduler.scheduleOnce(timeout)(self ! Status.Failure(new TimeoutException(s"Page $url timed out after $timeout")))
  }

  private def handleCacheResponse: Receive = { //state machine step
    case PageCache.Message.PageMiss => //page is not in the cache, fetch it
      fetcher ! Fetch(pageUrl, None)
      context become (fetching orElse handleError)

    case entry: PageCache.Message.PageEntry => //page is in the cache, fetch it only if expired
      if (entry.retrieved.exists(PageHandler.hasExpired(_, expireAfter))) //if cache entry may be too old
        fetcher ! Fetch(pageUrl, entry.retrieved) //fetch new page, if it has changed
      else
        self ! ResponsePage(304) //use cached entry, shortcut by returning Not-Modified HTTP code
      savePath = entry.savePath
      links = entry.links
      lastRetrieved = entry.retrieved
      context become (fetching orElse handleError)

  }

  private def fetching: Receive = { //state machine step
    case ResponsePage(200, Some(body), _, ts) => //result
      lastRetrieved = ts
      //save page to disk and when succeeded forward the resulting path to self
      import akka.pattern.pipe
      Source single body runWith savePage(pageUrl) map PageSaved pipeTo self
      //in parallel parse page body for links
      parser ! ExtractLinks(pageUrl, body)
      context become (processing(saved = false, linksDone = false) orElse handleError)

    case res: ResponsePage if res.code >= 300 && res.code < 400 && res.redirect.isDefined => //redirect
      if (redirectCount < (pageConf getInt "max-redirects")) {
        redirectCount = redirectCount + 1
        self ! HandleLink(res.redirect.get, depth)
        context become (redirecting orElse handleError)
      } else {
        finish()
      }

    case res: ResponsePage if res.code == 304 => //not modified
      //use cached data to move further through the state machine
      links.foreach(l => self ! LinkExtracted(l))
      self ! AllLinksFinished
      context become (processing(saved = true, linksDone = false) orElse handleError)

    case res: ResponsePage if res.code < 400 => //ignore
      finish()

    case res: ResponsePage if res.code >= 400 && res.code < 500 => //user error
      finish(Some(new RuntimeException(s"Page $pageUrl returned ${res.code}")))

    case res: ResponsePage if res.code >= 500 => //server error
      finish(Some(new RuntimeException(s"Page $pageUrl returned ${res.code}")))
  }

  private def processing(saved: Boolean, linksDone: Boolean): Receive = { //state machine step

    //saving to disk and parsing may finish in any order, but both need to complete to proceed

    case PageSaved(path) =>
      savePath = Some(path)
      log.info(s"Saved $pageUrl to $path")
      if (linksDone)
        finish()
      else
        context become (processing(saved = true, linksDone = false) orElse handleError)

    case AllLinksFinished =>
      if (saved)
        finish()
      else
        context become (processing(saved = false, linksDone = true) orElse handleError)

    case LinkExtracted(link) =>
      links = links :+ link
      if (keepCrawling) context.parent ! HandleLink(link, depth + 1)
  }

  private def handleError: Receive = { //partial state machine step
    case Status.Failure(e) =>
      finish(Some(new RuntimeException(s"Request to $pageUrl failed", e)))

  }

  private def redirecting: Receive = {
    case HandleLink(url, d) =>
      fetcher ! Fetch(url, None)
      context become (fetching orElse handleError)
  }

  protected def basePath: String = pageConf getString "save-dir" // "./data"

  private def filePath(pageUrl: String): Path = {
    val uri = Uri(pageUrl)
    var path = "/" + uri.authority.host.toString.replace('.','_') + uri.path.toString
    if (!path.endsWith(".html")) {
      if (!path.contains("/")) {
        path = path.concat("/")
      }
      if (path.endsWith("/")) {
        path = path.concat("index")
      }
      path = path.concat(".html")
    }
    val f = new File(basePath.concat(path))
    f.toPath
  }

  /**
    * Saves body of the page and returns the file name
    * @param pageUrl url of the page
    * @return
    */
  protected def savePage(pageUrl: String): Sink[String, Future[String]] = {
    metrics.counter("Files").inc(1)
    val dest = filePath(pageUrl)
    Files.createDirectories(Paths.get(dest.toString).getParent)
    //akka magic that saves files
    // we don't take full advantage of it because we pass a whole String response body in
    // we could have passed a Source of bytes to make it better
    Flow[String].map(ByteString(_)).toMat(FileIO.toPath(dest))(Keep.right)
      .mapMaterializedValue(_.flatMap(res =>
        Future.fromTry(res.status.map(_ => dest.toString))
      ))
  }

  protected def finish(error: Option[Throwable] = None): Unit = {
    error match {
      case Some(e) =>
        log.error(e.getMessage)
        context.parent ! LinkHandled(pageUrl, depth, error)
      case None =>
        val r = ratio
        //update cache
        cache ! PageCache.Message.PageEntry(links, savePath, r, lastRetrieved)
        //report back to crawler that page processing finished
        context.parent ! LinkHandled(pageUrl, depth, ratio = r, lastModified = lastRetrieved)
    }

    context stop self
  }

}

object PageHandler {
  object Message {
    case class PageSaved(path: String)
  }

  // -- utility methods for time handling/conversion --

  def timestampToDateTime(t: Long): time.ZonedDateTime = {
    time.ZonedDateTime.ofInstant(time.Instant.ofEpochSecond(t / 1000), time.ZoneId.systemDefault())
  }
  def hasExpired(t: Long, after: time.Duration): Boolean = {
    timestampToDateTime(t).plus(after).isBefore(time.ZonedDateTime.now)
  }
  def asScalaDuration(d: time.Duration): FiniteDuration = {
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)
  }

}
