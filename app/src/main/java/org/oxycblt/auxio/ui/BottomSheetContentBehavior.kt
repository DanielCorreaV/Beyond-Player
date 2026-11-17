/*
 * Copyright (c) 2022 Auxio Project
 * BottomSheetContentBehavior.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import kotlin.math.abs
import org.oxycblt.auxio.util.coordinatorLayoutBehavior
import org.oxycblt.auxio.util.replaceSystemBarInsetsCompat
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * A behavior that automatically re-layouts and re-insets content to align with the parent layout's
 * bottom sheet. Ideally, we would only want to re-inset content, but that has too many issues to
 * sensibly implement.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class BottomSheetContentBehavior<V : View>(context: Context, attributeSet: AttributeSet?) :
    CoordinatorLayout.Behavior<V>(context, attributeSet) {
    private var dep: View? = null
    private var lastInsets: WindowInsets? = null
    private var lastConsumed = -1
    private var setup = false
    private var childView: View? = null

    /** Extra room at the bottom for the keyboard; only the main content uses it. */
    private fun lift(child: View) = if (child is EatInsetsFrameLayout) keyboardLift else 0

    /** Re-apply the insets to the content now (the keyboard moved). */
    private fun refresh() {
        val child = childView ?: return
        lastConsumed = -1
        val insets = lastInsets
        if (insets != null) child.dispatchApplyWindowInsets(insets) else child.requestApplyInsets()
    }


    override fun layoutDependsOn(parent: CoordinatorLayout, child: V, dependency: View): Boolean {
        if (dependency.coordinatorLayoutBehavior is BackportBottomSheetBehavior) {
            dep = dependency
            return true
        }

        return false
    }

    override fun onLayoutChild(parent: CoordinatorLayout, child: V, layoutDirection: Int): Boolean {
        super.onLayoutChild(parent, child, layoutDirection)
        childView = child
        live.add(this)
        layoutContent(child)


        if (!setup) {
            child.setOnApplyWindowInsetsListener { _, insets ->
                lastInsets = insets

                val dep = dep ?: return@setOnApplyWindowInsetsListener insets
                val behavior = dep.coordinatorLayoutBehavior as BackportBottomSheetBehavior
                val consumed = behavior.calculateConsumedByBar()
                if (consumed == Int.MIN_VALUE) {
                    return@setOnApplyWindowInsetsListener insets
                }

                val bars = insets.systemBarInsetsCompat
                insets.replaceSystemBarInsetsCompat(
                    bars.left,
                    bars.top,
                    bars.right,
                    (consumed + lift(child)).coerceAtLeast(bars.bottom),

                )
            }

            setup = true
        }

        // Critical: seed / refresh inset dispatch after listener installation.
        child.requestApplyInsets()
        return true
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: V,
        dependency: View,
    ): Boolean {
        val behavior = dependency.coordinatorLayoutBehavior as BackportBottomSheetBehavior
        val consumed = behavior.calculateConsumedByBar()
        if (consumed == Int.MIN_VALUE) {
            return false
        }

        val key = consumed + lift(child)
        if (key != lastConsumed) {
            lastConsumed = key


            if (lastInsets != null) {
                child.dispatchApplyWindowInsets(lastInsets)
            } else {
                child.requestApplyInsets()
            }

            measureContent(parent, child)
            layoutContent(child)
            return true
        }

        return false
    }

    override fun onMeasureChild(
        parent: CoordinatorLayout,
        child: V,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ): Boolean {
        measureContent(parent, child)
        return true
    }

    private fun measureContent(parent: View, child: View) {
        val contentWidthSpec =
            View.MeasureSpec.makeMeasureSpec(parent.measuredWidth, View.MeasureSpec.EXACTLY)
        val contentHeightSpec =
            View.MeasureSpec.makeMeasureSpec(parent.measuredHeight, View.MeasureSpec.EXACTLY)
        child.measure(contentWidthSpec, contentHeightSpec)
    }

    private fun layoutContent(child: View) {
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    companion object {
        private var keyboardLift = 0
        private val live =
            java.util.Collections.newSetFromMap(
                java.util.WeakHashMap<BottomSheetContentBehavior<*>, Boolean>()
            )

        /** Tell every content view how tall the keyboard is (0 when it is closed). */
        fun setKeyboardLift(px: Int) {
            if (px == keyboardLift) return
            keyboardLift = px
            live.toList().forEach { it.refresh() }
        }
    }

    private fun BackportBottomSheetBehavior<*>.calculateConsumedByBar(): Int {
        val offset = calculateSlideOffset()
        if (offset == Float.MIN_VALUE || peekHeight < 0) {
            return Int.MIN_VALUE
        }

        return if (offset >= 0) {
            peekHeight
        } else {
            (peekHeight * (1 - abs(offset))).toInt()
        }
    }
}
