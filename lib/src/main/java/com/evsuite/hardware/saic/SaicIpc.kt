package com.evsuite.hardware.saic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.evsuite.hardware.AppLogger

/**
 * Talking to the SAIC vendor services the head unit's own apps use.
 *
 * Those apps ship an SDK (`com.saicmotor.sdk.*`) that wraps each service, but the SDK lives
 * inside their APKs, not on the boot classpath — reflection cannot reach it from here. What
 * *is* reachable is the AIDL underneath: a service to bind, an interface descriptor, and a
 * transaction code per method. Keep every constant documented here as an implementation
 * detail and validate compatibility on each supported firmware generation.
 *
 * The binding is asynchronous and survives for the process' lifetime. A call made before the
 * service is up returns null rather than blocking: the rule engine has a cycle to finish, and
 * a vendor service that is slow to come up must not hold it.
 */
class SaicService private constructor(
    private val packageName: String,
    private val action: String?,
    private val className: String?,
    private val tag: String,
) {

    /** Most vendor services publish an action; this is the usual way in. */
    constructor(packageName: String, action: String, tag: String) :
        this(packageName, action, null, tag)



    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var connecting = false

    val isReady: Boolean get() = binder?.isBinderAlive == true

    fun binder(): IBinder? = binder?.takeIf { it.isBinderAlive }.also {
        // A service that died leaves a stale handle behind; drop it so the next connect()
        // rebinds instead of transacting into nothing.
        if (it == null && binder != null) binder = null
    }

    /** Idempotent: binds once, and again only after the service has died. */
    fun connect(context: Context) {
        if (isReady || connecting) return
        connecting = true
        val intent = (if (action != null) Intent(action) else Intent()).apply {
            if (className != null) setClassName(packageName, className) else setPackage(packageName)
        }
        val bound = runCatching {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            connecting = false
            AppLogger.w(TAG, "$tag: bindService($packageName/${className ?: action}) refused")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connecting = false
            binder = service
            AppLogger.i(TAG, "$tag: connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            AppLogger.w(TAG, "$tag: disconnected")
        }
    }

    companion object {
        private const val TAG = "EV_SAIC"

        /**
         * For a service with no intent filter, reached by explicit component — how the car's
         * own apps bind the TTS service, and the only way it answers.
         */
        fun byComponent(packageName: String, className: String, tag: String) =
            SaicService(packageName, null, className, tag)

        /**
         * For one component publishing several interfaces, each behind its own action — the
         * media service, where the action selects which player answers.
         *
         * Both halves are needed: the action alone would leave the platform to resolve the
         * component, and this service exports several; the component alone would bind but
         * hand back whichever interface the service returns for a null action, which is not
         * the one asked for.
         */
        fun byActionAndComponent(
            packageName: String,
            action: String,
            className: String,
            tag: String,
        ) = SaicService(packageName, action, className, tag)
    }
}

/**
 * The AIDL calling convention the generated proxies use, written once.
 *
 * Every SAIC interface follows the same shape: interface token, arguments in declaration
 * order, `transact(code, …, 0)`, then `readException()` before the result. Nothing here is
 * SAIC-specific — it is what `aidl` generates — but having it in one place is what keeps the
 * typed wrappers below down to a line each.
 */
object SaicAidl {

    private const val TAG = "EV_SAIC"

    /** @return true when the call reached the service and it did not throw. */
    fun callVoid(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): Boolean =
        transact(binder, descriptor, code, args) { true } ?: false

    fun callInt(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): Int? =
        transact(binder, descriptor, code, args) { it.readInt() }

    fun callBoolean(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): Boolean? =
        transact(binder, descriptor, code, args) { it.readInt() != 0 }

    fun callFloat(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): Float? =
        transact(binder, descriptor, code, args) { it.readFloat() }

    fun callString(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): String? =
        transact(binder, descriptor, code, args) { it.readString() }

    fun callBinder(binder: IBinder?, descriptor: String, code: Int, vararg args: Any?): IBinder? =
        transact(binder, descriptor, code, args) { it.readStrongBinder() }

    /**
     * For the replies a typed helper cannot cover: a vendor bean, which has to be read field
     * by field in the order its own `writeToParcel` wrote them.
     *
     * Coupling to a vendor class' serialisation is a liability, so it is confined here and to
     * its callers: a bean that changes shape throws inside [transact] and the call reads as
     * unanswered, never as a guessed value.
     */
    fun <T> callParcel(
        binder: IBinder?,
        descriptor: String,
        code: Int,
        readReply: (Parcel) -> T,
    ): T? = transact(binder, descriptor, code, emptyArray(), readReply)

    private fun <T> transact(
        binder: IBinder?,
        descriptor: String,
        code: Int,
        args: Array<out Any?>,
        readReply: (Parcel) -> T,
    ): T? {
        val target = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            args.forEach { arg ->
                when (arg) {
                    is Int -> data.writeInt(arg)
                    is Boolean -> data.writeInt(if (arg) 1 else 0)
                    is Float -> data.writeFloat(arg)
                    is String -> data.writeString(arg)
                    is Double -> data.writeDouble(arg)
                    // A callback binder, for the one service here that answers asynchronously.
                    is IBinder -> data.writeStrongBinder(arg)
                    else -> error("unsupported AIDL argument: $arg")
                }
            }
            if (!target.transact(code, data, reply, 0)) {
                AppLogger.d(TAG, "$descriptor#$code: transact returned false")
                return null
            }
            reply.readException()
            readReply(reply)
        } catch (e: Exception) {
            AppLogger.d(TAG, "$descriptor#$code: ${e.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
