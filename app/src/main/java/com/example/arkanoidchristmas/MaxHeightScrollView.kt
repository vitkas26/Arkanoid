package com.example.arkanoidchristmas

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** ScrollView that never grows beyond [maxHeightPx], so dialog buttons below it stay reachable. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    var maxHeightPx: Int = 1 shl 29

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(
                minOf(maxHeightPx, View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maxHeightPx),
                View.MeasureSpec.AT_MOST,
            ),
        )
    }
}
