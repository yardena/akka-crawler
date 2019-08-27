package me.yardena.crawler

import java.io.File
import java.nio.file.{Files, Path, Paths}

import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import me.yardena.util.Configurable
import nl.grons.metrics4.scala.DefaultInstrumented

/**
  * Saves body of the page generating file name based on url path, returns the file name
  *
  * Created by yardena on 2019-08-27 16:59
  */
class Filer extends Actor with ActorLogging with Configurable with DefaultInstrumented {
  import Filer.Message._

  private val filerConf = config getConfig "filer"

  implicit val mat = ActorMaterializer()
  import context.dispatcher

  override def receive: Receive = {
    case SavePage(pageUrl, body) =>

      metrics.counter("Files").inc(1)

      val origin = context.sender()

      val dest = filePath(pageUrl)
      Files.createDirectories(Paths.get(dest.toString).getParent)

      //akka magic that saves files
      val f = Source.single(body)
        .map(ByteString(_))
        .runWith(
          FileIO.toPath(dest)
            .mapMaterializedValue(_.map(res =>
              res.status.map(_ => dest.toString).getOrElse("")
            ))
        )

      //return result to original sender
      import akka.pattern.pipe
      f map PageSaved pipeTo origin
  }

  protected def basePath: String = filerConf getString "save-dir" // "./data"

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

}

object Filer {
  object Message {
    case class SavePage(url: String, body: String)
    case class PageSaved(path: String)
  }
}
