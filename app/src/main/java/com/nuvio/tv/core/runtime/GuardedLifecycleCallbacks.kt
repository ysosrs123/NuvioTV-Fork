package com.nuvio.tv.core.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * Wraps a lifecycle callback registered by code loaded at runtime (CloudStream extensions), so an error
 * inside it switches that callback off instead of taking the whole app down.
 */
internal class GuardedLifecycleCallbacks(
    private val delegate: Application.ActivityLifecycleCallbacks
) : Application.ActivityLifecycleCallbacks {

    @Volatile
    var failed = false
        private set

    private inline fun guard(event: String, block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            failed = true
            Log.e(TAG, "Lifecycle callback ${delegate.javaClass.name} failed in $event, switched off", t)
        }
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) =
        guard("onActivityPreCreated") { delegate.onActivityPreCreated(activity, savedInstanceState) }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
        guard("onActivityCreated") { delegate.onActivityCreated(activity, savedInstanceState) }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) =
        guard("onActivityPostCreated") { delegate.onActivityPostCreated(activity, savedInstanceState) }

    override fun onActivityPreStarted(activity: Activity) =
        guard("onActivityPreStarted") { delegate.onActivityPreStarted(activity) }

    override fun onActivityStarted(activity: Activity) =
        guard("onActivityStarted") { delegate.onActivityStarted(activity) }

    override fun onActivityPostStarted(activity: Activity) =
        guard("onActivityPostStarted") { delegate.onActivityPostStarted(activity) }

    override fun onActivityPreResumed(activity: Activity) =
        guard("onActivityPreResumed") { delegate.onActivityPreResumed(activity) }

    override fun onActivityResumed(activity: Activity) =
        guard("onActivityResumed") { delegate.onActivityResumed(activity) }

    override fun onActivityPostResumed(activity: Activity) =
        guard("onActivityPostResumed") { delegate.onActivityPostResumed(activity) }

    override fun onActivityPrePaused(activity: Activity) =
        guard("onActivityPrePaused") { delegate.onActivityPrePaused(activity) }

    override fun onActivityPaused(activity: Activity) =
        guard("onActivityPaused") { delegate.onActivityPaused(activity) }

    override fun onActivityPostPaused(activity: Activity) =
        guard("onActivityPostPaused") { delegate.onActivityPostPaused(activity) }

    override fun onActivityPreStopped(activity: Activity) =
        guard("onActivityPreStopped") { delegate.onActivityPreStopped(activity) }

    override fun onActivityStopped(activity: Activity) =
        guard("onActivityStopped") { delegate.onActivityStopped(activity) }

    override fun onActivityPostStopped(activity: Activity) =
        guard("onActivityPostStopped") { delegate.onActivityPostStopped(activity) }

    override fun onActivityPreSaveInstanceState(activity: Activity, outState: Bundle) =
        guard("onActivityPreSaveInstanceState") { delegate.onActivityPreSaveInstanceState(activity, outState) }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
        guard("onActivitySaveInstanceState") { delegate.onActivitySaveInstanceState(activity, outState) }

    override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) =
        guard("onActivityPostSaveInstanceState") { delegate.onActivityPostSaveInstanceState(activity, outState) }

    override fun onActivityPreDestroyed(activity: Activity) =
        guard("onActivityPreDestroyed") { delegate.onActivityPreDestroyed(activity) }

    override fun onActivityDestroyed(activity: Activity) =
        guard("onActivityDestroyed") { delegate.onActivityDestroyed(activity) }

    override fun onActivityPostDestroyed(activity: Activity) =
        guard("onActivityPostDestroyed") { delegate.onActivityPostDestroyed(activity) }

    companion object {
        private const val TAG = "PluginLifecycle"

        /** True when [callback] comes from a class loader other than the app's own. */
        fun isFromLoadedCode(callback: Application.ActivityLifecycleCallbacks, appLoader: ClassLoader?): Boolean =
            callback.javaClass.classLoader !== appLoader
    }
}
