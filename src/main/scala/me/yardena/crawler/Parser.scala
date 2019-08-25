package me.yardena.crawler

import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.model.Uri
import nl.grons.metrics4.scala.DefaultInstrumented
import org.jsoup.Jsoup

import scala.concurrent.Future
import scala.util.Try

/**
  * Simple JSoup based parser, that given a response body produces links
  * Receives ExtractLinks(url, body) and responds with LinkExtracted(link) for each link and then sending AllLinksFinished
  * Since JSoup API is blocking, this actor requires an dedicated thread pool (a.k.a. dispatcher)
  *
  * Created by yardena on 2019-08-22 19:03
  */
class Parser extends Actor with ActorLogging with DefaultInstrumented {
  import Parser.Message._
  import context.dispatcher
  import scala.collection.JavaConverters._

  override def receive: Receive = {
    case ExtractLinks(pageUrl, body) =>
      val origin = sender()
      Future {
        val doc = Jsoup.parse(body)
        doc.select("a").asScala.toList
      } map { links =>
        //extract href attribute where it exists and is non-null (Java library inter-op)
        val refs = links.flatMap(l => Try(l.attr("href")).toOption.filter(_ != null))
        refs
          .flatMap(x => Try(Uri(x)).toOption) //make sure it's a valid URI
          .map(uri => if (uri.isAbsolute) uri else uri.resolvedAgainst(Uri(pageUrl))) //resolve if relative against page url
          .filter(x => x.scheme == "http" || x.scheme == "https") //filter out stuff like "mailto:" etc.
          .map(_.withoutFragment) //strip internal page link (fragment)
          .map(_.toString)
      } recover { //in an unlikely event of parser error, log and continue
        case e =>
          log.error(e, s"Error while extracting links from $pageUrl")
          List.empty
      } foreach { cleanRefs =>
        metrics.histogram("Refs") += cleanRefs.size
        //return the links to ExtractLinks message sender
        cleanRefs.foreach(origin ! LinkExtracted(_))
        origin ! AllLinksFinished
      }
  }

}

object Parser {
  object Message {
    case class ExtractLinks(pageUrl: String, body: String)
    case class LinkExtracted(link: String)
    case object AllLinksFinished
  }
}