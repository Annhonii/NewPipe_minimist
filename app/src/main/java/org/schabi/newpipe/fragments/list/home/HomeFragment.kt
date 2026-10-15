package org.schabi.newpipe.fragments.list.home

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentHomeBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.BlankFragment
import org.schabi.newpipe.fragments.Refreshable
import org.schabi.newpipe.fragments.list.kiosk.KioskFragment
import org.schabi.newpipe.fragments.list.kiosk.TrendingSearchFragment
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.ThemeHelper

/**
 * Home tab: a row of category pills (All, Gaming, Podcast, Music, ...) on top of the page.
 * "All" shows the recommended home feed, every other pill opens the trending videos of that
 * category. This replaces the separate "Trending" tab of the bottom bar.
 */
class HomeFragment : BaseFragment(), Refreshable {

    /** Double tap on the Home tab of the navigation pill: reload the page that is shown. */
    override fun refreshFromNavBar() {
        when (val page = childFragmentManager.findFragmentById(R.id.home_content)) {
            is HomeFeedFragment -> page.refreshFromNavBar()
            is BaseStateFragment<*> -> page.reloadContent()
            else -> Unit
        }
    }


    /** One pill. [query] is null for "All" (home feed) and for "Live" (the default kiosk). */
    private class Category(val titleRes: Int, val query: String?, val isLive: Boolean = false)

    private var binding: FragmentHomeBinding? = null
    private var selected = 0
    private val chips = ArrayList<TextView>()
    private val chipAnimators = HashMap<TextView, ValueAnimator>()
    private var chipFills = IntArray(0)
    private var chipTexts = IntArray(0)
    private var chipStrokes = IntArray(0)

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        selected = savedInstanceState.getInt(KEY_SELECTED, 0).coerceIn(0, CATEGORIES.size - 1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED, selected)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val b = FragmentHomeBinding.bind(rootView)
        binding = b

        chips.clear()
        chipFills = IntArray(0)
        chipTexts = IntArray(0)
        chipStrokes = IntArray(0)
        b.homeChips.removeAllViews()
        CATEGORIES.forEachIndexed { index, category ->
            val chip = layoutInflater.inflate(R.layout.item_home_chip, b.homeChips, false) as TextView
            chip.setText(category.titleRes)
            chip.setOnClickListener { select(index) }
            b.homeChips.addView(chip)
            chips.add(chip)
        }
        updateChipStyles()

        // after a rotation / coming back from another screen the child page is restored by itself
        if (childFragmentManager.findFragmentById(R.id.home_content) == null) {
            showPage(selected)
        }
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.main_toolbar_title))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chipAnimators.values.forEach { it.cancel() }
        chipAnimators.clear()
        chipFills = IntArray(0)
        chipTexts = IntArray(0)
        chipStrokes = IntArray(0)
        chips.clear()
        binding = null
    }

    private fun select(index: Int) {
        if (index == selected) {
            return
        }
        selected = index
        updateChipStyles(animate = true)
        showPage(index)
        binding?.homeChipsScroll?.let { scroll ->
            // keep the tapped pill visible when it is partly outside the screen
            chips.getOrNull(index)?.let { chip ->
                scroll.post { scroll.smoothScrollTo(chip.left - scroll.paddingStart * 2, 0) }
            }
        }
    }

    private fun showPage(index: Int) {
        if (!isAdded) {
            return
        }
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.home_content, createPage(CATEGORIES[index]))
            .commitAllowingStateLoss()
    }

    private fun createPage(category: Category): Fragment {
        val serviceId = ServiceList.YouTube.serviceId
        val query = category.query
        val fragment: BaseFragment = try {
            when {
                category.isLive -> KioskFragment.getInstance(serviceId).also {
                    it.setTitleOverride(getString(R.string.yt_home_title))
                }
                query == null -> HomeFeedFragment()
                else -> TrendingSearchFragment.getInstance(
                    serviceId,
                    query,
                    getString(category.titleRes)
                )
            }
        } catch (e: Exception) {
            return BlankFragment()
        }
        // this fragment is the front page: the child pages must not touch the toolbar title
        fragment.useAsFrontPage(true)
        return fragment
    }

    /**
     * Filter chips like the design: rounded rectangles on the bar. Idle = transparent with a thin
     * outline, selected = filled with the text colour (inverted: page colour as text). Coloured
     * from the app theme, so every theme works. Fill, outline and text cross-fade when the
     * selection moves, instead of snapping.
     */
    private fun updateChipStyles(animate: Boolean = false) {
        val context = context ?: return

        val onSurface = ThemeHelper.resolveColorFromAttr(context, android.R.attr.textColorPrimary)
        val onInverse = ThemeHelper.resolveColorFromAttr(context, R.attr.toolbar_container_color)
        // transparent in the same hue as the selected fill, so the cross-fade stays clean
        val idleFill = ColorUtils.setAlphaComponent(onSurface, 0)
        val idleStroke = ColorUtils.setAlphaComponent(onSurface, IDLE_STROKE_ALPHA)
        val strokeWidth = DeviceUtils.dpToPx(1, context).coerceAtLeast(1)

        if (chipFills.size != chips.size) {
            chipFills = IntArray(chips.size) { idleFill }
            chipTexts = IntArray(chips.size) { onSurface }
            chipStrokes = IntArray(chips.size) { idleStroke }
        }

        chips.forEachIndexed { index, chip ->
            val isSelected = index == selected
            chip.isSelected = isSelected
            val toFill = if (isSelected) onSurface else idleFill
            val toText = if (isSelected) onInverse else onSurface
            val toStroke = if (isSelected) onSurface else idleStroke

            val background = (chip.background as? GradientDrawable) ?: GradientDrawable().also {
                it.cornerRadius = DeviceUtils.dpToPx(CHIP_CORNER_DP, context).toFloat()
                chip.background = it
            }
            chipAnimators.remove(chip)?.cancel()

            val fromFill = chipFills[index]
            val fromText = chipTexts[index]
            val fromStroke = chipStrokes[index]
            if (!animate || (fromFill == toFill && fromText == toText && fromStroke == toStroke)) {
                background.setColor(toFill)
                background.setStroke(strokeWidth, toStroke)
                chip.setTextColor(toText)
            } else {
                chipAnimators[chip] = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 240L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        val t = animator.animatedFraction
                        background.setColor(ColorUtils.blendARGB(fromFill, toFill, t))
                        background.setStroke(
                            strokeWidth,
                            ColorUtils.blendARGB(fromStroke, toStroke, t)
                        )
                        chip.setTextColor(ColorUtils.blendARGB(fromText, toText, t))
                    }
                    start()
                }
            }
            chipFills[index] = toFill
            chipTexts[index] = toText
            chipStrokes[index] = toStroke
        }
    }

    companion object {
        private const val KEY_SELECTED = "home_selected_category"
        private const val CHIP_CORNER_DP = 12
        private const val IDLE_STROKE_ALPHA = 0x40

        // The order is the one of the design: All, Gaming, Podcast, Music, Movies, (Live).
        // YouTube retired its own Trending page, so these are searches ("trending ...").
        private val CATEGORIES = listOf(
            Category(R.string.home_chip_all, null),
            Category(R.string.home_chip_gaming, "gaming trending"),
            Category(R.string.home_chip_podcast, "trending podcast"),
            Category(R.string.home_chip_music, "trending music videos"),
            Category(R.string.home_chip_movies, "new movie trailers trending"),
            Category(R.string.duration_live, null, isLive = true)
        )
    }
}
