package app.mtk
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.webkit.*

class Bridge { @JavascriptInterface fun call(p: String, b: String): String = B.handle(p, b) }

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        B.start(filesDir)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Bridge(), "Bridge")
        w.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(m).setPositiveButton("حسنا") { _, _ -> r.confirm() }.setOnCancelListener { r.cancel() }.show(); return true
            }
            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(m).setPositiveButton("نعم") { _, _ -> r.confirm() }
                    .setNegativeButton("لا") { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show(); return true
            }
        }
        w.loadUrl("file:///android_asset/index.html")
        setContentView(w)
    }
}
