package ani.saikou.tv.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.KeyEvent
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.leanback.widget.PlaybackControlsRow.*
import androidx.leanback.widget.PlaybackControlsRowPresenter
import ani.saikou.R
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter
import java.util.concurrent.*

private class SpeedLabelDrawable(context: Context, initialLabel: String) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var label = initialLabel

    fun setLabel(value: String) {
        label = value
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val maxTextHeight = bounds.height() * 0.82f
        val maxTextWidth = bounds.width() * 0.94f
        textPaint.textSize = maxTextHeight
        val measuredWidth = textPaint.measureText(label)
        if (measuredWidth > maxTextWidth && measuredWidth > 0f) {
            textPaint.textSize *= maxTextWidth / measuredWidth
        }
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, x, y, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        textPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (64f * density).toInt()

    override fun getIntrinsicHeight(): Int = (40f * density).toInt()
}

class SpeedAction(context: Context, initialLabel: String) : Action(1234567894) {
    private val labelDrawable = SpeedLabelDrawable(context, initialLabel)

    init {
        icon = labelDrawable
        label1 = "Speed"
    }

    fun setSpeedLabel(label: String) = labelDrawable.setLabel(label)
}

@SuppressLint("UseCompatLoadingForDrawables")
class ResizeAction(context: Context?): Action(1234567891) {
    init {
        icon = context?.getDrawable(R.drawable.ic_round_fullscreen_24)
        label1 = "Resize"
    }
}
class QualityAction(context: Context?): Action(1234567892) {
    init {
        icon = context?.getDrawable(R.drawable.ic_round_high_quality_24)
        label1 = "Quality"
    }
}

class Skip85Action(context: Context?): Action(1234567893) {
    init {
        icon = context?.getDrawable(R.drawable.ic_skip85)
        label1 = "+85s Skip"
    }
}

class VideoPlayerGlue(
    context: Context?,
    playerAdapter: LeanbackPlayerAdapter?,
    val showQualityAction: Boolean,
    private val mActionListener: OnActionClickedListener,
    initialSpeedLabel: String = "1x"
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter?>(context, playerAdapter) {
    /** Listens for when skip to next and previous actions have been dispatched.  */
    interface OnActionClickedListener {
        /** Skip to the previous item in the queue.  */
        fun onPrevious()

        /** Skip to the next item in the queue.  */
        fun onNext()

        fun onResize()

        fun onQuality()

        fun onSpeed()

        fun onPlayerPause()

    }

    //private val mRepeatAction: RepeatAction
    //private val mThumbsUpAction: ThumbsUpAction
    private val qualityAction: QualityAction
    private val speedAction: SpeedAction
    var shouldShowQualityAction: Boolean = false
    private val resizeAction: ResizeAction
    var shouldShowResizeAction: Boolean = false
    private val mSkipPreviousAction: SkipPreviousAction
    private val mSkipNextAction: SkipNextAction
    private val mFastForwardAction: FastForwardAction
    private val mSkip85Action: Skip85Action
    private val mRewindAction: RewindAction
    override fun onCreatePrimaryActions(adapter: ArrayObjectAdapter) {
        // Order matters, super.onCreatePrimaryActions() will create the play / pause action.
        // Will display as follows:
        // play/pause, previous, rewind, fast forward, next
        //   > /||      |<        <<        >>         >|
        super.onCreatePrimaryActions(adapter)
        adapter.add(mSkipPreviousAction)
        adapter.add(mRewindAction)
        adapter.add(mFastForwardAction)
        adapter.add(mSkip85Action)
        adapter.add(mSkipNextAction)
    }

    override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
        super.onCreateSecondaryActions(adapter)
        adapter.add(resizeAction)
        if(showQualityAction)
            adapter.add(qualityAction)
        adapter.add(speedAction)
    }

    //TODO trying to draw actions only if needed
    /*fun drawSecondaryActions() {
        val adapter = ArrayObjectAdapter(PlaybackControlsRowPresenter())
        if(shouldShowQualityAction) {
            adapter.add(qualityAction)
        }
        if(shouldShowResizeAction) {
            adapter.add(resizeAction)
        }
        controlsRow.secondaryActionsAdapter = adapter
        controlsRow = controlsRow
    }*/

    override fun onActionClicked(action: Action) {
        if (shouldDispatchAction(action)) {
            dispatchAction(action)
            return
        }

        // Super class handles play/pause and delegates to abstract methods next()/previous().
        super.onActionClicked(action)

        //Extra callbacks
        if (action.respondsToKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            mActionListener.onPlayerPause()
        }
    }

    // Should dispatch actions that the super class does not supply callbacks for.
    private fun shouldDispatchAction(action: Action): Boolean {
        return action === mRewindAction || action === mFastForwardAction || action === mSkip85Action || action === resizeAction || action === qualityAction || action === speedAction
    }

    private fun dispatchAction(action: Action) {
        // Primary actions are handled manually.
        if (action === mRewindAction) {
            rewind()
        } else if (action === mFastForwardAction) {
            fastForward()
        } else if (action === mSkip85Action) {
            skip85()
        } else if (action === resizeAction) {
            mActionListener.onResize()
        } else if (action === qualityAction) {
            mActionListener.onQuality()
        } else if (action === speedAction) {
            mActionListener.onSpeed()
        } else if (action is MultiAction) {
            val multiAction = action
            multiAction.nextIndex()
            // Notify adapter of action changes to handle secondary actions, such as, thumbs up/down
            // and repeat.
            notifyActionChanged(
                multiAction,
                controlsRow.secondaryActionsAdapter as ArrayObjectAdapter
            )
        }
    }

    private fun notifyActionChanged(action: Action, adapter: ArrayObjectAdapter?) {
        if (adapter != null) {
            val index = adapter.indexOf(action)
            if (index >= 0) {
                adapter.notifyArrayItemRangeChanged(index, 1)
            }
        }
    }

    fun updateSpeedLabel(label: String) {
        speedAction.setSpeedLabel(label)
        notifyActionChanged(speedAction, controlsRow.secondaryActionsAdapter as? ArrayObjectAdapter)
    }

    override fun next() {
        mActionListener.onNext()
    }

    override fun previous() {
        mActionListener.onPrevious()
    }

    /** Skips backwards 10 seconds.  */
    fun rewind() {
        var newPosition = currentPosition - TEN_SECONDS
        newPosition = if (newPosition < 0) 0 else newPosition
        playerAdapter!!.seekTo(newPosition)
    }

    /** Skips forward 10 seconds.  */
    fun fastForward() {
        if (duration > -1) {
            var newPosition = currentPosition + TEN_SECONDS
            newPosition = if (newPosition > duration) duration else newPosition
            playerAdapter!!.seekTo(newPosition)
        }
    }
    /** Skips forward 85 seconds.  */
    fun skip85() {
        if (duration > -1) {
            var newPosition = currentPosition + SKIP_85
            newPosition = if (newPosition > duration) duration else newPosition
            playerAdapter!!.seekTo(newPosition)
        }
    }

    companion object {
        private val TEN_SECONDS = TimeUnit.SECONDS.toMillis(10)
        private val SKIP_85 = TimeUnit.SECONDS.toMillis(85)
    }

    init {
        mSkipPreviousAction = SkipPreviousAction(context)
        mSkipNextAction = SkipNextAction(context)
        mFastForwardAction = FastForwardAction(context)
        mSkip85Action = Skip85Action(context)
        mRewindAction = RewindAction(context)
        resizeAction = ResizeAction(context)
        qualityAction = QualityAction(context)
        speedAction = SpeedAction(context!!, initialSpeedLabel)
    }
}
