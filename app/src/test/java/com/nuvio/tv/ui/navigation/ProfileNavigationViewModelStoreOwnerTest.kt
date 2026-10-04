package com.nuvio.tv.ui.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNavigationViewModelStoreOwnerTest {
    private class Dependency
    private class InjectedViewModel(val dependency: Dependency) : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
    private object DependencyKey : CreationExtras.Key<Dependency>

    private class ParentOwner : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        val dependency = Dependency()
        override val defaultViewModelCreationExtras = MutableCreationExtras().apply {
            this[DependencyKey] = dependency
        }
        override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return modelClass.cast(InjectedViewModel(checkNotNull(extras[DependencyKey])))!!
            }
        }
    }

    @Test
    fun `root provider receives injected factory and creation extras`() {
        val parent = ParentOwner()
        val owner = ProfileNavigationViewModelStoreOwner(parent)
        try {
            assertSame(parent.defaultViewModelProviderFactory, owner.defaultViewModelProviderFactory)
            assertSame(parent.defaultViewModelCreationExtras, owner.defaultViewModelCreationExtras)
            val provider = ViewModelProvider(owner)
            val model = provider[InjectedViewModel::class.java]
            assertSame(parent.dependency, model.dependency)
            assertSame(model, ViewModelProvider(owner)[InjectedViewModel::class.java])
        } finally {
            owner.viewModelStore.clear()
            parent.viewModelStore.clear()
        }
    }

    @Test
    fun `profile exit clears scoped models without clearing activity or next profile`() {
        val parent = ParentOwner()
        val first = ProfileNavigationViewModelStoreOwner(parent)
        val second = ProfileNavigationViewModelStoreOwner(parent)
        try {
            val activityModel = ViewModelProvider(parent)[InjectedViewModel::class.java]
            val firstModel = ViewModelProvider(first)[InjectedViewModel::class.java]
            val secondModel = ViewModelProvider(second)[InjectedViewModel::class.java]
            assertNotSame(activityModel, firstModel)
            assertNotSame(firstModel, secondModel)
            first.viewModelStore.clear()
            assertTrue(firstModel.cleared)
            assertFalse(activityModel.cleared)
            assertFalse(secondModel.cleared)
        } finally {
            first.viewModelStore.clear()
            second.viewModelStore.clear()
            parent.viewModelStore.clear()
        }
    }
}
