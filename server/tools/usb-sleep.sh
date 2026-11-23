#!/system/bin/sh
# usb-sleep.sh —— 让外接 USB 存储（尤其是机械硬盘）空闲后自动停转
#
# 背景：默认情况下 USB 存储的 power/control 是 on（runtime PM 被钉死），
# 硬盘永远转着，机械盘会有持续的嗡嗡声和振动。
#
# 改成 auto + 一段空闲延时之后，内核的 runtime PM 会在没有 I/O 时
# 把设备挂起。要真正让盘停转（而不只是总线挂起），还必须把
# manage_start_stop 置 1 —— 它让 SCSI 层在挂起时下发 START STOP UNIT。
#
# 有 I/O 时内核会自动把设备唤醒（实测 dd 读一个字节就恢复 active），
# 所以这个功能对正常使用是无感的。
#
# 用法：
#   sh usb-sleep.sh status          看每块盘的当前状态
#   sh usb-sleep.sh on [秒]         开启自动休眠（默认 60 秒空闲）
#   sh usb-sleep.sh off             关闭自动休眠（恢复常转）
#   sh usb-sleep.sh once            临时挂起一次（立刻安静，下次 I/O 唤醒）

DELAY_DEFAULT=60

# 列出所有 USB 上的块设备。内部 UFS 分区的路径里有 ufshc，USB 的是 xhci/usbN。
usb_disks() {
    for d in /sys/block/sd*; do
        [ -d "$d" ] || continue
        link=$(readlink -f "$d")
        case "$link" in
            *xhci*|*usb*) echo "$(basename "$d")" ;;
        esac
    done
}

# 找到块设备对应的 USB 设备节点（逐级往上找带 idVendor 的那层）
usb_dev_node() {
    p="/sys/block/$1"
    while [ "$p" != "/" ]; do
        if [ -f "$p/idVendor" ]; then
            echo "$p"
            return 0
        fi
        p=$(dirname "$p")
    done
    return 1
}

do_status() {
    echo "=== USB 存储电源状态 ==="
    any=0
    for n in $(usb_disks); do
        any=1
        dev="/sys/block/$n/device"
        ctl=$(cat "$dev/power/control" 2>/dev/null || echo "?")
        st=$(cat "$dev/power/runtime_status" 2>/dev/null || echo "?")
        dl=$(cat "$dev/power/autosuspend_delay_ms" 2>/dev/null || echo "?")
        mss=$(cat "$dev"/scsi_disk/*/manage_start_stop 2>/dev/null || echo "?")
        rot=$(cat "/sys/block/$n/queue/rotational" 2>/dev/null || echo "?")
        mdl=$(cat "$dev/model" 2>/dev/null | tr -d ' ')
        size=$(cat "/sys/block/$n/size" 2>/dev/null)
        gb=$((size / 2 / 1024 / 1024))
        kind="固态"
        [ "$rot" = "1" ] && kind="机械"
        echo "$n  ${mdl:-?}  ${gb}G  $kind"
        echo "    control=$ctl  status=$st  delay=${dl}ms  manage_start_stop=$mss"
    done
    [ "$any" = "0" ] && echo "（没有检测到外接 USB 存储）"
}

do_on() {
    delay=${1:-$DELAY_DEFAULT}
    echo "开启自动休眠，空闲 ${delay} 秒后挂起"
    for n in $(usb_disks); do
        dev="/sys/block/$n/device"
        # 先把 SCSI 层的启停管理打开，否则挂起时盘不会真停
        for f in "$dev"/scsi_disk/*/manage_start_stop; do
            [ -f "$f" ] && echo 1 > "$f" 2>/dev/null
        done
        echo "$delay"000 > "$dev/power/autosuspend_delay_ms" 2>/dev/null
        echo auto > "$dev/power/control" 2>/dev/null
        # 上游 USB 设备/接口节点也要放开，否则子设备永远被父设备持住
        u=$(usb_dev_node "$n")
        if [ -n "$u" ]; then
            echo auto > "$u/power/control" 2>/dev/null
            for ifc in "$u"/*:*.*; do
                [ -f "$ifc/power/control" ] && echo auto > "$ifc/power/control" 2>/dev/null
            done
        fi
        echo "  $n -> control=$(cat "$dev/power/control" 2>/dev/null) delay=$(cat "$dev/power/autosuspend_delay_ms" 2>/dev/null)"
    done
}

do_off() {
    echo "关闭自动休眠（保持常转）"
    for n in $(usb_disks); do
        dev="/sys/block/$n/device"
        echo on > "$dev/power/control" 2>/dev/null
        for f in "$dev"/scsi_disk/*/manage_start_stop; do
            [ -f "$f" ] && echo 0 > "$f" 2>/dev/null
        done
        u=$(usb_dev_node "$n")
        if [ -n "$u" ]; then
            echo on > "$u/power/control" 2>/dev/null
            for ifc in "$u"/*:*.*; do
                [ -f "$ifc/power/control" ] && echo on > "$ifc/power/control" 2>/dev/null
            done
        fi
        echo "  $n -> control=$(cat "$dev/power/control" 2>/dev/null)"
    done
}

do_once() {
    # 立刻挂起：把 auto 打开、延时清零，等内核自己收敛
    echo "尝试立即挂起 USB 存储"
    for n in $(usb_disks); do
        dev="/sys/block/$n/device"
        for f in "$dev"/scsi_disk/*/manage_start_stop; do
            [ -f "$f" ] && echo 1 > "$f" 2>/dev/null
        done
        echo 0 > "$dev/power/autosuspend_delay_ms" 2>/dev/null
        echo auto > "$dev/power/control" 2>/dev/null
        u=$(usb_dev_node "$n")
        if [ -n "$u" ]; then
            echo auto > "$u/power/control" 2>/dev/null
            for ifc in "$u"/*:*.*; do
                [ -f "$ifc/power/control" ] && echo auto > "$ifc/power/control" 2>/dev/null
            done
        fi
    done
    sleep 3
    for n in $(usb_disks); do
        echo "  $n -> $(cat "/sys/block/$n/device/power/runtime_status" 2>/dev/null)"
    done
}

case "$1" in
    on)     do_on "$2" ;;
    off)    do_off ;;
    once)   do_once ;;
    status|"") do_status ;;
    *)
        echo "用法: sh usb-sleep.sh {status|on [秒]|off|once}"
        exit 1
        ;;
esac
