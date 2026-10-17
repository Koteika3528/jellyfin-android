@file:Suppress("NOTHING_TO_INLINE")

package org.jellyfin.mobile.utils.extensions

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import androidx.fragment.app.replace
import org.jellyfin.mobile.R

/** How a screen comes and goes: fades in and out ([R.anim.screen_enter]), or as a sheet from the bottom. */
enum class ScreenAnimation(val enter: Int, val exit: Int) {
    FADE(R.anim.screen_enter, R.anim.screen_exit),
    SHEET(R.anim.sheet_enter, R.anim.sheet_exit),
}

inline fun <reified T : Fragment> FragmentManager.addFragment(args: Bundle? = null, animation: ScreenAnimation = ScreenAnimation.FADE) {
    beginTransaction().apply {
        // The screen below stays (it is added over it): only the new one moves, also when it goes back
        setCustomAnimations(animation.enter, 0, 0, animation.exit)
        add<T>(R.id.fragment_container, args = args)
        addToBackStack(null)
    }.commit()
}

inline fun <reified T : Fragment> FragmentManager.replaceFragment(args: Bundle? = null) {
    beginTransaction().replace<T>(R.id.fragment_container, args = args).commit()
}
