package VoteTally

import com.google.common.util.concurrent.RateLimiter
import com.mashape.unirest.http.Unirest
import io.prometheus.client.Counter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.URLDecoder


interface Url {
    val address: String
    val maxAge: Long
    val cacheName: String
      get() = java.net.URLEncoder.encode(address, "UTF-8")
}


internal val pageRegex = "(.*)/page-([0-9]+)".toRegex()
internal fun parseCacheUrl(url: String): Url {
    if (url.endsWith("/threadmarks")) {
        return ThreadmarkUrl(url)
    } else if (pageRegex.matches(url)) {
        val groups = pageRegex.match(url)!!.groups
        return PageUrl(groups[1]!!.value, groups[2]!!.value.toInt())
    } else {
        return SomeRandomUrl(url)
    }
}

// TODO: Make the most recent pages last shorter.
data class PageUrl(val base: String, val page: Int): Url {
    override val address = "$base/page-$page"
    override val maxAge = 86400L * 1000
}

data class ThreadmarkUrl(val base: String): Url {
    override val address = "$base/threadmarks"
    override val maxAge = 300L * 1000
}

data class SomeRandomUrl(val url: String): Url {
    override val address = url
    override val maxAge = 300L * 1000
}

/**
 * Wraps the cache directory.
 *
 * The directory format is one file per URL we're caching, with GC and invalidation depending on (respectively)
 * atime and mtime.
 *
 * The URLs are percent-encoded to fit in unix filenames.
 */
object cache {
    // TODO: Flag this.
    private const val cacheDirPath = "/tmp/svcache"
    internal val cacheDir = File(cacheDirPath)
    private const val sizeLimit = 1e+9  // 1GB
    // Xon says 5-6 is the absolute max, but let's be nice.
    private val rateLimiter = RateLimiter.create(2.0)

    // Metrics!
    val cacheHits = Counter.build()
            .name("cache_hits")
            .help("Cache hits")
            .register()
    val cacheMisses = Counter.build()
            .name("cache_misses")
            .help("Cache misses")
            .register()
    val refetches = Counter.build()
            .name("cache_refetch")
            .help("Number of error-triggered refetches executed")
            .register()

    init {
        cacheDir.mkdirs()
        cacheGCThread.start()
    }

    /**
     * Retrieves a URL from cache, and only cache.
     */
    fun getCache(url: Url): Document? {
        try {
            val file = File(cacheDir, url.cacheName)
            return Jsoup.parse(file, "UTF-8", url.address)
        } catch(e: IOException) {
            return null
        }
    }

    private fun populateCache(url: Url, backoff: Int = 4): Document {
        rateLimiter.acquire()
        println("Fetching ${url.address}")
        val contents = Unirest.get(url.address).header("User-Agent", "SVQuestValet").asString().body
        var doc = Jsoup.parse(contents, url.address)
        if (doc.select(".message").count() == 0 && doc.select(".threadmark_item").count() == 0) {
            println("Error while fetching ${url.address}, backing off")
            refetches.inc()
            rateLimiter.acquire(backoff)
            return populateCache(url, backoff * 2)
        }
        // Valid doc. Write it to the cache.
        val file = File(cacheDir, url.cacheName)
        file.writeText(contents, "UTF-8")
        return doc
    }

    /**
     * Retrieves a URL, via HTTP if necessary.
     */
    operator fun get(url: Url): Document {
        val cached = getCache(url)
        if (cached != null) {
            cacheHits.inc()
            return cached
        }
        cacheMisses.inc()
        return populateCache(url)
    }

    fun invalidatePrefix(base: String) {
        val encoded = java.net.URLEncoder.encode(base, "UTF-8")
        for (file in cacheDir.listFiles()) {
            if (file.name.commonPrefixWith(encoded) == encoded) {
                println("Invalidating $base")
                file.delete()
            }
        }
    }
}

object cacheGCThread: Thread() {

    override fun run() {
        println("Starting GC thread")
        while (true) {
            //Thread.sleep(60000)
            val now = System.currentTimeMillis()
            for (file in cache.cacheDir.listFiles()) {
                val url = URLDecoder.decode(file.name, "UTF-8")
                val urlObj = parseCacheUrl(url)
                println("GC: Considering $urlObj")
                val mtime = file.lastModified()
                if (mtime + urlObj.maxAge < now) {
                    println("GC: Deleting $urlObj")
                    file.delete()
                }
            }
            Thread.sleep(60000)
        }
    }
}