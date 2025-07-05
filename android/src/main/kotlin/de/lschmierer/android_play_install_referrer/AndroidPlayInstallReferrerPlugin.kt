package de.lschmierer.android_play_install_referrer
 
import android.content.Context
import androidx.annotation.NonNull
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.rmi.RemoteException
import kotlin.collections.ArrayList
 
/** AndroidPlayInstallReferrerPlugin */
class AndroidPlayInstallReferrerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val pendingResults = ArrayList<Result>(1)
    private var referrerClient: InstallReferrerClient? = null
    private var referrerDetails: ReferrerDetails? = null
    private var referrerError: Pair<String, String>? = null
 
    private val isInstallReferrerPending: Boolean
        @Synchronized
        get() {
            return referrerClient != null && !isInstallReferrerResolved
        }
 
    private val isInstallReferrerResolved: Boolean
        @Synchronized
        get() {
            return referrerDetails != null || referrerError != null
        }
 
    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.context = flutterPluginBinding.applicationContext
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "de.lschmierer.android_play_install_referrer"
        )
        channel.setMethodCallHandler(this)
    }
 
    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getInstallReferrer") {
            getInstallReferrer(result)
        } else {
            result.notImplemented()
        }
    }
 
    @Synchronized
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        pendingResults.clear()
        referrerClient?.endConnection()
        channel.setMethodCallHandler(null)
    }
 
    @Synchronized
    private fun getInstallReferrer(@NonNull result: Result) {
        if (isInstallReferrerResolved) {
            resolveInstallReferrerResult(result)
        } else {
            pendingResults.add(result)
 
            if (!isInstallReferrerPending) {
                referrerClient = InstallReferrerClient.newBuilder(context).build()
                referrerClient?.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        handleOnInstallReferrerSetupFinished(responseCode)
                    }
 
                    override fun onInstallReferrerServiceDisconnected() {}
                })
            }
        }
    }
 
    @Synchronized
    private fun handleOnInstallReferrerSetupFinished(responseCode: Int) {
        when (responseCode) {
            InstallReferrerClient.InstallReferrerResponse.OK -> {
                referrerClient?.let { client ->
                    try {
                        referrerDetails = client.installReferrer
                    } catch (e: android.os.DeadObjectException) {
                        referrerError = Pair("DEAD_OBJECT", "Service connection lost: ${e.message}")
                    } catch (e: RemoteException) {
                        referrerError = Pair("REMOTE_ERROR", "Remote service error: ${e.message}")
                    } finally {
                        client.endConnection() // Always close the connection
                    }
                } ?: run {
                    referrerError = Pair("BAD_STATE", "Referrer client is null.")
                }
            }
 
            InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                referrerError = Pair(
                    "SERVICE_DISCONNECTED",
                    "Play Store service is not connected now - potentially transient state."
                )
            }
 
            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                referrerError = Pair("SERVICE_UNAVAILABLE", "Connection couldn't be established.")
            }
 
            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                referrerError = Pair(
                    "FEATURE_NOT_SUPPORTED",
                    "API not available on the current Play Store app."
                )
            }
 
            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR -> {
                referrerError = Pair("DEVELOPER_ERROR", "General errors caused by incorrect usage.")
            }
 
            InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> {
                referrerError =
                    Pair("PERMISSION_ERROR", "App is not allowed to bind to the Service.")
            }
 
            else -> {
                referrerError =
                    Pair("UNKNOWN_ERROR", "InstallReferrerClient returned unknown response code.")
            }
        }
 
        resolvePendingInstallReferrerResults()
    }
 
    @Synchronized
    private fun resolvePendingInstallReferrerResults() {
        pendingResults.forEach {
            resolveInstallReferrerResult(it)
        }
        pendingResults.clear()
    }
 
    @Synchronized
    private fun resolveInstallReferrerResult(@NonNull result: Result) {
        referrerDetails?.let { details ->
            result.success(
                mapOf(
                    "installReferrer" to details.installReferrer,
                    "referrerClickTimestampSeconds" to details.referrerClickTimestampSeconds,
                    "installBeginTimestampSeconds" to details.installBeginTimestampSeconds,
                    "referrerClickTimestampServerSeconds" to details.referrerClickTimestampServerSeconds,
                    "installBeginTimestampServerSeconds" to details.installBeginTimestampServerSeconds,
                    "installVersion" to details.installVersion,
                    "googlePlayInstantParam" to details.googlePlayInstantParam
                )
            )
            return
        }
        referrerError?.let {
            result.error(it.first, it.second, null)
            return
        }
        result.error("UNKNOWN", "Unexpected null state", null) // Fallback for unexpected null state
    }
}
