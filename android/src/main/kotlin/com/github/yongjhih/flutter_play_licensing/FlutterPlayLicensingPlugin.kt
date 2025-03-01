package com.github.yongjhih.flutter_play_licensing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import com.google.android.vending.licensing.AESObfuscator
import com.google.android.vending.licensing.LicenseChecker
import com.google.android.vending.licensing.LicenseCheckerCallback
import com.google.android.vending.licensing.LicenseCheckerCallback.*
import com.google.android.vending.licensing.ServerManagedPolicy
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result


object PlayLicensingConfig {
  val salt: ByteArray = byteArrayOf(
          -46, 65, 30, -128, -101, -57, 74, -64, 51, 88,
          -95, -45, 79, -117, -36, -113, -13, 32, -64, 89
  )

  // in base64, BASE64_PUBLIC_KEY
  val publicKey: String = ""
}

/** FlutterPlayLicensingPlugin
 *
 * ref. https://developer.android.com/google/play/licensing/client-side-verification
 */
public class FlutterPlayLicensingPlugin : FlutterPlugin, MethodCallHandler {

  private lateinit var context: Context
  private lateinit var channel : MethodChannel

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.getApplicationContext()
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "play_licensing")
      channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "check" -> {
        check(call, result)
      }
      "isAllowed" -> {
        isAllowed(call, result)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  private fun Context.checker(salt: ByteArray? = null,
                              publicKey: String? = null): LicenseChecker {
    return LicenseChecker(
            this,
            ServerManagedPolicy(
                    this,
                    AESObfuscator(
                            salt ?: PlayLicensingConfig.salt,
                            packageName,
                            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    )
            ),
            publicKey ?: PlayLicensingConfig.publicKey
    )
  }

  private fun isAllowed(@NonNull call: MethodCall, @NonNull result: Result) {

      val checker = context.checker(
              call.argument<String>("salt")?.toHexByteArray,
              call.argument<String>("publicKey"))
      checker.checkAccess(
              onAllow = {
                result.onMain().success(true)
              },
              onDontAllow = {
                result.onMain().success(false)
              },
              onApplicationError = { errorCode ->
                when (errorCode) {
                  ERROR_MISSING_PERMISSION -> {
                    result.onMain().errors(errorCode.toString(),
                            "ERROR_MISSING_PERMISSION",
                            details = errorCode)
                  }
                  ERROR_CHECK_IN_PROGRESS -> {
                    result.onMain().errors(errorCode.toString(),
                            "ERROR_CHECK_IN_PROGRESS",
                            details = errorCode)
                  }
                  //ERROR_NON_MATCHING_UID -> {
                  //  result.onMain().errors(errorCode.toString(),
                  //          "ERROR_NON_MATCHING_UID",
                  //          details = errorCode)
                  //}
                  //ERROR_INVALID_PACKAGE_NAME -> {
                  //  result.onMain().errors(errorCode.toString(),
                  //          "ERROR_INVALID_PACKAGE_NAME",
                  //          details = errorCode)
                  //}
                  //ERROR_INVALID_PUBLIC_KEY -> {
                  //  result.onMain().errors(errorCode.toString(),
                  //          "ERROR_INVALID_PUBLIC_KEY",
                  //          details = errorCode)
                  //}
                  else -> {
                    result.onMain().success(false)
                  }
                }
              }
      )
  }

  private fun check(@NonNull call: MethodCall, @NonNull result: Result) {

        val checker = context.checker(
                call.argument<String>("salt")?.toHexByteArray,
                call.argument<String>("publicKey"))
        checker.checkAccess(
                onAllow = { reason ->
                  result.onMain().success(reason)
                },
                onDontAllow = { reason ->
                  result.onMain().errors(reason.toString(), details = reason)
                },
                onApplicationError = { errorCode ->
                  result.onMain().errors(errorCode.toString(), details = errorCode)
                }
        )
  }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}

fun LicenseChecker.checkAccess(onAllow: (Int) -> Unit = { _ -> },
                               onDontAllow: (Int) -> Unit = { _ -> },
                               onApplicationError: (Int) -> Unit = { _ -> }) {
  checkAccess(object : LicenseCheckerCallback {
    override fun allow(reason: Int) {
      onAllow(reason)
    }

    override fun dontAllow(reason: Int) {
      onDontAllow(reason)
    }

    override fun applicationError(errorCode: Int) {
      onApplicationError(errorCode)
    }
  })
}

fun Result.onMain(): ResultOnMain {
  return if (this is ResultOnMain) {
    this
  } else {
    ResultOnMain(this)
  }
}

class ResultOnMain(private val result: Result) : Result {
  private val handler: Handler by lazy {
    Handler(Looper.getMainLooper())
  }

  override fun success(res: Any?) {
    handler.post { result.success(res) }
  }

  override fun error(
          errorCode: String, errorMessage: String?, errorDetails: Any?) {
    handler.post { result.error(errorCode, errorMessage, errorDetails) }
  }

  override fun notImplemented() {
    handler.post { result.notImplemented() }
  }
}

fun <T> MethodCall.argumentOrNull(key: String): T? = try { argument(key) } catch (e: Throwable) { null }
fun <T> MethodCall.argumentsOrNull(): T? = arguments() as? T?
//fun <T> MethodCall.argument(key: String): T? = try { argument(key) } catch (e: Throwable) { null }
//fun <T> MethodCall.arguments(): T? = arguments() as? T?
//fun Result.success(result: Any? = null): Unit = success(result)
fun Result.success(): Unit = success(null) // avoid shadow
fun Result.errors(code: String, message: String? = null, details: Any? = null): Unit = error(code, message, details)
fun Result.error(e: Throwable): Unit = errors(e.cause.toString(), e.message, e.stackTrace)

val Any.TAG: String
  get() {
    val tag = javaClass.simpleName
    val max = 23
    return if (tag.length <= max) tag else tag.substring(0, max)
  }

/// ref. https://gist.github.com/fabiomsr/845664a9c7e92bafb6fb0ca70d4e44fd#gistcomment-2836766
val ByteArray.toHex inline get() = joinToString(separator = "") {
  String.format("%02x",(it.toInt() and 0xFF))
}

val String.toHexByteArray inline get(): ByteArray? = try {
  chunked(2).map {
    it.toUpperCase().toInt(16).toByte()
  }.toByteArray()
} catch (e: Throwable) { null }
