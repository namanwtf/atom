package atom.web.search

import java.net.URI

data class ReadableLink(val text: String, val url: String)

data class ReadableDoc(val title: String, val text: String, val links: List<ReadableLink> = emptyList())

/** Turns an HTML page into its readable main text: no menus, footers, scripts, cookie banners or ads. */
object Readable {
    private val dropTags = setOf(
        "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed", "head",
        "nav", "footer", "aside", "form", "button", "select", "input", "textarea", "dialog", "menu", "video", "audio", "picture",
    )
    private val junk = Regex(
        "(^|[\\s_-])(nav|navbar|menu|footer|sidebar|side-bar|breadcrumbs?|cookie|consent|banner|gdpr|share|sharing|social|" +
            "comments?|related|recommend|promo|advert|ads?|sponsor|newsletter|subscribe|popup|modal|signup|toolbar|skip-link)([\\s_-]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val blockTags = setOf(
        "p", "div", "section", "article", "main", "header", "ul", "ol", "li", "table", "tr", "blockquote", "pre", "figure",
        "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "dl", "dt", "dd", "hr", "address", "details", "summary", "body",
    )

    fun extract(html: String, baseUrl: String? = null): ReadableDoc {
        val root = Html.parse(html)
        val title = pageTitle(root)
        prune(root)
        val content = mainContent(root)
        val links = ArrayList<ReadableLink>()
        val sb = StringBuilder()
        render(content, sb, links, baseUrl, pre = false)
        var text = tidy(sb.toString())
        if (text.length < 200) {
            val all = StringBuilder()
            render(root.walk().firstOrNull { it.tag == "body" } ?: root, all, ArrayList(), baseUrl, pre = false)
            val fallback = tidy(all.toString())
            if (fallback.length > text.length) text = fallback
        }
        return ReadableDoc(title, text, links.distinctBy { it.url }.take(200))
    }

    private fun pageTitle(root: Node): String {
        val metas = root.walk().filter { it.tag == "meta" }
        val og = metas.firstOrNull { it.attr("property") == "og:title" || it.attr("name") == "twitter:title" }?.attr("content")
        val t = root.walk().firstOrNull { it.tag == "title" }?.text()
        return (t?.takeIf { it.isNotBlank() } ?: og ?: "").replace(Regex("\\s+"), " ").trim()
    }

    private fun isJunk(n: Node): Boolean {
        if (n.tag in dropTags) return true
        if (n.attr("hidden") != null || n.attr("aria-hidden") == "true") return true
        val style = n.attr("style")
        if (style != null && style.replace(" ", "").contains("display:none", true)) return true
        val role = n.attr("role")
        if (role == "navigation" || role == "banner" || role == "contentinfo" || role == "complementary" || role == "dialog") return true
        if (n.tag == "header" && n.parent?.tag == "body") return true
        if (n.tag in setOf("article", "main", "body", "html")) return false
        val key = (n.attr("class").orEmpty() + " " + n.attr("id").orEmpty()).trim()
        return key.isNotEmpty() && junk.containsMatchIn(key)
    }

    private fun prune(node: Node) {
        node.children.removeAll { it is Node && isJunk(it) }
        for (c in node.children) if (c is Node) prune(c)
    }

    private fun mainContent(root: Node): Node {
        val articles = root.walk().filter { it.tag == "article" || it.attr("itemprop") == "articleBody" }.toList()
        articles.maxByOrNull { it.text().length }?.let { if (it.text().trim().length > 250) return it }
        root.walk().firstOrNull { it.tag == "main" || it.attr("role") == "main" }?.let { if (it.text().trim().length > 250) return it }
        val scores = HashMap<Node, Double>()
        for (p in root.walk().filter { it.tag == "p" || it.tag == "pre" || it.tag == "li" || it.tag == "td" }) {
            val len = p.text().trim().length
            if (len < 25) continue
            val score = 1.0 + p.text().count { it == ',' } + minOf(len / 100.0, 3.0)
            p.parent?.let { scores[it] = (scores[it] ?: 0.0) + score }
            p.parent?.parent?.let { scores[it] = (scores[it] ?: 0.0) + score / 2 }
        }
        val best = scores.maxByOrNull { it.value }?.key
        return best ?: root.walk().firstOrNull { it.tag == "body" } ?: root
    }

    private fun render(node: Node, sb: StringBuilder, links: MutableList<ReadableLink>, base: String?, pre: Boolean) {
        val tag = node.tag
        when (tag) {
            "br" -> { sb.append('\n'); return }
            "hr" -> { sb.append("\n\n"); return }
            "img" -> {
                val alt = node.attr("alt")?.trim()
                if (!alt.isNullOrEmpty() && alt.length > 3) sb.append(" [image: ").append(alt).append("] ")
                return
            }
        }
        val block = tag in blockTags
        if (block) sb.append("\n\n")
        when (tag) {
            "h1" -> sb.append("# ")
            "h2" -> sb.append("## ")
            "h3", "h4", "h5", "h6" -> sb.append("### ")
            "li" -> sb.append("- ")
            "blockquote" -> sb.append("> ")
            "td", "th" -> sb.append(" | ")
        }
        val isPre = pre || tag == "pre"
        val start = sb.length
        for (c in node.children) {
            if (c is String) {
                sb.append(if (isPre) c.replace(' ', PRE_SPACE).replace('\t', PRE_SPACE) else c.replace(Regex("\\s+"), " "))
            } else {
                render(c as Node, sb, links, base, isPre)
            }
        }
        if (tag == "a") {
            val href = node.attr("href")
            val label = sb.substring(start).trim()
            val abs = href?.let { resolve(base, it) }
            if (abs != null && label.isNotEmpty() && (abs.startsWith("http://") || abs.startsWith("https://"))) {
                links += ReadableLink(label.take(120), abs)
            }
        }
        if (block) sb.append("\n\n")
    }

    fun resolve(base: String?, href: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("javascript:") || h.startsWith("#") || h.startsWith("mailto:")) return null
        return runCatching { if (base == null) URI(h).toString() else URI(base).resolve(h.replace(" ", "%20")).toString() }.getOrNull()
    }

    fun tidy(s: String): String {
        val lines = s.split('\n').map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty() || line == "|" || line == "-") {
                blank++
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(line.removePrefix("| ").trim())
            blank = 0
        }
        return out.toString().trim().replace(PRE_SPACE, ' ')
    }

    private const val PRE_SPACE = '\uE000'
}

/** Splits long text into pages for tool results, cutting at a line break near the end when possible. */
object Paging {
    data class Page(val text: String, val offset: Int, val end: Int, val total: Int) {
        val hasMore: Boolean get() = end < total

        fun footer(unit: String = "characters"): String =
            if (hasMore) "\n\n[Showing $unit $offset–$end of $total. Call again with offset=$end for more.]"
            else if (offset > 0) "\n\n[End. Showing $unit $offset–$end of $total.]" else ""
    }

    fun page(text: String, offset: Int, limit: Int): Page {
        val total = text.length
        val start = offset.coerceIn(0, total)
        var end = (start + limit.coerceAtLeast(1)).coerceAtMost(total)
        if (end < total) {
            val cut = text.lastIndexOf('\n', end)
            if (cut > start + limit / 2) end = cut + 1
        }
        return Page(text.substring(start, end), start, end, total)
    }
}
