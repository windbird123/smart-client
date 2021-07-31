package com.github.windbird123.smartclient.core

import scalaj.http.{Http, HttpRequest, HttpResponse}
import zio._
import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock

import java.net.{SocketException, SocketTimeoutException}
import java.util.concurrent.atomic.AtomicInteger

object SmartClientTest extends DefaultRunnableSpec {
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("SmartClient Test")(executeSuite)

  val successHttpAction: HttpAction = new HttpAction {
    override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
      Task.succeed(HttpResponse[Array[Byte]]("success".getBytes(io.Codec.UTF8.name), 200, Map.empty))
  }

  val executeSuite = suite("execute")(
    testM("waitUntilServerIsAvailable=false 이고 사용 가능한 주소가 없을때 NoAvailableAddressException 로 fail 되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] = Task.succeed(Seq.empty[String])
      }

      val retryPolicy = new RetryPolicy {
        override val waitUntilServerIsAvailable: Boolean = false
      }

      for {
        client <- SmartClient.create(addressDiscover, successHttpAction)
        failed <- client.execute(Http("/some/path"), retryPolicy).flip
      } yield assert(failed)(isSubtype[NoAvailableAddressException](anything))
    },
    /**
     * 주의: AddressDiscover.fetch() 를 구현할때 아래와 같은 형태로 구현하면 안된다.
     * if (..) Task(..) else Task(..)
     * ZIO schedule 은 effect 만을 주기적으로 수행한다 !!!
     */
    testM("waitUntilServerIsAvailable=true 이면, 사용 가능한 주소가 있을 때 까지 기다렸다 수행하도록 한다.") {
      val addressDiscover = new AddressDiscover {
        val tryCount                 = new AtomicInteger(0)
        override val periodSec: Long = 1L

        override def fetch(): Task[Seq[String]] = Task {
          if (tryCount.getAndIncrement() < 3) Seq.empty[String] else Seq("http://a.b.c")
        }
      }

      val retryPolicy = new RetryPolicy {
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L
        override val waitUntilServerIsAvailable: Boolean     = true
      }

      for {
        client        <- SmartClient.create(addressDiscover, successHttpAction)
        resFork       <- client.execute(Http("/some/path"), retryPolicy).fork
        _             <- TestClock.adjust(5.seconds)
        smartResponse <- resFork.join

      } yield assert(smartResponse.code)(equalTo(200)) && assert(smartResponse.body)(
        equalTo("success".getBytes(io.Codec.UTF8.name))
      )
    },
    testM("SocketException 을 발생시키는 bad address 가 있으면, 이를 제거해 나가면서 request 를 시도한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] =
          Task.succeed(
            Seq(
              "http://bad1",
              "http://bad2",
              "http://bad3",
              "http://bad4",
              "http://bad5",
              "http://bad6",
              "http://bad7",
              "http://bad8",
              "http://bad9",
              "http://good"
            )
          )
      }

      val retryPolicy = new RetryPolicy {
        override val maxRetryNumberWhenTimeout: Int          = 1
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L
        override val maxRetryNumberToAnotherAddress: Int     = 10
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
          for {
            _       <- ZIO.when(!r.url.startsWith("http://good"))(Task.fail(new SocketException("bad")))
            success <- Task.succeed(HttpResponse[Array[Byte]]("success".getBytes(io.Codec.UTF8.name), 200, Map.empty))
          } yield success
      }

      for {
        client        <- SmartClient.create(addressDiscover, httpAction)
        resFork       <- client.execute(Http("/some/path"), retryPolicy).fork
        _             <- TestClock.adjust(100.seconds)
        smartResponse <- resFork.join
      } yield assert(smartResponse.code)(equalTo(200)) && assert(smartResponse.body)(
        equalTo("success".getBytes(io.Codec.UTF8.name))
      )
    },
    testM(
      "HttpAction 의 tryExecute 의 결과가 계속 SocketTimeoutException 일 경우, maxRetryNumberToAnotherAddress 횟수 만큼 재시도 하다 SocketTimeoutException 로 fail 되어야 한다."
    ) {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] =
          Task.succeed(Seq("http://bad1", "http://bad2", "http://bad3", "http://bad4", "http://bad5"))
      }

      val retryPolicy = new RetryPolicy {
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L
        override val maxRetryNumberToAnotherAddress: Int     = 3
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
          ZIO.fail(new SocketTimeoutException())
      }

      for {
        client  <- SmartClient.create(addressDiscover, httpAction)
        resFork <- client.execute(Http("/some/path"), retryPolicy).fork
        _       <- TestClock.adjust(100.seconds)
        failed  <- resFork.join.flip
      } yield assert(failed)(isSubtype[SocketTimeoutException](anything))
    },
    testM(
      "HttpAction 의 tryExecute 의 결과가 계속 SocketTimeoutException 되어 모든 주소가 제외되어 더 이상 사용할 주소가 없으면 NoAvailableAddressException 으로 fail 되어야 한다."
    ) {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] =
          Task.succeed(Seq("http://bad1", "http://bad2"))
      }

      val retryPolicy = new RetryPolicy {
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L
        override val maxRetryNumberToAnotherAddress: Int     = 3
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
          ZIO.fail(new SocketTimeoutException())
      }

      for {
        client  <- SmartClient.create(addressDiscover, httpAction)
        resFork <- client.execute(Http("/some/path"), retryPolicy).fork
        _       <- TestClock.adjust(100.seconds)
        failed  <- resFork.join.flip
      } yield assert(failed)(
        isSubtype[NoAvailableAddressException](anything)
      )
    },
    testM("isWorthRetryToAnotherAddress 에서 설정된 정책대로 retry 가 잘 수행되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] =
          Task.succeed(
            Seq(
              "http://bad1",
              "http://bad2",
              "http://bad3",
              "http://bad4",
              "http://bad5",
              "http://bad6",
              "http://bad7",
              "http://bad8",
              "http://bad9",
              "http://good"
            )
          )
      }

      val retryPolicy = new RetryPolicy {
        override val maxRetryNumberWhenTimeout: Int          = 1
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L

        override def isWorthRetryToAnotherAddress(smartResponse: HttpResponse[Array[Byte]]): Boolean =
          if (smartResponse.code == 503) true else false

        override val maxRetryNumberToAnotherAddress: Int = Integer.MAX_VALUE
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] = UIO {
          if (r.url.startsWith("http://good")) {
            HttpResponse[Array[Byte]]("success".getBytes(io.Codec.UTF8.name), 200, Map.empty)
          } else {
            HttpResponse[Array[Byte]](Array.empty[Byte], 503, Map.empty)
          }
        }
      }

      for {
        client        <- SmartClient.create(addressDiscover, httpAction)
        resFork       <- client.execute(Http("/some/path"), retryPolicy).fork
        _             <- TestClock.adjust(100.seconds)
        smartResponse <- resFork.join
      } yield assert(smartResponse.code)(equalTo(200)) && assert(smartResponse.body)(
        equalTo("success".getBytes(io.Codec.UTF8.name))
      )
    },
    testM("maxRetryNumberToAnotherAddress 까지 시도했는데도 실패하면, 최종 결과는 실패로 처리되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] =
          Task.succeed(
            Seq(
              "http://bad1"
            )
          )
      }

      val retryPolicy = new RetryPolicy {
        override val maxRetryNumberWhenTimeout: Int          = 1
        override val retryToAnotherAddressAfterSleepMs: Long = 1000L

        override def isWorthRetryToAnotherAddress(smartResponse: HttpResponse[Array[Byte]]): Boolean =
          if (smartResponse.code == 503) true else false

        override val maxRetryNumberToAnotherAddress: Int = 5
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] = UIO {
          if (r.url.startsWith("http://good")) {
            HttpResponse[Array[Byte]]("success".getBytes(io.Codec.UTF8.name), 200, Map.empty)
          } else {
            HttpResponse[Array[Byte]](Array.empty[Byte], 503, Map.empty)
          }
        }
      }

      for {
        client        <- SmartClient.create(addressDiscover, httpAction)
        resFork       <- client.execute(Http("/some/path"), retryPolicy).fork
        _             <- TestClock.adjust(100.seconds)
        smartResponse <- resFork.join
      } yield assert(smartResponse.code)(equalTo(503))
    },
    testM("AddressDiscover.fetch() 가 실패하면, 기존 주소 그대로 유지되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        val tryCount = new AtomicInteger(0)

        override val periodSec: Long = 1L

        // 최초 설정 이후에는 계속 주소 가져오는 것을 실패하도록 함
        override def fetch(): Task[Seq[String]] =
          for {
            count <- UIO(tryCount.getAndIncrement())
            success <- ZIO.ifM(UIO(count >= 2))(
                        Task.fail(new Exception("some exception !!!")),
                        ZIO.succeed(Seq("http://good"))
                      )
          } yield success
      }

      val retryPolicy = new RetryPolicy {
        override val waitUntilServerIsAvailable: Boolean = false
      }

      for {
        client        <- SmartClient.create(addressDiscover, successHttpAction)
        _             <- TestClock.adjust(5.seconds)
        smartResponse <- client.execute(Http("/some/path"), retryPolicy)
      } yield assert(smartResponse.code)(equalTo(200))
    },
    testM("excludeFailedAddress=false 이면, timeout 이 maxRetryNumberWhenTimeout 번 발생하더라도 해당 주소는 주소풀에서 유지되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] = Task.succeed(Seq("http://bad"))
      }

      val retryPolicy = new RetryPolicy {
        override val waitUntilServerIsAvailable: Boolean = false
        override val excludeFailedAddress: Boolean       = false
        override val maxRetryNumberToAnotherAddress: Int = 0
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
          ZIO.fail(new SocketTimeoutException())
      }

      for {
        client <- SmartClient.create(addressDiscover, httpAction)
        _      <- client.execute(Http("/some/path"), retryPolicy).flip // 풀에 주소는 여전히 존재함
        secondFail <- client
                       .execute(Http("/some/path"), retryPolicy)
                       .flip // 해당 주소에 대한 요청 결과는 SocketTimeoutException 이어야 함
      } yield assert(secondFail)(isSubtype[SocketTimeoutException](anything))
    },
    testM("excludeFailedAddress=true 이면, timeout 이 maxRetryNumberWhenTimeout 번 발생하면 해당 주소는 주소풀에서 제거 되어야 한다.") {
      val addressDiscover = new AddressDiscover {
        override def fetch(): Task[Seq[String]] = Task.succeed(Seq("http://bad"))
      }

      val retryPolicy = new RetryPolicy {
        override val waitUntilServerIsAvailable: Boolean = false
        override val excludeFailedAddress: Boolean       = true
        override val maxRetryNumberToAnotherAddress: Int = 0
      }

      val httpAction = new HttpAction {
        override def tryExecute(r: HttpRequest, maxRetryNumberWhenTimeout: Int): Task[HttpResponse[Array[Byte]]] =
          ZIO.fail(new SocketTimeoutException())
      }

      for {
        client     <- SmartClient.create(addressDiscover, httpAction)
        _          <- client.execute(Http("/some/path"), retryPolicy).flip // timeout 실패로 풀에서 주소가 삭제됨
        secondFail <- client.execute(Http("/some/path"), retryPolicy).flip // waitUntilServerIsAvailable=false 인데..
      } yield assert(secondFail)(isSubtype[NoAvailableAddressException](anything))
    }
  )
}
