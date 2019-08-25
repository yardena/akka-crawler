package me.yardena.crawler

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.stream.{ActorAttributes, ActorMaterializer, OverflowStrategy, QueueOfferResult, Supervision}
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import akka.http.scaladsl.unmarshalling.Unmarshal
import me.yardena.util.Configurable
import nl.grons.metrics4.scala.DefaultInstrumented

import scala.compat.Platform
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * This actor retrieves web pages by url.
  * It receives a message Fetch and responds with a ResponsePage.
  * Internally it holds a reactive http stream that takes requests, passes them through host connection pools flow to produce results.
  *
  * Created by yardena on 2019-08-22 19:02
  */
class Fetcher extends Actor with ActorLogging with Configurable with DefaultInstrumented {
  import Fetcher.Message._

  import context.dispatcher

  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import HttpMethods._
  import headers._
  import MediaTypes._

  private implicit val mat = ActorMaterializer()

  private val fetcherConf = config getConfig "fetcher"
  private val http = Http(context.system)
  private val connection = http.superPool[(ActorRef, String)]()

  private val q: SourceQueueWithComplete[(Fetch, ActorRef)] =
      Source
        .queue[(Fetch,ActorRef)](fetcherConf.getInt("queue-size"), OverflowStrategy.dropNew) //enqueue request with the return address
//        .throttle(fetcherCOnf getInt "limit-requests-per-second", 1.second)
        .map { case (msg, ref) => // turn into HTTP request
          val h: Seq[HttpHeader] = msg.modified.toSeq.map(t => new `If-Modified-Since`(DateTime(t)))
          HttpRequest(GET, msg.url).withHeaders(h:_*) -> (ref -> msg.url)
        }
        .via(connection) //pass through pool flow
        .mapAsyncUnordered(16) { //parse
          case (Success(res), (ref, url)) =>
            (res.status match {
              case code@StatusCodes.OK if res.header[`Content-Type`].exists(_.contentType.mediaType == `text/html`) =>
                metrics.counter("OK").inc(1)
                val ts = /*res.header[`Last-Modified`].map(_.date.clicks) orElse*/ Some(Platform.currentTime)
                Unmarshal(res.entity).to[String] map (s => ResponsePage(code.intValue, Option(s), lastRetrieved = ts))
              case code if code.intValue() >= 300 && code.intValue() < 400 && res.header[Location].isDefined =>
                metrics.counter("Redirect").inc(1)
                val redirect = res.header[Location].get.uri.resolvedAgainst(Uri(url))
                res.discardEntityBytes().future() map (_ => ResponsePage(code.intValue(), None, Some(redirect.toString)))
              case code =>
                metrics.counter(code.intValue().toString).inc(1)
                log.info(s"Not saving $url - returned ${res.status} with type ${res.header[`Content-Type`].getOrElse("n.a.")}")
                res.discardEntityBytes().future() map (_ => ResponsePage(code.intValue(), None))
            }) map (_ -> ref)
          case (Failure(e), (ref, url)) =>
            log.error(e, s"Unexpected error fetching $url")
            Future successful (ResponsePage(StatusCodes.InternalServerError.intValue, None) -> ref)
        }
        .map {
          case (res, ref) => ref ! res //send result back to the return address
        }
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider)) //continue processing after errors
        .to(Sink.ignore) //run perpetually
        .run()

  override def receive: Receive = {
    case msg@Fetch(url, _) =>
      val origin = context.sender()

      q.offer(msg -> origin) onComplete {
        case util.Success(QueueOfferResult.Enqueued) =>
          //all good
        case util.Success(_) =>
          //any other queue response (not good)
          origin ! Status.Failure(new RuntimeException(s"Could not fetch $url"))
        case Failure(e) =>
          origin ! Status.Failure(e)
      }

      metrics.meter("fetch").mark()

  }

  override def postStop(): Unit = {
    super.postStop()
    http.shutdownAllConnectionPools() //clean up resources
  }
}

object Fetcher {
  object Message {
    case class Fetch(url: String, modified: Option[Long])
    case class ResponsePage(code: Int, body: Option[String] = None, redirect: Option[String] = None, lastRetrieved: Option[Long] = None)
  }
}

