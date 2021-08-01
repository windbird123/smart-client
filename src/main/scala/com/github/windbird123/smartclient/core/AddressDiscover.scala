package com.github.windbird123.smartclient.core

import zio._

trait AddressDiscover {
  val periodSec: Long = 300L
  def fetch(): Task[Seq[String]]
}
