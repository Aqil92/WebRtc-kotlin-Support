package com.aqil.webrtc.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup


/**
 * Simple container that confines the children to a subrectangle specified as percentage values of
 * the container size. The children are centered horizontally and vertically inside the confined
 * space.
 */
class PercentFrameLayout : ViewGroup {
    private var xPercent = 0
    private var yPercent = 0
    private var widthPercent = 100
    private var heightPercent = 100

    constructor(context: Context) : super(context) {}

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {}

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setPosition(xPercent: Int, yPercent: Int, widthPercent: Int, heightPercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.widthPercent = widthPercent
        this.heightPercent = heightPercent
    }

    override fun shouldDelayChildPressedState(): Boolean {
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.getDefaultSize(Integer.MAX_VALUE, widthMeasureSpec)
        val height = View.getDefaultSize(Integer.MAX_VALUE, heightMeasureSpec)
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )

        val childWidthMeasureSpec =
            MeasureSpec.makeMeasureSpec(width * widthPercent / 100, MeasureSpec.AT_MOST)
        val childHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(height * heightPercent / 100, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        // Sub-rectangle specified by percentage values.
        val subWidth = width * widthPercent / 100
        val subHeight = height * heightPercent / 100
        val subLeft = left + width * xPercent / 100
        val subTop = top + height * yPercent / 100

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight

                val childLeft = subLeft + (subWidth - childWidth) / 2
                val childTop = subTop + (subHeight - childHeight) / 2
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            }
        }
    }
}
