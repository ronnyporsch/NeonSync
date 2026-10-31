@file:OptIn(ExperimentalWasmJsInterop::class)

package neonsync.web

import kotlinx.coroutines.await
import neonsync.SecretStore
import kotlin.js.Promise

/** secrets.js */
private external object NeonSyncSecrets : JsAny {
    fun available(): Boolean
    fun read(name: String): Promise<JsString?>
    fun write(name: String, value: String): Promise<JsAny?>
}

/**
 * Browsers have no credential store for sites, so the token is encrypted with a key only this site
 * can use (see secrets.js). That needs WebCrypto, which browsers offer on https and localhost only.
 */
class WebSecretStore(private val name: String) : SecretStore {
    override val location get() = "this browser, encrypted"

    override suspend fun read(): String? = if (NeonSyncSecrets.available()) NeonSyncSecrets.read(name).await<JsString?>()?.toString() else null

    override suspend fun write(secret: String) {
        check(NeonSyncSecrets.available()) { "the browser only allows this on https or localhost" }
        NeonSyncSecrets.write(name, secret).await<JsAny?>()
    }
}
