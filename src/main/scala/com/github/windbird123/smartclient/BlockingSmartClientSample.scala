package com.github.windbird123.smartclient

import scalaj.http.{Http, HttpResponse}
import zio.{Task, UIO}

import scala.util.{Failure, Success, Try}

object BlockingSmartClientSample {
  def main(args: Array[String]): Unit = {
    val retryPolicy = new RetryPolicy {
      override val waitUntilServerIsAvailable: Boolean                                             = true
      override val maxRetryNumberWhenTimeout: Int                                                  = 5
      override val retryToAnotherAddressAfterSleepMs: Long                                         = 10000L
      override def isWorthRetryToAnotherAddress(smartResponse: HttpResponse[Array[Byte]]): Boolean = false
    }

    val addressDiscover: AddressDiscover = new AddressDiscover {
      override val periodSec: Long            = 300L
      override def fetch(): Task[Seq[String]] = UIO(Seq("https://jsonplaceholder.typicode.com"))
    }

    val client = BlockingSmartClient.create(addressDiscover)
    val response = Try {
      client.execute(Http("/todos/1").timeout(2000, 2000), retryPolicy)
    }

    response match {
      case Success(smartResponse) =>
        println(smartResponse.code)
        println(new String(smartResponse.body, io.Codec.UTF8.name))
      case Failure(e) =>
        e.printStackTrace()
    }
  }
}