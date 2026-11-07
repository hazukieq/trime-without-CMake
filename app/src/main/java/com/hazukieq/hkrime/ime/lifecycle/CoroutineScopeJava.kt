package com.hazukieq.hkrime.ime.lifecycle

import kotlinx.coroutines.MainScope

object CoroutineScopeJava {
    @JvmStatic
    val MainScopeJava
        get() = MainScope()
}
