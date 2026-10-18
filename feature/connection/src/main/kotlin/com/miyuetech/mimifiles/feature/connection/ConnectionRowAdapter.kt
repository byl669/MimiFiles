package com.miyuetech.mimifiles.feature.connection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.feature.connection.databinding.ItemConnectionBinding

/**
 * 连接列表适配器。
 *
 * 用 ViewBinding + `ListAdapter` 之外的写法是一致的：本列表**不会**有几百条
 * （那是连接不是文件），因此不需要 DiffUtil 的复杂度；直接换 list 引用、
 * 全量 `notifyDataSetChanged` 在这个规模上无感，代码却少一半。
 *
 * 真正的理由是**正确性**：这几十条记录里"哪一条变了"从来不是问题，
 * 而 DiffUtil 的 `areItemsTheSame`/`areContentsTheSame` 写错会带来
 * "改了名字但列表没更新"这类问题——用一个不会出错的方案，比用一个更炫的方案好。
 */
internal class ConnectionRowAdapter(
    private val onOpen: (ConnectionProfile) -> Unit,
    private val onMenu: (anchor: View, profile: ConnectionProfile) -> Unit,
) : RecyclerView.Adapter<ConnectionRowAdapter.Row>() {

    private var items: List<ConnectionProfile> = emptyList()

    fun submit(list: List<ConnectionProfile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val binding = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Row(binding)
    }

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(items[position])

    inner class Row(private val binding: ItemConnectionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: ConnectionProfile) {
            binding.name.text = ConnectionFormats.fallbackLabel(profile)
            binding.endpoint.text = ConnectionFormats.subtitle(profile)

            val badgeRes = ConnectionFormats.badgeRes(profile)
            binding.badge.visibility = if (badgeRes == null) View.GONE else View.VISIBLE
            binding.badge.text = badgeRes?.let { binding.root.context.getString(it) }.orEmpty()

            // 整行点 = 编辑（列表页里最常做的动作）；测试与删除收进溢出菜单，
            // 因为它们都会产生副作用（一个发网络请求、一个不可逆）。
            binding.root.setOnClickListener { onOpen(profile) }
            binding.more.setOnClickListener { anchor -> onMenu(anchor, profile) }
        }
    }
}
