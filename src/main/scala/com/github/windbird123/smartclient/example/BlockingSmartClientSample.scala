package com.github.windbird123.smartclient.example

import com.github.windbird123.smartclient.core.{AddressDiscover, BlockingSmartClient, RetryPolicy}
import scalaj.http.{Http, HttpResponse}
import zio.Task

import scala.util.{Failure, Success, Try}

object BlockingSmartClientSample {
  def main(args: Array[String]): Unit = {
    val addressDiscover: AddressDiscover = new AddressDiscover {
      // periodSec 마다 fetch() 를 호출해 API 서버 목록을 갱신한다.
      override val periodSec: Long = 300L

      // - 리턴값의 주소 정보로 host, port 까지만 지정해 사용하는 것을 권장
      //     예: Task{ Seq("http://my.api.server:8000", "http://my.api.server:9000") }
      // - zio 의 Task 에 익숙하지 않을 경우, Task 를 scala.util.Try 로 생각하고 작성해도 좋다.
      // - Task 안에 discovery service 를 통해 즈소를 가져와 리턴하는 코드를 넣는 것도 가능하다.
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

    val client = BlockingSmartClient.create(addressDiscover)
    val response = Try {
      // AddressDiscover.fetch() 로 가져온 주소 중 하나와 조합되어, 최종적으로 아래 URL 을 호출하게 된다.
      // "https://jsonplaceholder.typicode.com" + "/todos/1" ==> https://jsonplaceholder.typicode.com/todos/1
      client.execute(Http("/todos/1").timeout(8000, 8000), retryPolicy)
    }

    response match {
      case Success(smartResponse) =>
        val statusCode = smartResponse.code
        val bodyString = new String(smartResponse.body, scala.io.Codec.UTF8.name)
        println(statusCode)
        println(bodyString)

      case Failure(e) =>
        e.printStackTrace()
    }
  }
}
