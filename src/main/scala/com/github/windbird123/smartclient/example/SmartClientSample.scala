package com.github.windbird123.smartclient.example

import com.github.windbird123.smartclient.core.{AddressDiscover, RetryPolicy, SmartClient}
import scalaj.http.{Http, HttpResponse}
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random

object MainService {
  val logic: ZIO[Console with Blocking with Clock with Random, Throwable, Unit] = {
    val addressDiscover: AddressDiscover = new AddressDiscover {
      override val periodSec: Long            = 300L
      override def fetch(): Task[Seq[String]] = Task(Seq("https://jsonplaceholder.typicode.com"))
    }

    val retryPolicy = new RetryPolicy {
      override val waitUntilServerIsAvailable: Boolean     = true
      override val maxRetryNumberWhenTimeout: Int          = 5
      override val excludeFailedAddress: Boolean           = false
      override val retryToAnotherAddressAfterSleepMs: Long = 10000L
      override def isWorthRetryToAnotherAddress(smartResponse: HttpResponse[Array[Byte]]): Boolean =
        smartResponse.code == 429 || smartResponse.code == 503
      override val maxRetryNumberToAnotherAddress: Int = 3
    }

    for {
      client        <- SmartClient.create(addressDiscover)
      smartResponse <- client.execute(Http("/todos/1").timeout(2000, 2000), retryPolicy)
      _             <- console.putStrLn(smartResponse.code.toString)
      _             <- console.putStrLn(new String(smartResponse.body, io.Codec.UTF8.name))
    } yield ()
  }
}

object SmartClientSample extends zio.App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] =
    MainService.logic.exitCode
}
