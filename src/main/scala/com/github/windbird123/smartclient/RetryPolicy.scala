package com.github.windbird123.smartclient

import scalaj.http.HttpResponse

trait RetryPolicy {
  val waitUntilServerIsAvailable: Boolean = false

  // request 를 보낸 base 주소에서 Timeout 이 발생할 경우 해당 주소로 최대 maxRetryNumberWhenTimeout 번 재시도함
  val maxRetryNumberWhenTimeout: Int = 1

  // 실패한 주소를 pool 에서 제외할 지 여부
  //   - maxRetryNumberWhenTimeout 번 시도했는데도 timeout 으로 끝내 실패할 경우
  //   - SocketTimeoutException 이외의 Exception 이 발생한 경우
  val excludeFailedAddress: Boolean = true

  // request 를 보낸 base 주소가 문제가 있는 것으로 판단될 경우, retryToAnotherAddressAfterSleepMs 후에 주소 pool 에서 random base 주소로 재시도
  // isWorthRetryToAnotherAddress 가 true 일 경우, retryToAnotherAddressAfterSleepMs 후에 주소 pool 에서 random base 주소로 재시도
  val retryToAnotherAddressAfterSleepMs: Long = 10000L

  // smartResponse 를 확인해 isWorthRetryToAnotherAddress 값이 true 로 설정될 경우, 현재 주소는 pool 에서 제외하지 않고 pool 에서 random base 주소를 골라 retryToAnotherAddressAfterSleepMs 후에 재시도
  // http status code 503 처럼 server busy 일 경우 현재 주소는 pool 에서 제외하지 않고 pool 에서 random base 주소를 골라 재시도하는데 사용할 수 있다.
  def isWorthRetryToAnotherAddress(smartResponse: HttpResponse[Array[Byte]]): Boolean = false

  // isWorthRetryToAnotherAddress 가 true 조건일 때, 최대 maxRetryNumberToAnotherAddress 번까지 random base 주소로 재시도
  // maxRetryNumberToAnotherAddress 까지 재시도 했는데 실패일 경우, 최종 결과를 실패로 리턴
  val maxRetryNumberToAnotherAddress: Int = 3
}

object DefaultRetryPolicy extends RetryPolicy
