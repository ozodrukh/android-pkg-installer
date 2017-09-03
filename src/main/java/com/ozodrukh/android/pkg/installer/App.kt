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

typealias OnPackagesFetched = (List<AndroidSourcePackage>) -> Unit

val displayPackages = object : OnPackagesFetched {
    override fun invoke(packages: List<AndroidSourcePackage>) {
        if (packages.isEmpty()) {
            Timber.e("packages-index", null, "no content received")
        } else {
            val packagesMsg = packages.map { "${it.name} - #${it.index}" }.joinToString("\n")
            Timber.d("packages-index", "repository packages(${packages.size}):\n$packagesMsg")
        }
    }
}

fun main(args: Array<String>) {
    Timber.plant(Timber.DebugTree())

    if (args.isEmpty()) {
        Timber.d("android-pkg-installer", "[action] [arguments]...")
        Timber.d("android-pkg-installer", "help - for more information")
    } else {
        val pkgManager = AndroidPackageManager()

        when (args[0]) {
            "search" -> pkgManager.search(args[1]).subscribeBy(onSuccess = displayPackages)
            "list" -> pkgManager.listPackages().subscribeBy(onSuccess = displayPackages)
            "download" -> {
                if (args.size < 2) {
                    Timber.e("android-pkg-installer", null, "(#)package_index\n" +
                            "root=.\t\t-- sources root folder\n" +
                            "tag=latest\t\t-- Tag name")
                    System.exit(0)
                }

                val index = args[1].toInt()

                val options = if (args.size >= 2) {
                    args.slice(2..args.size - 1).associateBy(
                            keySelector = { it.split("=")[0] },
                            valueTransform = { it.split("=")[1] })
                } else {
                    emptyMap()
                }


                pkgManager.findPackageDownloadLink(packageIndex = index, tagName = options["tag"] ?: "master")
                        .flatMap { file -> file.download().map { file } }
                        .flatMap { file -> file.extractArchive(options["root"] ?: ".") }
                        .subscribeBy(onSuccess = {
                            Timber.i("android-pkg-installer", "Download process completed($it)")
                        })
            }
            else -> println("type [help] for documentation")
        }
    }
}

class AndroidPackageManager {
    fun search(packageName: String): Single<List<AndroidSourcePackage>> {
        return listPackages()
                .map { packages ->
                    packages.filter { it.apply { score = name.score(packageName) }.score > 0.3f }
                            .sortedByDescending { it.score }
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

    fun findPackageDownloadLink(packageIndex: Int, tagName: String): Single<AndroidSourcePackage> {
        return listPackages()
                .flatMap { packages ->
                    val requestedPackage = packages[packageIndex]
                    RxCall(requestedPackage.url + "+refs")
                            .map { response ->
                                val elements = Jsoup.parse(response.body()?.string() ?: "", ANDROID_SOURCES)
                                        .body().select("li.RefList-item")

                                Timber.d("package-tags-index", "Searching referenced tag among ${elements.size} tags")
                                return@map elements
                            }
                            .map { elements ->
                                val index = findSatisfyingVersion(elements.map { it.text() }, tagName)

                                if (index >= 0) {
                                    Optional.ofNullable(ANDROID_SOURCES + elements[index].child(0).attr("href"))
                                } else {
                                    Timber.e("package-tags-index", null, "Tag not found")
                                    Optional.empty()
                                }
                            }
                            .flatMap { optionalTag ->
                                if (optionalTag.isPresent) {
                                    RxCall(optionalTag.get())
                                } else {
                                    Single.error(PackageNotFoundException("Package by supplied tag not found"))
                                }
                            }
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

                ProcessBuilder().command("tar", "-xvzf", path, "-C", outputDir.path)
                        .start()
                        .waitFor()
            }
        }
    }
}


class PackageNotFoundException(message: String?) : Exception(message);