package com.reactnativestripesdk

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import com.facebook.react.bridge.Promise
import com.reactnativestripesdk.utils.ConfirmSetupIntentErrorType
import com.reactnativestripesdk.utils.createError
import com.stripe.android.ApiResultCallback
import com.stripe.android.SetupIntentResult
import com.stripe.android.Stripe
import com.stripe.android.StripeIntentResult
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.SetupIntent

/**
 * Confirms a SetupIntent, or handles its next action, through the Stripe host API instead of
 * PaymentLauncher.
 *
 * PaymentLauncher keeps a transparent activity on top while Stripe reconciles a dismissed
 * challenge, and that activity swallows every touch until reconciliation ends, so the app
 * looks frozen. Here the challenge returns straight to the host activity and the result is
 * reconciled behind it.
 */
internal class LegacySetupIntentLauncher {
  private var pending: Pending? = null

  fun confirm(
    stripe: Stripe,
    activity: ComponentActivity,
    params: ConfirmSetupIntentParams,
    promise: Promise,
  ) {
    launch(stripe, params.clientSecret, promise) { stripe.confirmSetupIntent(activity, params) }
  }

  fun handleNextAction(
    stripe: Stripe,
    activity: ComponentActivity,
    clientSecret: String,
    promise: Promise,
  ) {
    launch(stripe, clientSecret, promise) { stripe.handleNextActionForSetupIntent(activity, clientSecret) }
  }

  fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    val pending = pending ?: return
    if (pending.isReconciling) return
    if (data == null) {
      if (requestCode != SETUP_REQUEST_CODE) return
      // Backing out of the initial 3DS2 progress screen returns without an intent.
      settle(
        pending,
        if (resultCode == Activity.RESULT_CANCELED) {
          createError(ConfirmSetupIntentErrorType.Canceled.toString(), message = null)
        } else {
          createError(ConfirmSetupIntentErrorType.Failed.toString(), "Setup returned no result")
        },
      )
      return
    }
    if (!pending.stripe.isSetupResult(requestCode, data)) return
    pending.isReconciling = true

    val callback =
      object : ApiResultCallback<SetupIntentResult> {
        override fun onSuccess(result: SetupIntentResult) {
          when (result.outcome) {
            StripeIntentResult.Outcome.SUCCEEDED -> retrieve(pending)
            StripeIntentResult.Outcome.CANCELED ->
              settle(pending, createError(ConfirmSetupIntentErrorType.Canceled.toString(), message = null))
            else ->
              settle(
                pending,
                createError(ConfirmSetupIntentErrorType.Failed.toString(), result.failureMessage),
              )
          }
        }

        override fun onError(e: Exception) {
          settle(pending, createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
        }
      }
    try {
      if (!pending.stripe.onSetupResult(requestCode, data, callback)) {
        settle(pending, createError(ConfirmSetupIntentErrorType.Failed.toString(), "Setup result was not handled"))
      }
    } catch (e: Exception) {
      settle(pending, createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
    }
  }

  private fun launch(
    stripe: Stripe,
    clientSecret: String,
    promise: Promise,
    start: () -> Unit,
  ) {
    if (pending != null) {
      promise.resolve(createError(ConfirmSetupIntentErrorType.Failed.toString(), "Setup is already in progress"))
      return
    }
    val pending = Pending(stripe, clientSecret, promise)
    this.pending = pending
    try {
      start()
    } catch (e: Exception) {
      settle(pending, createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
    }
  }

  // The same read-back PaymentLauncher does, so both paths resolve with the same payload.
  private fun retrieve(pending: Pending) {
    pending.stripe.retrieveSetupIntent(
      clientSecret = pending.clientSecret,
      expand = listOf("payment_method"),
      callback = object : ApiResultCallback<SetupIntent> {
        override fun onSuccess(result: SetupIntent) {
          if (this@LegacySetupIntentLauncher.pending !== pending) return
          this@LegacySetupIntentLauncher.pending = null
          resolveSetupIntent(result, pending.promise)
        }

        override fun onError(e: Exception) {
          settle(pending, createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
        }
      },
    )
  }

  private fun settle(
    pending: Pending,
    result: Any,
  ) {
    if (this.pending !== pending) return
    this.pending = null
    pending.promise.resolve(result)
  }

  private class Pending(
    val stripe: Stripe,
    val clientSecret: String,
    val promise: Promise,
    var isReconciling: Boolean = false,
  )

  private companion object {
    // StripePaymentController.SETUP_REQUEST_CODE, which is internal to stripe-android.
    const val SETUP_REQUEST_CODE = 50001
  }
}
