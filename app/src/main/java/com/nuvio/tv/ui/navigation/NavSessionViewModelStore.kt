package com.nuvio.tv.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import java.util.UUID

/**
 * Store owner for the main NavHost. Provided by MainActivity next to its NavController;
 * when absent the NavHost falls back to the activity's store.
 */
val LocalNavViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

/**
 * Keeps the back stack ViewModels of the current main UI session in a store of their own.
 *
 * Switching profile takes the main UI, and with it the NavController, out of composition
 * without popping the back stack. With the activity's store the back stack ViewModels were
 * never cleared, so every switch left another Home (and any open Search or Library) running
 * its pipelines for whichever profile became active next.
 *
 * The holder lives in the activity's store so the session survives activity recreation
 * (e.g. a language change), and the session store is cleared when the session ends.
 * Each session has a store of its own: a session that ends after the next one has started
 * must not clear the store the new NavController already uses.
 */
class NavSessionViewModelStoreHolder : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(session: String): ViewModelStore = stores.getOrPut(session) { ViewModelStore() }

    fun endSession(session: String) {
        stores.remove(session)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Remembers the store owner for [navController], created in the same composition. When that
 * composition goes away, unless the activity is only being recreated, the session ends: the
 * controller's back stack is destroyed and the store is cleared.
 */
@Composable
fun rememberNavSessionViewModelStoreOwner(navController: NavHostController): ViewModelStoreOwner {
    val holder: NavSessionViewModelStoreHolder = viewModel()
    val session = rememberSaveable { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, navController, session) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                // When the activity itself is being destroyed, the controller has already been told.
                if (hostLifecycle.currentState != Lifecycle.State.DESTROYED) {
                    navController.detachFromHostLifecycle()
                }
                holder.endSession(session)
            }
        }
    }
    return remember(holder, activity, session) {
        val factoryParent = activity as? HasDefaultViewModelProviderFactory
        if (factoryParent != null) {
            SessionStoreOwnerWithFactory(holder, session, factoryParent)
        } else {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore
                    get() = holder.storeFor(session)
            }
        }
    }
}

// Providers outside NavHost destinations resolve Hilt ViewModels through this owner, so it keeps
// the activity's factory and creation extras instead of the no-arg factory.
private class SessionStoreOwnerWithFactory(
    private val holder: NavSessionViewModelStoreHolder,
    private val session: String,
    parent: HasDefaultViewModelProviderFactory
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory by parent {
    override val viewModelStore: ViewModelStore
        get() = holder.storeFor(session)
}

/**
 * NavHost registers the controller as an observer of the activity lifecycle and never removes it,
 * so a controller that is no longer shown stays referenced until the activity is destroyed. Moves
 * it to a lifecycle of its own and ends that, which also destroys its back stack entries.
 */
private fun NavHostController.detachFromHostLifecycle() {
    val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    owner.lifecycle.currentState = Lifecycle.State.CREATED
    setLifecycleOwner(owner)
    owner.lifecycle.currentState = Lifecycle.State.DESTROYED
}
