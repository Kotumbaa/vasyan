package `fun`.tryliner.android.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `fun`.tryliner.android.core.network.HumanCheckRequest
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

private const val TURNSTILE_HOOK_JS = """
(function() {
  if (window.__linerHooked) return;
  window.__linerHooked = true;
  window.addEventListener("message", function(e) {
    var d = e.data;
    if (d && d.source === "liner-turnstile" && window.LinerTurnstile) {
      window.LinerTurnstile.onMessage(d.type || "", d.token || "");
    }
  });
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HumanCheckDialog(
    request: HumanCheckRequest,
    baseUrl: String,
    userAgent: String,
    onSolved: (token: String?, clearanceCookie: String?) -> Unit,
    onCancel: () -> Unit
) {
    val normalizedBaseUrl = remember(baseUrl) { baseUrl.trim().trimEnd('/') }
    var attempt by remember(request) { mutableIntStateOf(0) }
    var isLoading by remember(request, attempt) { mutableStateOf(true) }
    var statusError by remember(request, attempt) { mutableStateOf<String?>(null) }
    var detectedClearance by remember(request, attempt) { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        LinerPanel(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = LinerShapes.Panel,
            containerColor = LinerColors.SurfaceGlass
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Security Verification",
                    style = MaterialTheme.typography.titleLarge,
                    color = LinerColors.TextPrimary
                )

                Text(
                    text = "Complete the check below to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinerColors.TextSecondary
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(Color.TRANSPARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.userAgentString = userAgent

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        view?.evaluateJavascript(TURNSTILE_HOOK_JS, null)
                                        val clearance = extractCfClearance(
                                            cookieManager.getCookie(normalizedBaseUrl)
                                        )
                                        if (clearance != null) {
                                            detectedClearance = clearance
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            isLoading = false
                                            statusError = error?.description?.toString()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: "Failed to load verification page."
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?
                                    ) {
                                        val code = errorResponse?.statusCode ?: return
                                        if (request?.isForMainFrame == true && code != 403) {
                                            statusError = "Verification page returned HTTP $code."
                                        }
                                    }
                                }

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onMessage(type: String?, token: String?) {
                                            mainHandler.post {
                                                when (type) {
                                                    "token" -> {
                                                        val clearance = extractCfClearance(
                                                            cookieManager.getCookie(normalizedBaseUrl)
                                                        )
                                                        cookieManager.flush()
                                                        onSolved(
                                                            token?.takeIf { it.isNotBlank() },
                                                            clearance
                                                        )
                                                    }
                                                    "expired" -> {
                                                        statusError = "Verification expired. Please retry."
                                                    }
                                                    "error" -> {
                                                        statusError = "Verification error. Please retry."
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    "LinerTurnstile"
                                )

                                webViewRef = this
                            }
                        },
                        update = { webView ->
                            val safeAction = request.action.replace(Regex("[^a-zA-Z0-9_-]"), "")
                            val tag = "$safeAction:$attempt"
                            if (webView.tag != tag) {
                                webView.tag = tag
                                isLoading = true
                                statusError = null
                                webView.loadUrl("$normalizedBaseUrl/v1/auth/verify?action=$safeAction")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading) {
                        CircularProgressIndicator(color = LinerColors.AccentPrimary)
                    }
                }

                if (!statusError.isNullOrBlank()) {
                    Text(
                        text = statusError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.AccentPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detectedClearance != null) {
                        Button(
                            onClick = {
                                val cookieManager = CookieManager.getInstance()
                                val latest = extractCfClearance(
                                    cookieManager.getCookie(normalizedBaseUrl)
                                ) ?: detectedClearance
                                cookieManager.flush()
                                onSolved(null, latest)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LinerColors.BtnPrimaryBg,
                                contentColor = LinerColors.BtnPrimaryText
                            ),
                            shape = LinerShapes.Control
                        ) {
                            Text("Continue")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    TextButton(onClick = { attempt++ }) {
                        Text("Retry", color = LinerColors.TextSecondary)
                    }
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = LinerColors.AccentPrimary)
                    }
                }
            }
        }
    }
}

private fun extractCfClearance(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    return cookieHeader.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("cf_clearance=") }
        ?.substringAfter("cf_clearance=")
        ?.takeIf { it.isNotBlank() }
}
