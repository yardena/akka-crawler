scalaVersion := "2.12.9"

scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation")

val akkaVersion     = "2.5.25"
val akkaHttpVersion = "10.1.9"
val slf4jVersion    = "1.7.21"

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-actor"               % "2.5.25",
  "com.typesafe.akka"         %% "akka-stream"              % "2.5.25",
  "com.typesafe.akka"         %% "akka-slf4j"               % "2.5.25",
  
  "com.typesafe.akka"         %% "akka-persistence"         % "2.5.25",
  "org.fusesource.leveldbjni"  % "leveldbjni-all"           % "1.8",
  "com.twitter"               %% "chill-akka"               % "0.9.2",
  
  "com.typesafe.akka"         %% "akka-http"                % "10.1.9",
  
  "ch.qos.logback"             % "logback-classic"          % "1.1.7",
  "org.slf4j"                  % "slf4j-api"                % "1.7.21",
  "org.slf4j"                  % "log4j-over-slf4j"         % "1.7.21",

  "org.jsoup"                  % "jsoup"                    % "1.12.1",
  
  "org.picoworks"             %% "pico-hashids"             % "4.4.141",

  "nl.grons"                  %% "metrics4-scala"           % "4.0.8",
  "nl.grons"                  %% "metrics4-akka_a25"        % "4.0.8"

//------------------------------------ T E S T ----------------------------------------------
//  "org.scalatest"            %% "scalatest"                 % "3.0.5"       % Test,
//  "org.mockito"              %% "mockito-scala"             % "1.1.3"       % Test,
//  "com.typesafe.akka"        %% "akka-testkit"              % "2.5.25"      % Test,
//  "com.typesafe.akka"        %% "akka-stream-testkit"       % "2.5.25"      % Test,
//  "com.typesafe.akka"        %% "akka-http-testkit"         % "10.1.9"      % Test
)

test in assembly := {}
mainClass in assembly := Some("me.yardena.crawler.CrawlerApp")

name := "akka-crawler"
organization := "me.yardena"
version := "0.0.1"

