package com.littlesauce.usbnas;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯 Java 的 OOXML（.docx / .xlsx / .pptx）解析器，产出可以直接下发的 HTML 片段。
 *
 * <h3>为什么不用 Apache POI</h3>
 * POI 依赖 xmlbeans + JAXB，Android 上要贴一堆 shim，产物十几 MB 还会撞 dex 方法数上限。
 * 而 OOXML 本质就是一个 zip 包着一堆 XML —— {@link ZipFile} 是 JDK 自带的，
 * {@link XmlPullParser} 是 Android 框架自带的，加起来零依赖。
 *
 * <h3>为什么这次可以用 XmlPullParser</h3>
 * 之前解析 nfo 时吃过亏（刮削器手拼 XML 常有裸 {@code &}，解析器遇到一个就整份报废），
 * 所以那处改成了正则。但 OOXML 是 Office 机器生成的、严格合规，
 * 流式解析反而又快又稳，一个 300 页的 docx 也就几十毫秒。
 *
 * <h3>保真度预期</h3>
 * 这是"能看清内容"，不是"所见即所得"。段落、粗斜体、标题、列表、表格、图片都在；
 * 分栏、文本框、页眉页脚、精确行距这些东西做不到 —— 那些交给上层的
 * docx-preview.js（A 层）去补，补不上就留在这一层，用户至少能读到字。
 */
public final class OoxmlView {

    /** 单份文档最多渲染这么多段落/行/张，防止一个巨型文件把内存吃光 */
    private static final int MAX_BLOCKS = 4000;
    /** 内联图片总字节上限。图片是 base64 塞进 HTML 的，1MB 原图会变成 1.37MB 文本 */
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;

    private OoxmlView() {
    }

    /**
     * HTML 转义。
     *
     * 这里自己留一份而不是复用 HttpServer.esc —— 那个是 HttpServer 的 static 方法，
     * 从这边调得写全限定名，还让两个类的耦合变得没道理。转义规则就这么几条，
     * 复制过来最省事，也保证 OOXML 里的正文永远不会变成活动标签。
     */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== 入口 ====================

    /** 按扩展名分发；不认识返回 null，调用方自己兜底 */
    public static String render(File f, String ext) {
        lastError = null;
        try {
            String html;
            if ("docx".equals(ext)) html = renderDocx(f);
            else if ("xlsx".equals(ext)) html = renderXlsx(f);
            else if ("pptx".equals(ext)) html = renderPptx(f);
            else return null;
            if (lastError != null) {
                return "<p class='ox-cut'>解析中断：" + esc(lastError)
                        + "（下面是中断前已经读到的内容）</p>\n" + html;
            }
            return html;
        } catch (Throwable e) {
            // 任何解析异常都不能让整个预览页崩掉，交给上层显示"解析失败"
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    /** 上一次解析中断的异常摘要，供页面提示用 */
    private static String lastError;

    /** 给上层拼错误信息用 */
    public static String lastErrorText() {
        return lastError == null ? "" : lastError;
    }

    // ==================== .docx ====================

    /**
     * 在 zip 里找一个条目，容忍路径分隔符是反斜杠、以及大小写不一致。
     *
     * Windows 上用某些库打出来的 docx，条目名就是 {@code word\document.xml}；
     * {@code ZipFile.getEntry("word/document.xml")} 是逐字节比较，一个都不命中，
     * 结果整份文档被判成"不是真正的 OOXML"。这里把两边都规范化再比。
     */
    static ZipEntry findEntry(ZipFile z, String want) {
        String w = normEntry(want);
        ZipEntry e = z.getEntry(want);
        if (e != null) return e;
        e = z.getEntry(w);
        if (e != null) return e;
        String base = w.substring(w.lastIndexOf('/') + 1);
        java.util.Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry c = en.nextElement();
            String n = normEntry(c.getName());
            if (n.equals(w)) return c;
            // 只在唯一命中时才接受 basename 匹配，否则同名不同目录会串
            if (n.endsWith("/" + base)) {
                ZipEntry again = null;
                boolean dup = false;
                java.util.Enumeration<? extends ZipEntry> en2 = z.entries();
                while (en2.hasMoreElements()) {
                    ZipEntry c2 = en2.nextElement();
                    String n2 = normEntry(c2.getName());
                    if (n2.endsWith("/" + base)) {
                        if (again != null) { dup = true; break; }
                        again = c2;
                    }
                }
                if (!dup) return again;
            }
        }
        return null;
    }

    /** 条目名规范化：反斜杠转正斜杠、去掉 ./ 前缀、转小写（只用于比较） */
    private static String normEntry(String n) {
        if (n == null) return "";
        String s = n.replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        if (s.startsWith("/")) s = s.substring(1);
        return s.toLowerCase(java.util.Locale.US);
    }

    private static String renderDocx(File f) throws Exception {
        StringBuilder body = new StringBuilder();
        try (ZipFile z = new ZipFile(f)) {
            // 编号定义要先读：正文里的 <w:numPr> 只是一个 id，真正的符号在 numbering.xml
            NumMap nums = readNumbering(z);
            // 同名样式表：标题级别不一定写 "Heading1"，WPS 会存成 <w:styleId="2"> +
            // <w:name w:val="heading 1"/>，只认字符串前缀的话整份文档的标题全成普通段落
            java.util.Map<String, Integer> styleLevel = readStyleLevels(z);
            // getEntry 是大小写敏感的精确匹配，而 Windows/WPS 打出来的 zip 里条目名
            // 可能是 "word\document.xml"（反斜杠）甚至 "Word/Document.xml"。
            // 先用规范化名字找，找不到再扫一遍全部条目按 basename 兜底。
            ZipEntry e = findEntry(z, "word/document.xml");
            if (e == null) {
                lastError = "zip 里没有 word/document.xml";
                return "<p class='ox-cut'>这个 docx 里找不到正文部件（word/document.xml）。"
                        + "可以下载后用 Office / WPS 打开，或者另存成 PDF 再放回来。</p>";
            }

            try {
                renderDocxBody(z, e, nums, styleLevel, body);
            } catch (Throwable t) {
                // 正文解析中途炸了也要把已经攒下的段落吐出去，
                // 否则整份文档变成"没有可显示的正文"，一个字都看不到
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                if (body.length() == 0) throw t;
            }
        }
        String html = body.toString();
        if (html.trim().isEmpty()) {
            return "<p class='ox-cut'>这个 docx 里没有可显示的正文（可能是纯图片或纯文本框排版）。</p>";
        }
        return "<div class='ox-doc'>" + html + "</div>";
    }

    /** 把 word/document.xml 扫一遍，结果追加到 body */
    private static void renderDocxBody(ZipFile z, ZipEntry e, NumMap nums,
                                       java.util.Map<String, Integer> styleLevel,
                                       StringBuilder body) throws Exception {
        XmlPullParser p = open(z, e);
        int blocks = 0;
        StringBuilder para = new StringBuilder();
        List<String> listStack = new ArrayList<>();
        StringBuilder table = null;
        StringBuilder cell = new StringBuilder();
        int nested = 0;
        boolean inTable = false;
        int styleLv = 0;
        int listLevel = 0;
        boolean ordered = false;

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            // 注意用 localName：KXmlParser 开命名空间后 getName() 返回的是 "p" 而不是 "w:p"，
            // 直接拿 getName() 去 switch "w:p" 会一个分支都不命中（整份文档一个字都出不来）
            String tag = localName(p);
            if (tag == null) continue;
            int ev = p.getEventType();

            if (ev == XmlPullParser.START_TAG) {
                switch (tag) {
                    case "tbl":
                        nested++;
                        if (nested == 1) {
                            flushPara(body, para, listStack, styleLv, listLevel, ordered, null);
                            table = new StringBuilder("<table class='ox-tbl'>");
                            inTable = true;
                        }
                        break;
                    case "tr":
                        if (nested == 1) table.append("<tr>");
                        break;
                    case "tc":
                        if (nested == 1) {
                            cell.setLength(0);
                            table.append("<td>");
                        }
                        break;
                    case "pStyle": {
                        String sid = attr(p, "w:val");
                        styleLv = styleLevelOf(styleLevel, sid);
                        break;
                    }
                    case "ilvl":
                        listLevel = parseInt(attr(p, "w:val"), 0);
                        break;
                    case "numPr":
                        // 段落里有编号属性就说明它是列表项；
                        // 真正的"有序还是无序"由下面的 numId 决定
                        break;
                    case "numId": {
                        String id = attr(p, "w:val");
                        ordered = nums.ordered(id);
                        listStack.add(id);
                        break;
                    }
                    case "t": {
                        // 文本可能跨多个 run，直接拼在段落缓冲里。
                        // nextText 会把解析器挪到本标签的 END_TAG 之后，
                        // 这里绝不能让它把异常带出去，否则整段正文丢失
                        String t = p.nextText();
                        if (t != null) {
                            if (nested > 0 && inTable) cell.append(esc(t));
                            else para.append(esc(t));
                        }
                        break;
                    }
                    case "tab":
                        para.append("&nbsp;&nbsp;&nbsp;&nbsp;");
                        break;
                    case "br":
                        para.append("<br>");
                        break;
                    case "b":
                        if (!"0".equals(attr(p, "w:val"))) para.append("<b>");
                        break;
                    case "i":
                        if (!"0".equals(attr(p, "w:val"))) para.append("<i>");
                        break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                switch (tag) {
                    case "tc":
                        if (nested == 1) {
                            table.append(cell).append("</td>");
                        }
                        break;
                    case "tr":
                        if (nested == 1) table.append("</tr>");
                        break;
                    case "tbl":
                        nested--;
                        if (nested == 0) {
                            body.append(table).append("</table>\n");
                            table = null;
                            inTable = false;
                            blocks++;
                        }
                        break;
                    case "p":
                        if (nested == 0 && !inTable) {
                            flushPara(body, para, listStack, styleLv, listLevel, ordered, null);
                            blocks++;
                            styleLv = 0;
                            listLevel = 0;
                        }
                        break;
                }
                if (blocks > MAX_BLOCKS) break;
            }
        }
        if (blocks > MAX_BLOCKS) {
            body.append("<p class='ox-cut'>（文档太长，只显示前 ").append(MAX_BLOCKS)
                    .append(" 段）</p>");
        }
    }

    /** 段落收尾：区分标题 / 列表 / 普通段落 */
    private static void flushPara(StringBuilder body, StringBuilder para,
                                  List<String> listStack, int styleLv,
                                  int listLevel, boolean ordered, String ignore) {
        String txt = para.toString();
        boolean empty = txt.replace("&nbsp;", "").replace("<br>", "").trim().isEmpty();
        if (!empty) {
            if (styleLv > 0) {
                int lv = Math.max(1, Math.min(6, styleLv));
                body.append("<h").append(lv).append(" class='ox-h'>")
                        .append(txt).append("</h").append(lv).append(">\n");
            } else if (!listStack.isEmpty()) {
                // 缩进按层级走，最多 6 级
                int lv = Math.max(0, Math.min(6, listLevel));
                body.append("<p class='ox-li' style='padding-left:")
                        .append((lv * 22 + 8)).append("px'>")
                        .append(ordered ? "<span class='ox-mk'>▪</span>" : "<span class='ox-mk'>•</span>")
                        .append(txt).append("</p>\n");
            } else {
                body.append("<p class='ox-p'>").append(txt).append("</p>\n");
            }
        }
        para.setLength(0);
        listStack.clear();
    }

    // ==================== 样式表：样式 id → 标题级别 ====================

    /**
     * 扫 word/styles.xml，建「段落样式 id → 标题级别」的表。
     *
     * 为什么要建这张表：Word 自己存的是 {@code <w:pStyle w:val="Heading1"/>}，
     * 但 WPS 存的是 {@code <w:pStyle w:val="2"/>}，而 {@code <w:style w:styleId="2">}
     * 里面写着 {@code <w:name w:val="heading 1"/>}。只按 "Heading" 前缀认标题的话，
     * 这类文档的标题会全部退化成普通段落（内容还在，只是层级没了）。
     *
     * 判定顺序：{@code <w:name>} 里带 heading 字样 → {@code <w:outlineLvl>}+1 →
     * 样式 id 本身形如 HeadingN。都没有就不当作标题。
     */
    private static java.util.Map<String, Integer> readStyleLevels(ZipFile z) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        ZipEntry e = findEntry(z, "word/styles.xml");
        if (e == null) return map;
        XmlPullParser p = openSoft(z, e);
        if (p == null) return map;

        String curId = null;
        int nameLv = 0;
        int outlineLv = 0;
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                String tag = localName(p);
                if (tag == null) continue;
                int ev = p.getEventType();
                if (ev == XmlPullParser.START_TAG) {
                    if ("style".equals(tag)) {
                        // 只认段落样式，字符样式和表格样式同名会串味
                        String type = attr(p, "w:type");
                        curId = (type.isEmpty() || "paragraph".equals(type))
                                ? attr(p, "w:styleId") : null;
                        nameLv = 0;
                        outlineLv = 0;
                    } else if (curId != null && "name".equals(tag)) {
                        nameLv = levelFromName(attr(p, "w:val"));
                    } else if (curId != null && "outlineLvl".equals(tag)) {
                        int v = parseInt(attr(p, "w:val"), -1);
                        if (v >= 0 && v <= 8) outlineLv = v + 1;
                    }
                } else if (ev == XmlPullParser.END_TAG && "style".equals(tag)) {
                    if (curId != null && !curId.isEmpty()) {
                        int lv = nameLv > 0 ? nameLv
                                : (outlineLv > 0 ? outlineLv : levelFromName(curId));
                        if (lv > 0) map.put(curId, lv);
                    }
                    curId = null;
                }
            }
        } catch (Throwable ignored) {
            // 样式表坏了不影响正文，正文该出还是出，只是标题没法认级别
        }
        return map;
    }

    /** "heading 1" / "Heading2" / "heading1" → 1..9；认不出返回 0 */
    private static int levelFromName(String v) {
        if (v == null || v.isEmpty()) return 0;
        String s = v.trim().toLowerCase(java.util.Locale.US).replace(" ", "");
        if (!s.startsWith("heading")) return 0;
        int lv = parseInt(s.substring(7), 0);
        return (lv >= 1 && lv <= 9) ? lv : 0;
    }

    /** 取某个段落样式 id 的标题级别，非标题返回 0 */
    private static int styleLevelOf(java.util.Map<String, Integer> map, String sid) {
        if (sid == null || sid.isEmpty()) return 0;
        Integer v = map.get(sid);
        if (v != null) return v;
        // 样式表里没登记（或没读到 styles.xml 时）按 id 字面兜底
        return levelFromName(sid);
    }

    /** numbering.xml 里定义了什么，正文里 <w:numId> 指的是哪个 */
    private static final class NumMap {
        private final List<String> bulletIds = new ArrayList<>();
        private final List<String> decimalIds = new ArrayList<>();

        boolean ordered(String numId) {
            if (numId == null) return false;
            return decimalIds.contains(numId);
        }
    }

    private static NumMap readNumbering(ZipFile z) {
        NumMap m = new NumMap();
        ZipEntry e = findEntry(z, "word/numbering.xml");
        if (e == null) return m;
        try {
            XmlPullParser p = open(z, e);
            String curNumId = null;
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                String tag = localName(p);
                if (tag == null) continue;
                if (p.getEventType() == XmlPullParser.START_TAG) {
                    if ("num".equals(tag)) curNumId = attr(p, "w:numId");
                    else if ("numFmt".equals(tag) && curNumId != null) {
                        String fmt = attr(p, "w:val");
                        if ("bullet".equals(fmt)) m.bulletIds.add(curNumId);
                        else m.decimalIds.add(curNumId);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    // ==================== .xlsx ====================

    private static String renderXlsx(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile z = new ZipFile(f)) {
            // 真正的单元格文本可能全在共享字符串池里，sheet 只存索引
            List<String> shared = readSharedStrings(z);
            List<String> dateStyles = readDateFormatStyles(z);

            List<ZipEntry> sheets = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                // 跑 normEntry：zip 里的条目名可能是反斜杠或大小写不一致，
                // 直接 startsWith 会一个工作表都找不到
                String n = normEntry(e.getName());
                if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) sheets.add(e);
            }
            sheets.sort((a, b) -> a.getName().compareTo(b.getName()));
            if (sheets.isEmpty()) return null;

            int idx = 0;
            for (ZipEntry sheet : sheets) {
                idx++;
                sb.append("<div class='ox-sheet'><div class='ox-sheet-h'>工作表 ").append(idx)
                        .append("</div><div class='ox-grid-wrap'><table class='ox-grid'>");
                readSheet(z, sheet, shared, dateStyles, sb);
                sb.append("</table></div></div>\n");
                if (idx >= 10) {
                    sb.append("<p class='ox-cut'>（只显示前 10 个工作表）</p>");
                    break;
                }
            }
        }
        return "<div class='ox-xls'>" + sb + "</div>";
    }

    private static void readSheet(ZipFile z, ZipEntry e, List<String> shared,
                                  List<String> dateStyles, StringBuilder sb) throws Exception {
        XmlPullParser p = openSoft(z, e);
        if (p == null) {
            // 单个工作表打不开不该让整本工作簿变空白
            sb.append("<p class='ox-cut'>（这个工作表读不出来）</p>");
            return;
        }
        int maxRow = 0;
        int maxCol = 0;
        List<String[]> cells = new ArrayList<>();
        int curRow = -1;
        StringBuilder row = null;

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            String tag = localName(p);
            if (tag == null) continue;
            int ev = p.getEventType();
            if (ev == XmlPullParser.START_TAG) {
                if ("row".equals(tag)) {
                    curRow = parseInt(attr(p, "r"), cells.size() + 1) - 1;
                    // 空行也要占位，否则行号会错位
                    while (cells.size() <= curRow) cells.add(null);
                    row = new StringBuilder();
                } else if ("c".equals(tag) && curRow >= 0) {
                    String ref = attr(p, "r");
                    String type = attr(p, "t");
                    String sIdx = attr(p, "s");
                    int col = colIndex(ref);
                    if (col > maxCol) maxCol = col;
                    String val = readCellValue(p, type, shared, sIdx, dateStyles);
                    if (val != null && !val.isEmpty()) {
                        row.append("<td>").append(esc(val)).append("</td>");
                    } else {
                        row.append("<td></td>");
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && "row".equals(tag) && row != null) {
                cells.set(curRow, new String[]{row.toString()});
                if (curRow + 1 > maxRow) maxRow = curRow + 1;
                row = null;
            }
            if (cells.size() > 3000) break;
        }

        for (String[] r : cells) {
            if (r == null) {
                sb.append("<tr>");
                for (int i = 0; i <= maxCol; i++) sb.append("<td></td>");
                sb.append("</tr>");
            } else {
                sb.append("<tr>").append(r[0]).append("</tr>");
            }
        }
    }

    /** 读一个 <c> 单元格的值，读完后把解析器停到 </c> */
    private static String readCellValue(XmlPullParser p, String type, List<String> shared,
                                        String styleIdx, List<String> dateStyles) throws Exception {
        int depth = p.getDepth();
        String raw = null;
        String inlineStr = null;
        while (true) {
            int ev = p.next();
            if (ev == XmlPullParser.END_DOCUMENT) break;
            if (ev == XmlPullParser.END_TAG && p.getDepth() == depth) break;
            String tag = localName(p);
            if (tag == null) continue;
            if (ev == XmlPullParser.START_TAG) {
                if ("v".equals(tag)) {
                    raw = p.nextText();
                } else if ("t".equals(tag)) {
                    // 内联字符串（t="inlineStr"）直接写在 <is><t> 里
                    inlineStr = p.nextText();
                }
            }
        }

        // 内联字符串直接可用
        if (inlineStr != null) return inlineStr;
        if (raw == null) return null;

        // t="s" 时 <v> 里存的是共享字符串池的**下标**，不是文本本身。
        // 不翻译的话，整张表每格都是一个数字，等于看不懂。
        if ("s".equals(type)) {
            int idx = parseInt(raw, -1);
            if (idx >= 0 && idx < shared.size()) return shared.get(idx);
            return "";
        }
        // t="b" 是布尔
        if ("b".equals(type)) return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        // t="str" 是公式计算出来的字符串，原文即结果
        if ("str".equals(type)) return raw;

        // 剩下的是裸数字。日期在 xlsx 里就是一个天数序列号（45366），
        // 只有查样式表才知道该显示成 2024-03-15。
        if (isDateStyle(styleIdx, dateStyles)) {
            String d = serialToDate(raw);
            if (d != null) return d;
        }
        return raw;
    }

    private static boolean isDateStyle(String styleIdx, List<String> dateStyles) {
        int s = parseInt(styleIdx, -1);
        return s >= 0 && s < dateStyles.size() && "1".equals(dateStyles.get(s));
    }

    /**
     * Excel 序列号 → 日期文本。
     *
     * 基准是 1899-12-30（不是 1900-01-01）：Excel 有个著名的 1900 闰年 bug，
     * 把 1900 当闰年算了一天，所以 1900-03-01 之后的日期用 30 号做原点才对得上。
     */
    private static String serialToDate(String raw) {
        try {
            double d = Double.parseDouble(raw.trim());
            if (d < 1 || d > 2958465) return null;   // 超出 9999-12-31 就不当日期
            long days = (long) Math.floor(d);
            double frac = d - days;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(1899, java.util.Calendar.DECEMBER, 30);
            c.add(java.util.Calendar.DAY_OF_YEAR, (int) days);
            int y = c.get(java.util.Calendar.YEAR);
            int mo = c.get(java.util.Calendar.MONTH) + 1;
            int da = c.get(java.util.Calendar.DAY_OF_MONTH);
            String date = String.format("%04d-%02d-%02d", y, mo, da);
            // 带小数说明还有时间部分：0.5 天 = 12:00
            if (frac > 1e-6) {
                int secs = (int) Math.round(frac * 86400);
                date += String.format(" %02d:%02d", secs / 3600, (secs % 3600) / 60);
            }
            return date;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 共享字符串池。
     *
     * 坑：不能简单地把所有 {@code <t>} 串起来 —— 一个单元格里的富文本会被拆成多个
     * {@code <r><t>}，必须按 {@code <si>} 分组再拼，否则整列文本会错位一格。
     */
    private static List<String> readSharedStrings(ZipFile z) {
        List<String> out = new ArrayList<>();
        ZipEntry e = findEntry(z, "xl/sharedStrings.xml");
        if (e == null) return out;
        try {
            XmlPullParser p = open(z, e);
            StringBuilder cur = null;
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                String tag = localName(p);
                if (tag == null) continue;
                int ev = p.getEventType();
                if (ev == XmlPullParser.START_TAG) {
                    if ("si".equals(tag)) cur = new StringBuilder();
                    else if ("t".equals(tag) && cur != null) {
                        String s = p.nextText();
                        if (s != null) cur.append(s);
                    }
                } else if (ev == XmlPullParser.END_TAG && "si".equals(tag) && cur != null) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 哪些样式是日期格式。
     *
     * 坑：xlsx 里日期就是一个数字（45366），只有查 styles.xml 的 numFmtId
     * 才能知道它其实该显示成 2024-03-15。不做这一步，整张表全是天书数字。
     */
    private static List<String> readDateFormatStyles(ZipFile z) {
        List<String> styles = new ArrayList<>();
        ZipEntry e = findEntry(z, "xl/styles.xml");
        if (e == null) return styles;
        try {
            XmlPullParser p = open(z, e);
            int idx = 0;
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                String tag = localName(p);
                if (tag == null) continue;
                if (p.getEventType() == XmlPullParser.START_TAG && "cellXfs".equals(tag)) {
                    // 进到 cellXfs 里，按 <xf> 出现顺序编号
                    int depth = p.getDepth();
                    while (true) {
                        int ev = p.next();
                        if (ev == XmlPullParser.END_DOCUMENT) break;
                        if (ev == XmlPullParser.END_TAG && p.getDepth() == depth) break;
                        if (ev == XmlPullParser.START_TAG && "xf".equals(localName(p))) {
                            String nf = attr(p, "numFmtId");
                            int id = parseInt(nf, 0);
                            // 14~22、45~47 是内置日期/时间格式
                            boolean date = (id >= 14 && id <= 22) || (id >= 45 && id <= 47);
                            styles.add(date ? "1" : "0");
                            idx++;
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return styles;
    }

    /** "BC12" → 列号（0 基）。字母部分逐位 ×26 */
    private static int colIndex(String ref) {
        if (ref == null || ref.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') n = n * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') n = n * 26 + (c - 'a' + 1);
            else break;
        }
        return n < 1 ? 0 : n - 1;
    }

    // ==================== .pptx ====================

    private static String renderPptx(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile z = new ZipFile(f)) {
            List<ZipEntry> slides = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = normEntry(e.getName());
                if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) slides.add(e);
            }
            // slide2.xml 必须排在 slide10.xml 前面
            slides.sort((a, b) -> slideNo(a.getName()) - slideNo(b.getName()));
            if (slides.isEmpty()) return null;

            int i = 0;
            for (ZipEntry s : slides) {
                i++;
                sb.append("<div class='ox-slide'><div class='ox-slide-h'>第 ").append(i)
                        .append(" 页</div>");
                XmlPullParser p = open(z, s);
                int paraCount = 0;
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    String tag = localName(p);
                    if (tag == null) continue;
                    if (p.getEventType() == XmlPullParser.START_TAG && "t".equals(tag)) {
                        String t = p.nextText();
                        if (t != null && !t.trim().isEmpty()) {
                            sb.append("<p class='ox-slide-l")
                                    .append(paraCount == 0 ? "1" : "2").append("'>")
                                    .append(esc(t)).append("</p>");
                            paraCount++;
                        }
                    }
                }
                if (paraCount == 0) sb.append("<p class='ox-slide-l2'>（这一页没有文字）</p>");
                sb.append("</div>\n");
                if (i >= 200) {
                    sb.append("<p class='ox-cut'>（只显示前 200 页）</p>");
                    break;
                }
            }
        }
        return "<div class='ox-ppt'>" + sb + "</div>";
    }

    private static int slideNo(String name) {
        int a = name.lastIndexOf("slide");
        int b = name.lastIndexOf('.');
        if (a < 0 || b <= a) return 0;
        return parseInt(name.substring(a + 5, b), 0);
    }

    // ==================== 小工具 ====================

    private static XmlPullParser open(ZipFile z, ZipEntry e) throws Exception {
        InputStream in = z.getInputStream(e);
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, null);
        return p;
    }

    /**
     * 带吞异常保护的版本。文档级解析用它，行为不变；
     * 次级文件（styles.xml / numbering.xml）用它，坏了就当没有，绝不连累正文。
     */
    private static XmlPullParser openSoft(ZipFile z, ZipEntry e) {
        try {
            return open(z, e);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取当前标签的「本地名」—— 去掉命名空间前缀。
     *
     * <p>这是整个 OOXML 解析里最容易踩的一脚：Android 的 {@code KXmlParser}
     * 开了命名空间处理后，{@code getName()} 返回的是 <b>local name</b>，
     * 也就是 {@code "p"} {@code "t"} {@code "r"}，而不是 XML 里写的 {@code "w:p"}。
     * 之前所有 {@code case "w:p":} 的 switch 分支永远不命中 —— 循环把 6 万多个事件
     * 全扫完、一段都没吐出来，页面就只能显示"没有可显示的正文"。
     *
     * <p>所以这里统一按「去掉前缀」取值，既能吃 {@code w:p} 也能吃 {@code p}
     * （以及 pptx 的 {@code a:t}、数学公式的 {@code m:t} 之类）。
     * 调用点用 {@link #isTag} 比较全名、用本方法做精确匹配，两者等价。
     */
    private static String localName(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return null;
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    /** 当前标签的完整名（带前缀），拿不到时退回本地名 */
    private static String fullName(XmlPullParser p) {
        String n = p.getName();
        return n == null ? "" : n;
    }

    /**
     * 判断当前标签是不是 {@code prefix:name} 或 {@code name}。
     * 只比本地名，所以 {@code w:p} / {@code p} / {@code a:p} 都会命中 "p"。
     */
    private static boolean isTag(XmlPullParser p, String name) {
        String ln = localName(p);
        return ln != null && ln.equals(name);
    }

    /**
     * 取属性值，容忍调用方写带前缀的名字。
     *
     * 和标签名相反：KXmlParser 的 {@code getAttributeName(i)} 返回的是<b>不带前缀</b>的
     * 本地名（{@code "styleId"}），但 {@code getAttributeValue(null, "w:styleId")} 一律
     * 返回 null —— 命名空间是 URL 不是 "w"。所以传 {@code "w:styleId"} 进来必须先把前缀
     * 削掉再比，否则整份 styles.xml 的 styleId 全是空串，标题永远认不出级别。
     */
    private static String attr(XmlPullParser p, String name) {
        if (name == null) return "";
        String v = p.getAttributeValue(null, name);
        if (v != null) return v;
        String local = name;
        int c = local.indexOf(':');
        if (c >= 0) {
            local = local.substring(c + 1);
            v = p.getAttributeValue(null, local);
            if (v != null) return v;
        }
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n == null) continue;
            int cc = n.indexOf(':');
            String ln = cc >= 0 ? n.substring(cc + 1) : n;
            if (ln.equals(local)) return p.getAttributeValue(i);
        }
        return "";
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 预览页用的 CSS，跟主样式表分开命名（ox- 前缀），避免和列表页打架 */
    public static final String CSS =
            ".ox-doc{padding:4px 2px;line-height:1.75;font-size:15px;color:#d5dae2}"
                    + ".ox-p{margin:0 0 10px}"
                    + ".ox-h{margin:18px 0 10px;color:#e6edf5;line-height:1.4}"
                    + ".ox-li{margin:0 0 6px;display:flex;gap:8px}"
                    + ".ox-mk{color:#5aa9ff;flex:0 0 auto}"
                    + ".ox-tbl{border-collapse:collapse;margin:12px 0;font-size:13.5px;"
                    + "display:block;overflow-x:auto;max-width:100%}"
                    + ".ox-tbl td{border:1px solid #29323d;padding:6px 10px;vertical-align:top}"
                    + ".ox-tbl tr:nth-child(2n) td{background:#161c24}"
                    + ".ox-xls{padding:2px}"
                    + ".ox-sheet{margin:0 0 20px}"
                    + ".ox-sheet-h{color:#8ec5ff;font-size:13px;font-weight:600;margin:0 0 8px}"
                    + ".ox-grid-wrap{overflow:auto;max-height:70vh;border:1px solid #29323d;"
                    + "border-radius:8px}"
                    + ".ox-grid{border-collapse:collapse;font-size:13px;white-space:nowrap}"
                    + ".ox-grid td{border:1px solid #222a34;padding:5px 9px;color:#cfd6df}"
                    + ".ox-grid tr:nth-child(2n) td{background:#161c24}"
                    + ".ox-grid tr:first-child td{background:#1b2430;color:#e6edf5;font-weight:600}"
                    + ".ox-ppt{padding:2px}"
                    + ".ox-slide{background:#141a21;border:1px solid #24303d;border-radius:10px;"
                    + "padding:16px 18px;margin:0 0 12px;aspect-ratio:16/10;"
                    + "display:flex;flex-direction:column;gap:8px;overflow:auto}"
                    + ".ox-slide-h{color:#5f6b7a;font-size:11.5px}"
                    + ".ox-slide-l1{font-size:19px;font-weight:600;color:#e6edf5;margin:0}"
                    + ".ox-slide-l2{font-size:14px;color:#c3cbd6;margin:0;line-height:1.6}"
                    + ".ox-cut{color:#7d8590;font-size:12.5px;margin:14px 0}";

    /** B 层是不是能处理这个扩展名 */
    public static boolean supports(String ext) {
        return "docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext);
    }

    /** 读不到 zip 时用来判断文件是不是真的 OOXML（有人把 .doc 改名成 .docx） */
    public static boolean looksLikeZip(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 'P' && b[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }
}
