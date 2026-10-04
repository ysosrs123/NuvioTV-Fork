package com.nuvio.tv.ui.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Keeps the profile's navigation state across renderer changes while allowing it to be
 * cleared on profile exit. Root providers outside NavHost destinations also need the
 * activity's Hilt factory and creation extras; a bare store owner uses the no-arg factory.
 */
internal class ProfileNavigationViewModelStoreOwner(
    parent: HasDefaultViewModelProviderFactory
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory by parent {
    override val viewModelStore = ViewModelStore()
}
