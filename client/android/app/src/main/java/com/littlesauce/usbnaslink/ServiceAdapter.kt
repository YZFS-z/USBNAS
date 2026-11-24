package com.littlesauce.usbnaslink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 服务列表。条目极少（通常 1 个），所以不搞 DiffUtil/分页，
 * 直接 ArrayList + notifyDataSetChanged —— 简单到不会坏。
 *
 * 颜色全部现场取 Palette：`item_service.xml` 里的 #161B22 / #E6E9EF 只是
 * 夜间默认值，日间模式下如果不在 onBind 里重涂一遍，就会出现
 * 「浅底上一块深卡片」的割裂感。
 */
class ServiceAdapter(
    private val onPick: (ServiceInfo) -> Unit,
    private val connectedKey: () -> String?
) : RecyclerView.Adapter<ServiceAdapter.VH>() {

    private val items = ArrayList<ServiceInfo>()

    fun submit(list: List<ServiceInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(si: ServiceInfo) {
        for (i in items.indices) {
            val o = items[i]
            if (o.key() == si.key()) {
                items[i] = si
                notifyItemChanged(i)
                return
            }
        }
        items.add(si)
        notifyItemInserted(items.size - 1)
    }

    fun all(): List<ServiceInfo> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_service, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val si = items[position]
        holder.name.text = si.displayName()

        val bits = ArrayList<String>(3)
        bits.add(si.hostPort())
        if (si.rttMs in 1..9999) bits.add("${si.rttMs}ms")
        if (!si.disksText.isNullOrEmpty()) bits.add(si.disksText)
        holder.detail.text = bits.joinToString(" · ")

        // 状态点：已连接绿 / 需登录蓝 / 未知灰（色值统一走 Palette，别再散字面量）
        val dot = when {
            si.key() == connectedKey() -> Palette.OK
            si.needLogin -> Palette.ACCENT
            else -> Palette.DIM
        }
        val ctx = holder.dot.context
        holder.dot.background = Palette.pill(ctx, dot, 7)

        // 卡片底与文字也按当前主题重涂（XML 里是夜间的死色）
        holder.itemView.background = Palette.round(ctx, Palette.CARD, 12f, Palette.LINE, 1f)
        holder.name.setTextColor(Palette.FG)
        holder.detail.setTextColor(Palette.DIM2)
        holder.chevron.setTextColor(Palette.FAINT)

        holder.itemView.setOnClickListener { onPick(si) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.dot)
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val chevron: TextView = v.findViewById(R.id.chevron)
    }
}
