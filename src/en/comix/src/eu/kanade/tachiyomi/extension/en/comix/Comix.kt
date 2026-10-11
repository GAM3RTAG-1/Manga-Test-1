package eu.kanade.tachiyomi.extension.en.comix

import android.content.SharedPreferences
import android.webkit.WebResourceResponse
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.int
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.string
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Comix :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl get() = "$baseUrl/api/v1"
    private val preferences: SharedPreferences by getPreferencesLazy()

    @Volatile
    private var cipher: ComixCipher? = null

    private val tagIdCache = object : LinkedHashMap<String, List<String>>(
        TAG_ID_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > TAG_ID_CACHE_SIZE
    }

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(Descrambler.interceptor)
        .addInterceptor { chain ->
            val request = chain.request()

            var response = proceedWithRetry(chain, request)
            if (response.isSuccessful) return@addInterceptor response

            val url = request.url.toString()
            val fallbacks = listOf("/hi/", "/fcf/", "/i5/", "/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isEmpty()) return@addInterceptor response

            for (fallbackUrl in fallbacks) {
                response.close()
                response = proceedWithRetry(chain, request.newBuilder().url(fallbackUrl).build())
                if (response.isSuccessful) break
            }
            response
        }
        .rateLimit(5)

    private fun proceedWithRetry(chain: Interceptor.Chain, request: Request): Response {
        var response = chain.proceed(request)
        if (response.isSuccessful) return response

        for (attempt in 1..10) {
            if (response.code !in SERVER_ERROR_CODES && response.code != 404) break

            response.close()
            runCatching { Thread.sleep(1500) }

            val urlBuilder = request.url.newBuilder()
                .setQueryParameter("r", attempt.toString())

            val retryUrl = urlBuilder.build()
            response = chain.proceed(request.newBuilder().url(retryUrl).build())
            if (response.isSuccessful) break
        }

        return response
    }

    override fun Headers.Builder.configureHeaders() = add("Accept", "*/*")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[score]", "desc")
            addQueryParameter("page", page.toString())
            applyPreferenceFilters()
        }.build()
        return getMangaListFromBrowse(url)
    }

    private suspend fun getMangaListFromBrowse(url: HttpUrl): MangasPage {
        getSigned<SearchResponse>("/api/v1/manga", nativeMangaParams(url))?.let { response ->
            return MangasPage(
                response.result.items.map { it.toBasicSManga(preferences.posterQuality()) },
                response.result.hasNextPage(),
            )
        }

        val document = client.get(url).asJsoup()
        val contentRating = url.queryParameter("content_rating")
            ?: preferences.contentRating()
        val effectiveContentRating = contentRating
            .split(',')
            .lastOrNull { it.isNotBlank() }
            .orEmpty()
            .ifEmpty { "pornographic" }
        val expectedKeyword = JSONObject.quote(
            url.queryParameter("q") ?: url.queryParameter("keyword").orEmpty(),
        )
        val searchResponse = document.extractBrowseResponse() ?: runInWebView(
            document = document,
            initializationScript = """
                (function () {
                    const key = 'settings_v2';
                    let settings = {};
                    try {
                        settings = JSON.parse(localStorage.getItem(key) || '{}');
                    } catch (e) {}
                    settings.state = {
                        ...(settings.state || {}),
                        contentFilter: '$effectiveContentRating'
                    };
                    if (settings.version === undefined) settings.version = 0;
                    localStorage.setItem(key, JSON.stringify(settings));
                })();
            """.trimIndent(),
            buildScript = { passPayloadName, _ ->
                """
                    (function () {
                        const payloadKey = '__comixBrowsePayload';
                        const expectedKeyword = $expectedKeyword;
                        const capture = (parsed, allowEmpty = false) => {
                            try {
                                if (parsed && Array.isArray(parsed.items)) {
                                    parsed = { result: parsed };
                                }
                                if (
                                    parsed &&
                                    parsed.result &&
                                    Array.isArray(parsed.result.items) &&
                                    (allowEmpty || parsed.result.items.length > 0)
                                ) {
                                    window[payloadKey] = JSON.stringify(parsed);
                                    window.$passPayloadName(window[payloadKey]);
                                    return true;
                                }
                            } catch (e) {}
                            return false;
                        };

                        if (window[payloadKey]) return window[payloadKey];

                        try {
                            const raw = document.querySelector('script#initial-data')?.textContent;
                            const queries = raw && JSON.parse(raw).queries;
                            if (queries) Object.values(queries).some(capture);
                        } catch (e) {}

                        if (window[payloadKey]) return window[payloadKey];
                        if (window.__comixBrowseCaptureInstalled) return null;
                        window.__comixBrowseCaptureInstalled = true;

                        const captureText = text => {
                            try {
                                if (text) capture(JSON.parse(text), true);
                            } catch (e) {}
                        };

                        const shouldCaptureUrl = rawUrl => {
                            try {
                                const url = new URL(rawUrl || '', window.location.origin);
                                if (!url.pathname.includes('/api/v1/manga')) return false;
                                if (!expectedKeyword) return true;
                                return url.searchParams.get('keyword') === expectedKeyword;
                            } catch (e) {
                                return false;
                            }
                        };

                        const originalFetch = window.fetch;
                        if (typeof originalFetch === 'function') {
                            window.fetch = function () {
                                return originalFetch.apply(this, arguments).then(response => {
                                    try {
                                        const url = response && response.url || '';
                                        if (shouldCaptureUrl(url)) {
                                            response.clone().text().then(captureText).catch(() => {});
                                        }
                                    } catch (e) {}
                                    return response;
                                });
                            };
                        }

                        const originalOpen = XMLHttpRequest.prototype.open;
                        const originalSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function (method, url) {
                            this.__comixBrowseUrl = String(url || '');
                            return originalOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function () {
                            this.addEventListener('load', function () {
                                try {
                                    if (shouldCaptureUrl(this.__comixBrowseUrl)) {
                                        captureText(this.responseText);
                                    }
                                } catch (e) {}
                            });
                            return originalSend.apply(this, arguments);
                        };

                        const originalParse = JSON.parse;
                        const proxiedParse = new Proxy(originalParse, {
                            apply(target, thisArg, args) {
                                const parsed = Reflect.apply(target, 
