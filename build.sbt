// factor out common settings into a sequence
lazy val commonSettings = Seq(
    organization := "org.hirosezouen",
    version      := "1.0.0",
    scalaVersion := "2.12.1"
)

// sbt-native-packager settings
enablePlugins(JavaAppPackaging)

lazy val root = (project in file(".")).
    settings(commonSettings: _*).
    settings(
        // set the name of the project
        name := "EchoClient",

        // Reflect of Ver2.10.0 requires to add libraryDependencies explicitly
        libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,

        // add ScalaTest dependency
        //libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
        //libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "compile,test",

        // add Akka dependency
//        resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/",
        libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.17",
        libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.4.17",

        // add typesafe config dependencies
        libraryDependencies += "com.typesafe" % "config" % "1.3.1",

        // add Logback, SLF4j dependencies
        libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.8",
        libraryDependencies += "ch.qos.logback" % "logback-core" % "1.1.8",
        libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.22",

        // add HZUtil dependency
        libraryDependencies += "org.hirosezouen" %% "hzutil" % "2.1.0",
        // add HZUtil dependency
        libraryDependencies += "org.hirosezouen" %% "hzactor" % "1.1.0",
        // add HZUtil dependency
        libraryDependencies += "org.hirosezouen" %% "hznet" % "1.1.0",

        // sbt-native-packager settings
        executableScriptName := "EchoClient",
        batScriptExtraDefines += """set "APP_CLASSPATH=%APP_CLASSPATH%;conf"""",

        // Avoid sbt warning ([warn] This usage is deprecated and will be removed in sbt 1.0)
        // Current Sbt dose not allow overwrite stabele release created publicLocal task.
        isSnapshot := true,

        // fork new JVM when run and test and use JVM options
        //fork := true,

        // misc...
        parallelExecution in Test := false,
        //logLevel := Level.Debug,
        scalacOptions += "-deprecation",
        scalacOptions += "-feature",
        scalacOptions += "-Xlint",
        scalacOptions += "-Xfatal-warnings"
    )

