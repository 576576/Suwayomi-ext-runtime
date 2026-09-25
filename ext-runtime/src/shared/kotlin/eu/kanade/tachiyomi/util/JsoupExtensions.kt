package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

fun Element.selectText(
    css: String,
    defaultValue: String? = null,
): String? = select(css).first()?.text() ?: defaultValue

fun Element.selectInt(
    css: String,
    defaultValue: Int = 0,
): Int = select(css).first()?.text()?.toInt() ?: defaultValue

fun Element.attrOrText(css: String): String = if (css != "text") attr(css) else text()

/**
 * Returns a Jsoup document for this response.
 * @param html the body of the response. Use only if the body was read before calling this method.
 */
// okhttp 4.0 起 `Response.body` 是非空 `ResponseBody`（3.x 才可空），`!!` 是多余的。
fun Response.asJsoup(html: String? = null): Document = Jsoup.parse(html ?: body.string(), request.url.toString())
