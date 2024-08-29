/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.tools.minibrowser.settings.clearbrowsingdata

import android.content.Context
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import org.wpewebkit.tools.minibrowser.R
import org.wpewebkit.tools.minibrowser.databinding.ClearBrowsingDataItemBinding

class ClearBrowsingDataItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var binding: ClearBrowsingDataItemBinding

    val titleView: TextView
        get() = binding.title

    val subtitleView: TextView
        get() = binding.subtitle

    var isChecked: Boolean
        get() = binding.checkbox.isChecked
        set(value) {
            binding.checkbox.isChecked = value
        }

    var onCheckListener: ((Boolean) -> Unit)? = null

    init {
        val view =
            LayoutInflater.from(context).inflate(R.layout.clear_browsing_data_item, this, true)

        binding = ClearBrowsingDataItemBinding.bind(view)

        setOnClickListener {
            binding.checkbox.isChecked = !binding.checkbox.isChecked
        }

        binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
            onCheckListener?.invoke(isChecked)
        }

        context.withStyledAttributes(attrs, R.styleable.ClearBrowsingDataItem, defStyleAttr, 0) {
            val iconId = getResourceId(R.styleable.ClearBrowsingDataItem_clearBrowsingDataItemIcon, 0)
            val titleId = getResourceId(
                R.styleable.ClearBrowsingDataItem_clearBrowsingDataItemTitle,
                R.string.empty_string
            )
            val subtitleId = getResourceId(
                R.styleable.ClearBrowsingDataItem_clearBrowsingDataItemSubtitle,
                R.string.empty_string,
            )


            setLeadingIcon(AppCompatResources.getDrawable(context, iconId))
            binding.title.text = resources.getString(titleId)
            val subtitleText = resources.getString(subtitleId)
            binding.subtitle.text = subtitleText
            if (subtitleText.isBlank()) binding.subtitle.visibility = View.GONE
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private fun setLeadingIcon(icon: Drawable?) {
        binding.icon.setImageDrawable(icon)
        invalidate();
    }

    private companion object {
        private const val ENABLED_ALPHA = 1f
        private const val DISABLED_ALPHA = 0.6f

        fun getDrawable(context: Context, attributes: TypedArray, index: Int) : Drawable? {
            if (attributes.hasValue(index)) {
                val resourceId = attributes.getResourceId(index, 0)
                if (resourceId != 0) {
                    val value = AppCompatResources.getDrawable(context, resourceId)
                    if (value != null) {
                        return value
                    }
                }
            }
            return attributes.getDrawable(index)
        }
    }
}
