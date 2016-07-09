package VoteTally

import com.google.common.collect.MinMaxPriorityQueue
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Whitelist
import java.net.URL
import java.util.*
import java.util.concurrent.RecursiveTask


fun baseUrl(s: String): String {
    val url = URL(s)
    if (url.host != "forums.sufficientvelocity.com" && url.host != "forums.spacebattles.com")
        throw IllegalArgumentException("Cannot parse host ${url.host}")
    val dirs = url.path.splitToSequence('/')
    if (dirs.count() < 3 || dirs.elementAt(1) != "threads" || dirs.elementAt(2).length == 0)
        throw IllegalArgumentException("Can only scrape thread URLs, not ${url.path}")
    return "https://${url.host}/${dirs.elementAt(1)}/${dirs.elementAt(2)}"
}


data class VoteSet(val text: String, val weight: Double, val posts: List<Post>): Comparable<VoteSet> {
    override fun compareTo(other: VoteSet): Int {
        if (weight - other.weight < 0) return -1
        if (weight - other.weight > 0) return 1
        return 0
    }
}

class Election(posts: List<Post>) {
    private val voteRegex = "([- ])*\\[X\\].*".toRegex(RegexOption.IGNORE_CASE)
    private val deferRegex = "\\[X\\]\\s*plan\\s+(\\w+).*".toRegex(RegexOption.IGNORE_CASE)
    private val heuristicDeferRegex = "^\\[X\\]\\s+(\\w+).*".toRegex(RegexOption.IGNORE_CASE)

    val errors = ArrayList<String>()
    // Author to text
    val votes = HashMap<String, String>()
    // Author to post
    val votePost = HashMap<String, Post>()
    // Votes, overall. This is probably what you want.
    val summary = MinMaxPriorityQueue.create<VoteSet>()

    init {
        // First pass: Expand all the votes, discard all-but-last.
        posts.forEach {
            val text = voteText(it)
            if (text.isNotEmpty()) {
                votes[it.author] = parseVote(text)
                votePost[it.author] = it
            }
        }
        // Second pass: Summarize votes.
        // TODO: Support parsing weighted votes.
        // TODO: Support instant runoff, or something.
        val byText = HashMap<String, VoteSet>()
        for ((author, text) in votes) {
            if (text in byText) {
                val old = byText[text]!!
                byText[text] = VoteSet(text, old.weight + 1, ArrayList<Post>(old.posts).apply { add(votePost[author]!!) })
            } else {
                byText[text] = VoteSet(text, 1.0, ArrayList<Post>().apply { add(votePost[author]!!) })
            }
        }
        summary.addAll(byText.values)
    }

    private fun parseVote(text: List<String>): String {
        return StringBuilder().apply {
            text.forEach done@ {
                append("\n")
                // Is this a "plan" vote?
                val defer = deferRegex.find(it)
                if (defer != null) {
                    val target = defer.groups[1]!!.value
                    if (target in votes) {
                        append(votes[target])
                    } else {
                        errors.add("Bad deferred vote: $it")
                        append(it)
                    }
                    return@done
                }
                // Ok, how about a heuristic deferred vote?
                // This regex will match just about anything.
                val heuristicDefer = heuristicDeferRegex.find(it)
//                println(heuristicDefer != null)
//                println(it)
                if (heuristicDefer != null) {
                    val target = heuristicDefer.groups[1]!!.value
                    if (target in votes) {
                        append(votes[target])
                        return@done
                    }
                }
                // Guess it's just a standard vote.
                append(it)
            }
        }.toString()
    }

    /**
     * Extract only the parts of a post concerned with voting.
     */
    private fun voteText(post: Post): List<String> {
        val lines = post.content.split("\n")
        return lines.filter {
//            println(it)
//            println(voteRegex.matches(it))
            voteRegex.matches(it)
        }.map {
            it.replace("&nbsp;", " ").trim()
        }
    }
}


data class Threadmark(val base: String, val page: Int, val post: Int, val title: String)

data class Post(val href: String, val content: String, val post: Int, val author: String)


private val postRegex = "threads/[^/]+/(page-[0-9]+)?#post-([0-9]+)".toRegex()
class Threadmarks(url: String) {

    val cleanUrl = baseUrl(url)
    val doc = cache[ThreadmarkUrl(cleanUrl)]

    val threadmarks = doc.select(".threadmarkItem a").map {
        val post = postRegex.find(it.attr("href"))
        if (post == null) {
            println("Bad threadmark: ${it.attr("href")}")
            Threadmark("", -1, -1, it.text())
        } else {
            val page = (post.groups.get(1)?.value ?: "page-1").removePrefix("page-")
            val postId = post.groups.get(2)!!.value
            Threadmark(cleanUrl, page.toInt(), postId.toInt(), it.text())
        }
    }
}


/**
 * Fetches all the posts between one threadmark and the next.
 */
class PostsFetch(val start: Threadmark, val force: Boolean): RecursiveTask<List<Post>>() {
    val threadmarks = Threadmarks(start.base)
    var progress = 0
    var progressTotal = 0

    override fun compute(): List<Post> {
        if (force) {
            cache.invalidatePrefix(start.base)
        }
        val lastPage: Int
        val postMarks = threadmarks.threadmarks.dropWhile { it.post != start.post }.drop(1)
        val stopMark: Threadmark?
        // Do we stop in the middle, or read all of it?
        if (postMarks.isEmpty()) {
            stopMark = null
            lastPage = pageCount
        } else {
            stopMark = postMarks.first()
            lastPage = stopMark.page
        }
        // Well, go ahead and fetch them.
        val posts = ArrayList<Post>((lastPage - start.page) * 25)
        val pages = (start.page..lastPage).map {
            progressTotal++
            PageFetch(PageUrl(start.base, it)).apply { fork() }
        }
        pages.forEachIndexed { i, fetch ->
            val pagePosts: List<Post>
            if (i == 0) {
                pagePosts = fetch.join().dropWhile { start.post != it.post }
            } else {
                pagePosts = fetch.join()
            }
            if (stopMark == null) {
                posts.addAll(pagePosts)
            } else {
                posts.addAll(pagePosts.takeWhile {
                    stopMark.post != it.post
                })
            }
            progress++
        }
        return posts
    }

    val pageCount: Int by lazy {
        val doc = cache[PageUrl(start.base, 1)]
        doc.select(".PageNav").attr("data-last").toInt()
    }

}

/**
 * Fetches a single page.
 */
class PageFetch(val url: PageUrl): RecursiveTask<List<Post>>() {
    override fun compute(): List<Post> {
        val page = cache[url]
        val messages = page.select(".message")
        if (messages.count() == 0) {
            println("Error! Retrying...")
            return compute()
        }

        val baseHref = page.head().select("base").attr("href")

        return messages.map {
            // Clean up the text.
            // My kingdom for some bbcode~
            val raw = it.select(".messageText").single()
            raw.children().forEach {
                if (it.hasClass("bbCodeQuote")) it.remove()
            }
            val prettyPrinted = Jsoup.clean(raw.html(), "", Whitelist.none().addTags("br"), Document.OutputSettings().prettyPrint(true))
                    .replace("<br> ", "")
            val href = baseHref + it.select(".hashPermalink").single().attr("href")
            val author = it.attr("data-author")
            val post = it.attr("id").removePrefix("post-").toInt()
            Post(content = prettyPrinted,
                    post = post,
                    author = author,
                    href = href)
        }
    }

    private fun getThreadmark(it: Element): String? {
        if (it.hasClass("hasThreadmark")) {
            val title = it.select(".threadmarker .label").text()
            return title.removePrefix("Threadmark: ")
        } else {
            return null
        }
    }
}


//fun main(args: Array<String>) {
//    val url = "https://forums.sufficientvelocity.com/threads/stand-still-stay-silent.22848/page-7"
//    val pool = ForkJoinPool()
//    val marks = Threadmarks(url)
//    //    println(marks.threadmarks)
//    val mark = marks.threadmarks.last()
//    val posts = pool.invoke(PostsFetch(mark))
//    println(posts)
//
//    println(posts.count())
//    val election = Election(posts)
//    while (!election.summary.isEmpty()) {
//        val s = election.summary.removeFirst()
//        println(s.text)
//        println(s.weight)
//    }
//}
//
