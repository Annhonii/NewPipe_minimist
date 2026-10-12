package org.schabi.newpipe.fragments.list.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentHomeBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.fragments.BlankFragment
import org.schabi.newpipe.fragments.list.kiosk.KioskFragment
import org.schabi.newpipe.fragments.list.kiosk.TrendingSearchFragment
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.ThemeHelper

/**
 * Home tab: a row of category pills (All, Gaming, Podcast, Music, ...) on top of the page.
 * "All" shows the recommended home feed, every other pill opens the trending videos of that
 * category. This replaces the separate "Trending" tab of the bottom bar.
 */
class HomeFragment : BaseFragment() {

    /** One pill. [query] is null for "All" (home feed) and for "Live" (the default kiosk). */
    private class Category(val titleRes: Int, val query: String?, val isLive: Boolean = false)

    private var binding: FragmentHomeBinding? = null
    private var selected = 0
    private val chips = ArrayList<TextView>()

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
        setTitle(getString(R.string.yt_home_title))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chips.clear()
        binding = null
    }

    private fun select(index: Int) {
        if (index == selected) {
            return
        }
        selected = index
        updateChipStyles()
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

    /** Dark: dark grey pill with a thin border, selected = white. Light: the inverse. */
    private fun updateChipStyles() {
        val context = context ?: return
        val light = ThemeHelper.isLightThemeSelected(context)
        val density = context.resources.displayMetrics.density

        val idleFill = if (light) 0xFFF2F2F2.toInt() else 0xFF242424.toInt()
        val idleStroke = if (light) 0xFFD6D6D6.toInt() else 0xFF4A4A4A.toInt()
        val idleText = if (light) 0xFF0F0F0F.toInt() else Color.WHITE
        val selectedFill = if (light) 0xFF0F0F0F.toInt() else Color.WHITE
        val selectedText = if (light) Color.WHITE else 0xFF0F0F0F.toInt()

        chips.forEachIndexed { index, chip ->
            val isSelected = index == selected
            chip.background = GradientDrawable().apply {
                cornerRadius = DeviceUtils.dpToPx(100, context).toFloat()
                setColor(if (isSelected) selectedFill else idleFill)
                // the selected pill has no border, like a filled button
                setStroke(if (isSelected) 0 else Math.round(density), idleStroke)
            }
            chip.setTextColor(if (isSelected) selectedText else idleText)
            chip.isSelected = isSelected
        }
    }

    companion object {
        private const val KEY_SELECTED = "home_selected_category"

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
