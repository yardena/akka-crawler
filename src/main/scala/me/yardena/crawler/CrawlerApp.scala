package me.yardena.crawler

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import com.codahale.metrics.Slf4jReporter
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util._

/**
  * Created by yardena on 2019-08-24 17:42
  */
object CrawlerApp extends App {

  parseArgs() match {
    case Success((home, maxDepth)) =>
      run(home, maxDepth)
    case Failure(_) =>
      println("Usage: me.yardena.crawler.CrawlerApp <url> <depth>")
  }

  def parseArgs(): Try[(String, Int)] = Try {
    val home = args(0)
    val maxDepth = args(1).toInt
    require(maxDepth > 0)
    home -> maxDepth
  }

  def printUsage(): Unit = {
    println("Usage: me.yardena.crawler.CrawlerApp <url> <depth>")
  }

  def run(home: String, maxDepth: Int): Unit = {

    val system = ActorSystem("crawler")

    val crawler = system actorOf Props(new Crawler(home, maxDepth))
    crawler ! Crawler.Message.Crawl

    //watchdog
    (system actorOf Props[Terminator]) ! Terminator.Message.Monitor(crawler)

    system.whenTerminated.foreach{_ =>
      System.exit(0)
    }(ExecutionContext.global)

  }

}
