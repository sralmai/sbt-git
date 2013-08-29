import bintray.Keys._

sbtPlugin := true

name := "sbt-git"

organization := "com.typesafe.sbt"

version := "0.6.3"

libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit.pgm" % "2.2.0.201212191850-r"

publishMavenStyle := false

seq(bintraySettings:_*)

seq(bintrayPublishSettings:_*)

repository in bintray := "sbt-plugins"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))
