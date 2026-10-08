package dev.applicazza.flutter.plugins.whatsapp_stickers_plus

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry


/** WhatsappStickersPlugin */
public class WhatsappStickersPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var stickerPackList: List<StickerPack>? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    // Result of the pending sendToWhatsApp call, answered once in onActivityResult.
    private var pendingResult: Result? = null
    val ADD_PACK = 200

    private val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "whatsapp_stickers_plus")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {

        private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
        private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
        private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

        @JvmStatic
        fun getContentProviderAuthorityURI(context: Context): Uri {
            return Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(getContentProviderAuthority(context))
                .appendPath(StickerContentProvider.METADATA).build()
        }

        @JvmStatic
        fun getContentProviderAuthority(context: Context): String {
            return context.packageName + ".stickercontentprovider"
        }

    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getPlatformVersion" ->
                result.success("Android " + android.os.Build.VERSION.RELEASE)

            "isWhatsAppInstalled" ->
                result.success(context?.let { WhitelistCheck.isWhatsAppInstalled(it) });
            "isWhatsAppConsumerAppInstalled" ->
                result.success(WhitelistCheck.isWhatsAppConsumerAppInstalled(context?.packageManager));
            "isWhatsAppSmbAppInstalled" ->
                result.success(WhitelistCheck.isWhatsAppSmbAppInstalled(context?.packageManager));
            "isStickerPackInstalled" -> {
                val stickerPackIdentifier = call.argument<String>("identifier");
                if (stickerPackIdentifier != null && context != null) {
                    val installed = WhitelistCheck.isWhitelisted(context!!, stickerPackIdentifier)
                    result.success(installed);
                }
            }

            "removeStickerPack" -> {
                val identifier = call.argument<String>("identifier")
                if (identifier == null) {
                    result.error("invalid_argument", "identifier is required", null)
                    return
                }
                try {
                    result.success(ConfigFileManager.removePack(context, identifier))
                } catch (e: Exception) {
                    result.error("error", e.message, null)
                }
            }

            "retainStickerPacks" -> {
                val identifiers = call.argument<List<String>>("identifiers")
                if (identifiers == null) {
                    result.error("invalid_argument", "identifiers is required", null)
                    return
                }
                try {
                    result.success(ConfigFileManager.retainPacks(context, identifiers.toSet()))
                } catch (e: Exception) {
                    result.error("error", e.message, null)
                }
            }

            "sendToWhatsApp" -> {
                // A previous request whose result never came back can't be answered anymore.
                pendingResult?.error("cancelled", "cancelled", null)
                pendingResult = null
                try {
                    val stickerPack: StickerPack = ConfigFileManager.fromMethodCall(context, call)
                    // update json file
                    ConfigFileManager.addNewPack(context, stickerPack)
                    context?.let {
                        StickerPackValidator.verifyStickerPackValidity(
                            it,
                            stickerPack
                        )
                    };
                    // send intent to whatsapp
                    val ws = WhitelistCheck.isWhatsAppConsumerAppInstalled(context?.packageManager)
                    if (!(ws || WhitelistCheck.isWhatsAppSmbAppInstalled(context?.packageManager))) {
                        throw InvalidPackException(
                            InvalidPackException.OTHER,
                            "WhatsApp is not installed on target device!"
                        )
                    }
                    val whatsAppPackage =
                        if (ws) WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME else WhitelistCheck.SMB_WHATSAPP_PACKAGE_NAME
                    val stickerPackIdentifier = stickerPack.identifier
                    val stickerPackName = stickerPack.name
                    val authority: String? = context?.let { getContentProviderAuthority(it) }

                    val intent = createIntentToAddStickerPack(
                        authority,
                        stickerPackIdentifier,
                        stickerPackName
                    )

                    val activity = this.activity ?: throw InvalidPackException(
                        InvalidPackException.FAILED,
                        "No activity attached"
                    )
                    try {
                        // Kept for onActivityResult, which answers once WhatsApp returns.
                        pendingResult = result
                        activity.startActivityForResult(
                            Intent.createChooser(
                                intent,
                                "ADD Sticker"
                            ), ADD_PACK
                        )
                    } catch (e: ActivityNotFoundException) {
                        pendingResult = null
                        throw InvalidPackException(
                            InvalidPackException.FAILED,
                            "Sticker pack not added. If you'd like to add it, make sure you update to the latest version of WhatsApp."
                        )
                    }

                } catch (e: InvalidPackException) {
                    result.error(e.code, e.message, null)
                }
            }

            else -> result.notImplemented()
        }
    }

    fun createIntentToAddStickerPack(
        authority: String?,
        identifier: String?,
        stickerPackName: String?
    ): Intent? {
        val intent = Intent()
        intent.action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        intent.putExtra(this.EXTRA_STICKER_PACK_ID, identifier)
        intent.putExtra(this.EXTRA_STICKER_PACK_AUTHORITY, authority)
        intent.putExtra(this.EXTRA_STICKER_PACK_NAME, stickerPackName)
        return intent
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        this.activity = null
        channel.setMethodCallHandler(null)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != ADD_PACK) return false
        // Each request must be answered exactly once, a second reply crashes the app.
        val result = pendingResult ?: return true
        pendingResult = null
        if (resultCode == Activity.RESULT_CANCELED) {
            val validationError = data?.getStringExtra("validation_error")
            if (validationError != null) {
                result.error("error", validationError, "")
            } else {
                result.error("cancelled", "cancelled", "")
            }
        } else if (resultCode == Activity.RESULT_OK) {
            val bundle = data?.extras
            if (bundle?.containsKey("add_successful") == true) {
                result.success("add_successful")
            } else if (bundle?.containsKey("already_added") == true) {
                result.error("already_added", "already_added", "")
            } else {
                result.success("success")
            }
        } else {
            result.success("unknown")
        }
        return true
    }
}
