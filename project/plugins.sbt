resolvers += Resolver.url("Typesafe Repo", url("http://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)

resolvers += Resolver.url("Typesafe nightlies", url("https://typesafe.artifactoryonline.com/typesafe/ivy-snapshots/"))(Resolver.ivyStylePatterns)

libraryDependencies <+= sbtVersion("org.scala-sbt" % "scripted-plugin" % _)
