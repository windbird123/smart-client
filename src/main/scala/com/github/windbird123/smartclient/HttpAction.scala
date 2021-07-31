package com.github.windbird123.smartclient

import java.net.SocketTimeoutException

import com.typesafe.scalalogging.LazyLogging
import scalaj.http.{HttpRequest, HttpResponse}
import zio._
import zio.duration._

trait HttpAction {
  def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]]
}

object DefaultHttpAction extends HttpAction with LazyLogging {
  override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] = {
    lazy val schedule: Schedule[Any, Throwable, ((Duration, Long), Throwable)] =
      Schedule.exponential(1.second) && Schedule.recurs(maxRetryNumberWhenTimeout) && Schedule.recurWhile[Throwable] {
        case e: SocketTimeoutException =>
          logger.info(s"retry to same address, url=[${r.url}] cause=[${e.getMessage}]")
          true

        case t: Throwable =>
          logger.info(s"failed to request, url=[${r.url}]", t)
          false
      }

    blocking.effectBlocking {
      r.asBytes
    }.retry(schedule).provideLayer(ZEnv.live)
  }
}
