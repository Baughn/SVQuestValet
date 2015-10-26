package VoteTally

import com.google.common.cache.CacheBuilder
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.MetricsServlet
import io.prometheus.client.hotspot.DefaultExports
import org.json.JSONArray
import org.json.JSONObject
import spark.Spark.externalStaticFileLocation
import spark.Spark.staticFileLocation
import spark.Spark.get
import spark.Spark.port
import sun.security.krb5.internal.crypto.Nonce
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit


val pool = ForkJoinPool()

// TODO: Subclass and wrap the task-adding functions, use counters.
val poolQueued = Gauge.build()
        .name("pool_queued")
        .labelNames("where")
        .help("Tasks submitted but not yet started")
        .register()
val poolLoad = Gauge.build()
        .name("pool_load")
        .help("Running # of pool threads")
        .register()

val fetches = CacheBuilder.newBuilder()
    .expireAfterAccess(5, TimeUnit.MINUTES)
    .build<Int, PostsFetch>()


fun main(args: Array<String>) {
    // TODO: Parse the port from a flag.
    port(1234)
    externalStaticFileLocation("src/main/resources/static")
    staticFileLocation("static")

    val metrics = MetricsServlet()
    DefaultExports.initialize()
    get("/metrics", { req, res ->
        poolQueued.labels("global").set(pool.queuedSubmissionCount.toDouble())
        poolQueued.labels("threads").set(pool.queuedTaskCount.toDouble())
        poolLoad.set(pool.runningThreadCount.toDouble())
        metrics.service(req.raw(), res.raw())
    })

    get("/StartFetch", {req, res ->
        val base = req.queryParams("base")
        val page = req.queryParams("page").toInt()
        val post = req.queryParams("post").toInt()
        val force = req.queryParams("force").toBoolean()
        val title = req.queryParams("title")
        val id = Nonce.value()
        fetches.put(id, PostsFetch(Threadmark(base, page, post, title), force).apply { pool.execute(this) })
        JSONObject().apply {
            put("id", id)
        }
    })

    get("/PollFetch", {req, res ->
        val id = req.queryParams("id").toInt()
        val fetch = fetches.getIfPresent(id)
        JSONObject().apply done@ {
            if (fetch == null) {
                put("error", "No such fetch ID")
                return@done
            }
            put("id", id)
            put("progressAt", fetch.progress)
            put("progressTotal", fetch.progressTotal)
            if (fetch.isDone) {
                val posts = fetch.join()
                val election = Election(posts)
                put("result", JSONArray().apply {
                    while (!election.summary.isEmpty()) {
                        val it = election.summary.removeLast()
                        put(JSONObject().apply {
                            put("weight", it.weight)
                            put("text", it.text)
                            put("votes", JSONArray().apply {
                                it.posts.forEach {
                                    put(JSONObject().apply {
                                        put("author", it.author)
                                        put("href", it.href)
                                    })
                                }
                            })
                        })
                    }
                })
            }
        }
    })

    get("/Threadmarks", { req, res ->
        val url = req.queryParams("url")
        val threadmarks = Threadmarks(url)

        JSONArray().apply {
            threadmarks.threadmarks.reversed().forEach {
                put(JSONObject().apply {
                    put("base", it.base)
                    put("page", it.page)
                    put("title", it.title)
                    put("post", it.post)
                })
            }
        }
    })
}
