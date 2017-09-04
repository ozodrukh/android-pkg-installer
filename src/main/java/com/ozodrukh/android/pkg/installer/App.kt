package com.ozodrukh.android.pkg.installer

import io.reactivex.Single
import io.reactivex.rxkotlin.subscribeBy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Pipe
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.util.*

/**
 * @author Ozodrukh
 */

const val ANDROID_SOURCES = "https://android.googlesource.com"
const val TMP_DIR = "/tmp/android_sources_packages"

val http = OkHttpClient.Builder().build()

infix fun OkHttpClient.get(url: String): Request.Builder =
    Request.Builder().url(url).get()

fun Request.Builder.execute(): Response = http.newCall(build()).execute()

fun RxCall(url: String): Single<Response> {
  return Single.fromCallable { (http get url).execute() }
}

typealias AndroidRepository = List<AndroidSourcePackage>;
typealias OnPackagesFetched = (AndroidRepository) -> Unit

val displayPackages = object : OnPackagesFetched {
  override fun invoke(packages: AndroidRepository) {
    if (packages.isEmpty()) {
      Timber.e("packages-index", null, "no content received")
    } else {
      val packagesMsg = packages.map { "${it.name} - #${it.index}" }.joinToString("\n")
      Timber.d("packages-index", "repository packages(${packages.size}):\n$packagesMsg")
    }
  }
}

fun applyFilter(options: Map<String, String>): (AndroidRepository) -> AndroidRepository {
  return { packages: AndroidRepository ->
    val filter = (options["R"] ?: options["filter"] ?: "").run {
      replace("*", "(.+)")
    }

    Timber.d("packages-index", "applying filter($filter)")

    packages.filter { it.name.matches(filter.toRegex()) }
  }
}

fun main(args: Array<String>) {
  Timber.plant(Timber.DebugTree())

  if (args.isEmpty()) {
    Timber.d("android-pkg-installer", "[action] [arguments]...")
    Timber.d("android-pkg-installer", "help - for more information")
  } else {
    val pkgManager = AndroidPackageManager()

    val options = if (args.size > 1) {
      args.filter({ it.contains("=") })
          .associateBy(keySelector = { it.split("=")[0] },
              valueTransform = { it.split("=")[1] })
    } else {
      emptyMap()
    }

    when (args[0]) {
      "search" -> pkgManager.search(args[1])
          .map(applyFilter(options))
          .subscribeBy(onSuccess = displayPackages)

      "list" -> pkgManager.listPackages()
          .map(applyFilter(options))
          .subscribeBy(onSuccess = displayPackages)

      "tags" -> pkgManager.listTags(args[1])
          .subscribeBy(onSuccess = { tagsMap ->
            tagsMap.forEach { entry ->
              Timber.d("android-pkg-installer", "List of ${entry.key} for ${args[1]}:\n" +
                  entry.value.take(5).joinToString("\n"))
            }
          })

      "install" -> {
        if (args.size < 2) {
          Timber.e("android-pkg-installer", null, "(#)package_index\n" +
              "root=.    \t\t-- sources root folder\n" +
              "tag=master\t\t-- Tag name")
          System.exit(0)
        }

        var path = options["root"] ?: ".";

        if (path.startsWith("~")) {
          path = System.getProperty("user.home") + path.substring(1)
        }

        pkgManager.findPackageDownloadLink(packageName = args[1], tagName = options["tag"] ?: "master")
            .flatMap { file -> file.download().map { file } }
            .flatMap { file -> file.extractArchive(path) }
            .subscribeBy(onSuccess = {
              Timber.i("android-pkg-installer", "Download process completed($it)")
            })
      }
      "help" -> println("search - type package name to search index number\n" +
          "list - lists all available packages\n" +
          "install - (#)package_index root=. tag=master downloads & installs package")
      else -> println("type [help] for documentation")
    }
  }
}

class AndroidPackageManager {
  fun search(packageName: String): Single<AndroidRepository> {
    return listPackages()
        .map { packages ->
          packages.filter { it.apply { score = name.score(packageName) }.score > 0.3f }
              .sortedByDescending { it.score }
        }
  }

  fun listTags(packageName: String): Single<Map<String, List<String>>> {
    Timber.d("packages-index", "loading tags...")

    return RxCall(ANDROID_SOURCES + "/$packageName/+refs")
        .map { response ->
          val body = Jsoup.parse(response.body()?.string() ?: "", ANDROID_SOURCES).body()
          val tagsMap = mutableMapOf<String, List<String>>()
          body.select("div.RefList").forEach {
            tagsMap[it.select("h3").text()] = it.select("ul li").map { it.text() }
          }
          tagsMap
        }
  }

  fun listPackages(): Single<List<AndroidSourcePackage>> {
    Timber.d("packages-index", "loading packages...")

    return RxCall(ANDROID_SOURCES)
        .doOnError { error ->
          Timber.e("packages-index", error, "i/o exception while loading index")
        }
        .doOnSuccess { response ->
          val receivedAt = response.receivedResponseAtMillis() - response.sentRequestAtMillis()
          val state = if (response.isSuccessful) "ok" else "failed"

          Timber.d("packages-index", "responded in ${receivedAt}ms " +
              "with status = ${response.code()}($state)")
        }
        .map(parsePackagesList())
  }

  fun findPackageDownloadLink(packageName: String, tagName: String): Single<AndroidSourcePackage> {
    val requestedPackage = AndroidSourcePackage(
        name = packageName,
        description = "",
        url = ANDROID_SOURCES + "/$packageName",
        index = -1
    )

    return RxCall(requestedPackage.url + "/+/$tagName")
        .map { response ->
          val links = Jsoup.parse(response.body()?.string() ?: "", ANDROID_SOURCES)
              .body()
              .select("a[href$='tar.gz']")

          if (links.isNotEmpty()) {
            Optional.of(ANDROID_SOURCES + links.first().attr("href"))
          } else {
            Optional.empty<String>()
          }
        }
        .map { requestedPackage.apply { packageUrl = it } }

  }

  fun findSatisfyingVersion(versions: List<String>, versionName: String): Int {
    return versions.indexOf(versionName)
  }

  fun parsePackagesList(): (Response) -> List<AndroidSourcePackage> {
    return { response ->
      val source = response.body()?.string() ?: ""
      Jsoup.parse(source, ANDROID_SOURCES).body()
          .select("div.RepoList > a.RepoList-item")
          .mapIndexed { index, pkg ->
            AndroidSourcePackage(
                url = ANDROID_SOURCES + pkg.attr("href"),
                name = pkg.select(".RepoList-itemName").text(),
                description = pkg.select(".RepoList-itemDescription").text(),
                index = index
            )
          }
    }
  }
}

data class AndroidSourcePackage(
    val name: String,
    val description: String?,
    val url: String,
    val index: Int,
    var packageUrl: Optional<String> = Optional.empty(),
    var score: Double = 0.0) {

  fun tmpArchiveFile(): File {
    return File(TMP_DIR, "$name.tar.gz")
  }

  fun createMissingDirectories(): Boolean {
    tmpArchiveFile().parentFile.run {
      if (!exists() && !mkdirs()) {
        throw IOException("Coun't create directory ${path}")
      }
      return exists()
    }
  }

  fun download(): Single<Int> {
    return if (!packageUrl.isPresent) {
      // Raise error when package archive is missing
      Single.error(PackageNotFoundException("package archive (download url) not found"))
    } else {
      Single.fromCallable {
        // If directory is missing ensure it created
        createMissingDirectories()

        ProcessBuilder().command("wget", "-c", packageUrl.get(),
            "-O", tmpArchiveFile().path)
            .inheritIO()
            .start()
            .waitFor()
      }
    }
  }

  fun extractArchive(rootDirectory: String): Single<Int> {
    tmpArchiveFile().run {
      return Single.fromCallable {
        if (!exists())
          throw PackageNotFoundException("Package was not downloaded")

        val outputDir = File(rootDirectory, this@AndroidSourcePackage.name)
        if (!outputDir.exists()) {
          outputDir.mkdirs()
        }

        Timber.d("android-pkg-installer", "Extracting at $outputDir")

        ProcessBuilder().command("tar", "-xvzf", path, "-C", outputDir.path)
            .inheritIO()
            .start()
            .waitFor()
      }
    }
  }
}


class PackageNotFoundException(message: String?) : Exception(message);