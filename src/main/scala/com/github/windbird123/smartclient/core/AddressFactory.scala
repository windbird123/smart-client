package com.github.windbird123.smartclient.core

import com.typesafe.scalalogging.LazyLogging
import scalaj.http.HttpRequest
import zio._
import zio.clock.Clock
import zio.duration._
import zio.random.Random

class NoAvailableAddressException(message: String) extends Exception(message)

class AddressFactory(ref: Ref[Seq[String]], addressDiscover: AddressDiscover) extends LazyLogging {
  def fetchAndSet(): Task[Unit] =
    for {
      addr <- addressDiscover
               .fetch()
               .tapError((t: Throwable) =>
                 UIO(logger.error(s"failed to fetch address from discover service, ${t.getMessage}"))
               )
               .orElse(ref.get) // 실패할 경우 기존 주소를 그대로 유지한다.
      _ <- ref.set(addr)
      _ <- Task(logger.info(s"Base addresses are updated, addresses=[${addr.mkString(",")}]"))
    } yield ()

  def scheduleUpdate(): ZIO[Clock with Random, Throwable, Unit] =
    for {
      delayFactor <- random.nextDouble
      period      = addressDiscover.periodSec
      schedule    = Schedule.spaced(period.seconds) && Schedule.forever
      _           <- fetchAndSet().repeat(schedule).delay((delayFactor * period).toLong.seconds)
    } yield ()

  def choose(waitUntilServerIsAvailable: Boolean): ZIO[Clock, Throwable, String] =
    ref.get
      .map(addrs =>
        scala.util.Random
          .shuffle(addrs)
          .headOption
          .toRight(
            new NoAvailableAddressException(
              s"Any available address was not found, waitUntilServerIsAvailable=[$waitUntilServerIsAvailable]"
            )
          )
      )
      .repeatWhileM {
        case Right(_) => UIO(false)
        case Left(_) =>
          UIO(waitUntilServerIsAvailable) <* ZIO.when(waitUntilServerIsAvailable)(ZIO.unit.delay(5.seconds))
      }
      .absolve

  def exclude(blacks: Seq[String]): UIO[Unit] = ZIO.when(blacks.nonEmpty) {
    UIO(logger.info(s"Abnormal addresses=[${blacks.mkString(",")}] are excluded")) *> ref.update(x =>
      x.filterNot(blacks.contains)
    )
  }

  def build(chosenBase: String, r: HttpRequest): HttpRequest = r.copy(url = chosenBase + r.url)
}
