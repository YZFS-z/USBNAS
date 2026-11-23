package com.littlesauce.usbnaslink

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 服务列表。条目极少（通常 1 个），所以不搞 DiffUtil/分页，
 * 直接 ArrayList + notifyDataSetChanged —— 简单到不会坏。
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

        val dot = when {
            si.key() == connectedKey() -> Color.parseColor("#22A45D")
            si.needLogin -> Color.parseColor("#2F6FED")
            else -> Color.parseColor("#C9CDD4")
        }
        holder.dot.setBackgroundColor(dot)
        holder.itemView.setOnClickListener { onPick(si) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.dot)
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
    }
}
