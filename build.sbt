name := "smart-client"
organization := "com.github.windbird123.smartclient"
version := "1.0.0-SNAPSHOT"

//scalaVersion := "2.11.12"
scalaVersion := "2.12.10"

scalacOptions := Seq(
  "-encoding",
  "UTF-8",                 // source files are in UTF-8
  "-deprecation",          // warn about use of deprecated APIs
  "-unchecked",            // warn about unchecked type parameters
  "-feature",              // warn about misused language features
  "-language:higherKinds", // allow higher kinded types without `import scala.language.higherKinds`
  "-Ypartial-unification", // allow the compiler to unify type constructors of different arities
  "-language:implicitConversions"
)

libraryDependencies ++= Seq(
  "ch.qos.logback"             % "logback-classic" % "1.2.3",
  "org.scalaj"                 %% "scalaj-http"    % "2.4.2",
  "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.2",
  "dev.zio"                    %% "zio"            % "1.0.4",
  "dev.zio"                    %% "zio-test"       % "1.0.4" % "test",
  "dev.zio"                    %% "zio-test-sbt"   % "1.0.4" % "test",
  "org.scalatest"              %% "scalatest"      % "3.0.5" % "test"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
