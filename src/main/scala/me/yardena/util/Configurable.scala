package me.yardena.util

import com.typesafe.config.{Config, ConfigFactory}

/**
  * Easy access to Typesafe Config library
  *
  * Created by yardena on 2019-08-25 10:16
  */
trait Configurable {

  def config: Config = ConfigFactory.load().resolve()

}
