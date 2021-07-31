package com.github.windbird123.smartclient.core

import com.typesafe.scalalogging.LazyLogging
import scalaj.http.{HttpRequest, HttpResponse}
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.random.Random

object SmartClient {
  def create(
    addressDiscover: AddressDiscover,
    httpAction: HttpAction = DefaultHttpAction
  ): ZIO[Clock with Blocking with Random, Throwable, SmartClient] =
    for {
      ref     <- Ref.make(Seq.empty[String])
      factory = new AddressFactory(ref, addressDiscover)
      _       <- factory.fetchAndSet() // 최초 한번은 바로 읽어 초기화
      _       <- factory.scheduleUpdate().fork // note fork
    } yield new SmartClient(factory, httpAction)
}

class SmartClient(addressFactory: AddressFactory, httpAction: HttpAction = DefaultHttpAction) extends LazyLogging {
  def execute(
    req: HttpRequest,
    retryPolicy: RetryPolicy
  ): ZIO[Blocking with Clock, Throwable, HttpResponse[Array[Byte]]] =
    execute(req, retryPolicy, retryPolicy.maxRetryNumberToAnotherAddress)

  private def execute(
    req: HttpRequest,
    retryPolicy: RetryPolicy,
    retryLeftToAnotherAddress: Int
  ): ZIO[Blocking with Clock, Throwable, HttpResponse[Array[Byte]]] =
    for {
      chosen                            <- addressFactory.choose(retryPolicy.waitUntilServerIsAvailable)
      request                           = addressFactory.build(chosen, req)
      retryToAnotherAddressAfterSleepMs = retryPolicy.retryToAnotherAddressAfterSleepMs
      maxRetryNumberWhenTimeout         = retryPolicy.maxRetryNumberWhenTimeout
      smartResponse <- httpAction.tryExecute(request, maxRetryNumberWhenTimeout).catchAll { t =>
                        val abnormalAddressExclusion =
                          addressFactory.exclude(Seq(chosen)).when(retryPolicy.excludeFailedAddress)
                        val retryOrFail = if (retryLeftToAnotherAddress > 0) {
                          execute(req, retryPolicy, retryLeftToAnotherAddress - 1)
                            .delay(retryToAnotherAddressAfterSleepMs.millis)
                        } else {
                          ZIO.fail(t)
                        }

                        abnormalAddressExclusion *> retryOrFail
                      }
      worthRetry = retryPolicy.isWorthRetryToAnotherAddress(smartResponse)
      result <- if (worthRetry && retryLeftToAnotherAddress > 0) {
                 UIO(
                   logger.info(
                     s"retry to another address after [$retryToAnotherAddressAfterSleepMs] millis, current=[$chosen], url=[${request.url}], statusCode=[${smartResponse.code}], retried=[${retryPolicy.maxRetryNumberToAnotherAddress - retryLeftToAnotherAddress}]"
                   )
                 ) *>
                   execute(req, retryPolicy, retryLeftToAnotherAddress - 1)
                     .delay(retryToAnotherAddressAfterSleepMs.millis)
               } else {
                 ZIO.succeed(smartResponse)
               }
    } yield result
}
