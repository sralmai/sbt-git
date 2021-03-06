package com.typesafe.sbt

import sbt._
import Keys._
import git.{ ConsoleGitRunner, JGitRunner }
import com.typesafe.sbt.git.GitRunner
import com.typesafe.sbt.git.ReadableGit
import com.typesafe.sbt.git.DefaultReadableGit

/** This plugin has all the basic 'git' functionality for other plugins. */
object SbtGit extends Plugin {
  object GitKeys {
    // Read-only git settings and values for use in other build settings.
    // Note: These are all grabbed using jgit currently.
    val gitReader = SettingKey[ReadableGit]("git-reader", "This gives us a read-only view of the git repository.")
    val gitBranch = SettingKey[Option[String]]("git-branch", "Target branch of a git operation")
    val gitCurrentBranch = SettingKey[String]("git-current-branch", "The current branch for this project.")
    val gitCurrentTags = SettingKey[Seq[String]]("git-current-tags", "The tags associated with this commit.")
    val gitHeadCommit = SettingKey[Option[String]]("git-head-commit", "The commit sha for the top commit of this project.")

    // A Mechanism to run Git directly.
    val gitRunner = TaskKey[GitRunner]("git-runner", "The mechanism used to run git in the current build.")

    // Keys associated with setting a version number.
    val gitTagToVersionNumber = SettingKey[String => Option[String]]("git-tag-to-version-number", "Converts a git tag string to a version number.")
    val baseVersion = SettingKey[String]("base-version", "The base version number which we will append the git version to.")
    val versionProperty = SettingKey[String]("version-property", "The system property that can be used to override the version number.  Defaults to `project.version`.")

    // The remote repository we're using.
    val gitRemoteRepo = SettingKey[String]("git-remote-repo", "The remote git repository associated with this project")
  }

  object GitCommand {
    val action: (State, Seq[String]) => State = { (state, args) =>
      val extracted = Project.extract(state)
      val (state2, runner) = extracted.runTask(GitKeys.gitRunner, state)
      val dir = extracted.get(baseDirectory)
      val result = runner(args:_*)(dir, state2.log)
      // TODO - Best way to print to console?
      println(result)
      state2
    }

    // <arg> is the suggestion printed for tab completion on an argument
    val command: Command = Command.args("git", "<args>")(action)

    @scala.annotation.tailrec
    private def isGitRepo(dir: File): Boolean = {
      if (dir.listFiles().map(_.getName).contains(".git")) true
      else {
        val parent = dir.getParentFile
        if (parent == null) false
        else isGitRepo(parent)
      }
    }

    val prompt: State => String = { state =>
      val extracted = Project.extract(state)
      val reader = extracted get GitKeys.gitReader
      val dir = extracted get baseDirectory
      val name = extracted get Keys.name
      if (isGitRepo(dir)) {
        val branch = reader.withGit(_.branch)
        name + "(" + branch + ")> "
      } else {
        name + "> "
      }
    }
  }

  import GitKeys._
  // Use SBT 0.12's features for advantage!
  // We store our global build settings just once.
  override val projectSettings = Seq(
    gitReader in ThisBuild <<= (baseDirectory in ThisBuild) apply (new DefaultReadableGit(_)),
    gitRunner in ThisBuild := ConsoleGitRunner,
    gitHeadCommit in ThisBuild <<= (gitReader in ThisBuild) apply { (reader) =>
      // TODO - Figure out logging!
      reader.withGit(_.headCommitSha)
    },
    gitCurrentTags in ThisBuild <<= (gitReader in ThisBuild) apply { (reader) =>
      reader.withGit(_.currentTags)
    },
    gitCurrentBranch in ThisBuild <<= (gitReader in ThisBuild) apply { (reader) =>
      // TODO - Make current branch an option?
      Option(reader.withGit(_.branch)) getOrElse ""
    }
  )
  override val settings = Seq(
    // Input task to run git commands directly.
    commands += GitCommand.command
  )
  /** A Predefined setting to use JGit runner for git. */
  def useJGit: Setting[_] = gitRunner in ThisBuild := JGitRunner

  /** Adapts the project prompt to show the current project name *and* the current git branch. */
  def showCurrentGitBranch: Setting[_] =
    shellPrompt := GitCommand.prompt


  /** Uses git to control versioning.
   *
   * Versioning runs through the following:
   *
   * 1. Looks at version-property settings, and checks the sys.props to see if this has a value.
   * 2. Looks at the project tags.  The first to match the `gitTagToVersionNumberSetting` is used to assign the version.
   * 3. if we have a head commit, we attach this to the base version setting "<base-version>.<git commit sha>"
   * 4. We append the current timestamp tot he base version: "<base-version>.<timestamp>"
   */
  def versionWithGit: Seq[Setting[_]] =
    Seq(
        gitTagToVersionNumber in ThisBuild := git.defaultTagByVersionStrategy,
        baseVersion in ThisBuild := "1.0",
        versionProperty in ThisBuild := "project.version",
        version in ThisBuild <<= (git.versionProperty, git.baseVersion, git.gitHeadCommit, git.gitCurrentTags, git.gitTagToVersionNumber) apply git.makeVersion
    )


  /** A holder of keys for simple config. */
  object git {
    val remoteRepo = GitKeys.gitRemoteRepo
    val branch = GitKeys.gitBranch
    val runner = GitKeys.gitRunner in ThisBuild
    val gitHeadCommit = GitKeys.gitHeadCommit in ThisBuild
    val gitCurrentTags = GitKeys.gitCurrentTags in ThisBuild
    val gitCurrentBranch = GitKeys.gitCurrentBranch in ThisBuild
    val gitTagToVersionNumber = GitKeys.gitTagToVersionNumber in ThisBuild
    val baseVersion = GitKeys.baseVersion in ThisBuild
    val versionProperty = GitKeys.versionProperty in ThisBuild

    def defaultTagByVersionStrategy(tag: String): Option[String] = {
      if(tag matches "v[0-9].*") Some(tag drop 1)
      else None
    }
    // Simple fall-through on how to define the project version.
    // TODO - Split this to use multiple settings, perhaps.
    def makeVersion(versionProperty: String, baseVersion: String, headCommit: Option[String], currentTags: Seq[String], releaseTagVersion: String => Option[String]): String = {
      // The version string passed in via command line settings, if desired.
      def overrideVersion = Option(sys.props(versionProperty))
      // Version string that is computed from tags.
      def releaseVersion(): Option[String] = {
        val releaseVersions =
          for {
            tag <- currentTags
            version <- releaseTagVersion(tag)
          } yield version
        releaseVersions.headOption
      }
      // Version string that just uses the commit version.
      def commitVersion: Option[String] =
         headCommit map (sha => baseVersion + "-" + sha + "-SNAPSHOT")
      // Version string that just uses the full timestamp.
      def datedVersion: String = {
        val df = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss")
        df setTimeZone java.util.TimeZone.getTimeZone("GMT")
        baseVersion + "-" + (df format new java.util.Date) + "-SNAPSHOT"
      }
      //Now we fall through the potential version numbers...
      overrideVersion  orElse releaseVersion orElse commitVersion getOrElse datedVersion
    }
  }
}
