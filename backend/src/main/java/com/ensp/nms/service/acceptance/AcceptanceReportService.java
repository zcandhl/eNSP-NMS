package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceBatch;
import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.AcceptanceProject;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import com.ensp.nms.repository.AcceptancePlanRepository;
import com.ensp.nms.repository.AcceptanceProjectRepository;
import com.ensp.nms.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验收报告：三输入装配——方案（清单与准则）+ 批次快照（实测值）+ 输出格式。
 *
 * 输出为真正的 Word（.docx，Apache POI XWPF 生成），按批次实际测试内容装配：
 * 封面 → 验收依据 → 工程概况 → 验收组织 → 测试方法与环境 → 验收项清单 → 分项测试结果 →
 * 问题清单（含整改责任与期限）→ 结论与统计（三级结论）→ 签字栏 → 附录 A。
 * 页眉带受控标识（文件编号/版本），页脚带页码。下载接口路径与前端契约不变。
 *
 * 铁律：报告只读批次快照，同一批次永远同一份报告，生成时不重跑任何测试。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceReportService {

    /** 报告存储：内存（实验室阶段够用；正式移交版本落盘并入库归档） */
    public record ReportEntry(String no, String name, byte[] content, String batchId) {
    }

    /** Word 下载响应类型 */
    public static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String FONT_BODY = "宋体";
    private static final String FONT_HEAD = "微软雅黑";
    private static final String FONT_MONO = "Consolas";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 分项状态 → [中文标签, 着色]：PASS 绿 / FINDING 橙 / FAIL 红 / 其他灰 */
    private static final Map<String, String[]> STATUS_LABEL = Map.of(
            "PASS", new String[]{"通过", "00B050"},
            "FINDING", new String[]{"发现问题（待整改）", "ED7D31"},
            "FAIL", new String[]{"不通过", "C00000"},
            "SKIPPED", new String[]{"已跳过", "808080"},
            "PENDING", new String[]{"待执行", "808080"});

    private final Map<String, ReportEntry> store = new ConcurrentHashMap<>();

    private final AcceptanceBatchRepository batchRepository;
    private final AcceptancePlanRepository planRepository;
    private final AcceptanceProjectRepository projectRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> generate(String batchId) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        // 报告只认完整快照：中断/失败/运行中的批次不出正式报告，避免把半份数据当结论
        if (!"DONE".equals(batch.getStatus())) {
            String label = switch (batch.getStatus()) {
                case "RUNNING" -> "运行中";
                case "FAILED" -> "执行失败";
                case "INTERRUPTED" -> "已中断";
                case "VOIDED" -> "已作废";
                default -> batch.getStatus();
            };
            throw new IllegalArgumentException("批次当前为「" + label + "」，只有已完成的批次才能生成报告");
        }
        AcceptancePlan plan = planRepository.findById(batch.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
        AcceptanceProject project = projectRepository.findById(plan.getProjectId()).orElse(null);
        List<Map<String, Object>> items = parse(batch.getItemsJson());

        String reportId = "R-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "验收报告_" + batch.getNo() + ".docx";
        byte[] content = renderDocx(project, plan, batch, items);
        store.put(reportId, new ReportEntry(batch.getNo(), name, content, batchId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId);
        out.put("no", batch.getNo());
        out.put("name", name);
        out.put("batchId", batchId);
        return out;
    }

    public ReportEntry find(String reportId) {
        return store.get(reportId);
    }

    // ────────────────────────── Word 装配 ──────────────────────────

    /** 包可见便于单元测试：装配并输出 docx 字节流 */
    byte[] renderDocx(AcceptanceProject project, AcceptancePlan plan, AcceptanceBatch batch,
                      List<Map<String, Object>> items) {
        // username → 真实姓名（验收组织与整改责任人展示用）
        Map<String, String> realNames = new LinkedHashMap<>();
        userService.getAllUsers().forEach(u ->
                realNames.put(u.getUsername(), u.getRealName() == null ? u.getUsername() : u.getRealName()));
        try (XWPFDocument doc = new XWPFDocument()) {
            setA4(doc);
            setHeaderFooter(doc, batch.getNo());

            cover(doc, project, plan, batch, items);
            chapterBasis(doc);
            chapterOverview(doc, project);
            chapterOrganization(doc, project, items, realNames);
            chapterMethod(doc);
            chapterChecklist(doc, plan);
            chapterResultSummary(doc, plan, items, realNames);
            chapterFindings(doc, items, batch, realNames);
            chapterConclusion(doc, batch, items);
            chapterSignature(doc);
            appendixRaw(doc, items);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("报告装配失败：" + e.getMessage(), e);
        }
    }

    /** 页眉受控标识（文件编号/版本/出具方）+ 页脚页码，全页生效 */
    private void setHeaderFooter(XWPFDocument doc, String reportNo) {
        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        XWPFParagraph hp = header.getParagraphArray(0) != null ? header.getParagraphArray(0) : header.createParagraph();
        hp.setAlignment(ParagraphAlignment.RIGHT);
        run(hp, "文件编号：" + reportNo + "　·　版本 V1.0　·　eNSP-NMS 验收工作台出具", FONT_BODY, 8, false, "808080");

        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph fp = footer.getParagraphArray(0) != null ? footer.getParagraphArray(0) : footer.createParagraph();
        fp.setAlignment(ParagraphAlignment.CENTER);
        run(fp, "第 ", FONT_BODY, 9, false, "595959");
        CTSimpleField page = fp.getCTP().addNewFldSimple();
        page.setInstr("PAGE \\* MERGEFORMAT");
        CTR pageRun = page.addNewR();
        pageRun.addNewT().setStringValue("1");
        run(fp, " 页", FONT_BODY, 9, false, "595959");
    }

    /** ── 封面：标题 + 结论速览 + 三方元信息 ── */
    private void cover(XWPFDocument doc, AcceptanceProject project, AcceptancePlan plan,
                       AcceptanceBatch batch, List<Map<String, Object>> items) {
        String projectName = project != null ? project.getName() : "网络工程";
        blank(doc, 4);
        para(doc, projectName, FONT_HEAD, 26, true, "1F3864", ParagraphAlignment.CENTER, 0);
        para(doc, "测 试 验 收 报 告", FONT_HEAD, 22, true, "1F3864", ParagraphAlignment.CENTER, 0);
        blank(doc, 2);

        long pass = count(items, "PASS");
        long finding = count(items, "FINDING");
        long fail = count(items, "FAIL") + count(items, "SKIPPED") + count(items, "PENDING");
        String verdict = fail > 0 ? "不通过" : (finding > 0 ? "有条件通过" : "通过");
        para(doc, "验收结论：" + verdict, FONT_HEAD, 16, true, verdictColor(verdict), ParagraphAlignment.CENTER, 0);
        para(doc, "共 " + items.size() + " 项 · 通过 " + pass + " · 待整改 " + finding + " · 未达标 " + fail,
                FONT_BODY, 11, false, "595959", ParagraphAlignment.CENTER, 0);
        blank(doc, 3);

        String[][] meta = {
                {"报告编号", batch.getNo()},
                {"工程名称", project != null ? project.getName() : "-"},
                {"建设单位", project != null ? orDash(project.getOwner()) : "-"},
                {"承建单位", project != null ? orDash(project.getContractor()) : "-"},
                {"验收单位", project != null ? orDash(project.getAcceptanceOrg()) : "-"},
                {"验收方案", plan.getName() + "（v" + orDash(plan.getVersion()) + "）"},
                {"测试批次", batch.getBatchId()},
                {"开始时间", batch.getStartedAt() != null ? TS.format(batch.getStartedAt()) : "-"},
                {"完成时间", batch.getFinishedAt() != null ? TS.format(batch.getFinishedAt()) : "-"},
        };
        XWPFTable t = doc.createTable(meta.length, 2);
        t.setWidth("70%");
        t.setTableAlignment(TableRowAlign.CENTER);
        for (int i = 0; i < meta.length; i++) {
            setCell(t.getRow(i).getCell(0), meta[i][0], FONT_HEAD, 11, true, "333333", 2600);
            setCell(t.getRow(i).getCell(1), meta[i][1], FONT_BODY, 11, false, "000000", 5200);
            shade(t.getRow(i).getCell(0), "F2F2F2");
        }
        blank(doc, 4);
        para(doc, "本报告由 eNSP-NMS 验收工作台按验收方案自动装配，数据取自批次快照，未重新执行任何测试。",
                FONT_BODY, 9, false, "808080", ParagraphAlignment.CENTER, 0);
        doc.createParagraph().setPageBreak(true);
    }

    /** ── 一、验收依据 ── */
    private void chapterBasis(XWPFDocument doc) {
        heading1(doc, "一、验收依据");
        for (String line : new String[]{
                "GB 50312-2016 《综合布线系统工程验收规范》",
                "GB 50339-2013 《智能建筑工程质量验收规范》",
                "GB/T 21671-2018 《基于以太网技术的局域网（LAN）系统验收测评规范》",
                "GB 50311-2016 《综合布线系统工程设计规范》",
                "GB/T 22239-2019 《信息安全技术 网络安全等级保护基本要求》"}) {
            bullet(doc, line);
        }
    }

    /** ── 二、工程概况 ── */
    private void chapterOverview(XWPFDocument doc, AcceptanceProject project) {
        heading1(doc, "二、工程概况");
        if (project == null) {
            para(doc, "（未关联验收工程信息）", FONT_BODY, 11, false, "808080", ParagraphAlignment.LEFT, 0);
            return;
        }
        String[][] rows = {
                {"工程名称", project.getName()},
                {"建筑与规模", orDash(project.getBuilding())},
                {"纳管设备数", project.getDeviceCount() != null ? project.getDeviceCount() + " 台" : "-"},
                {"建设单位", orDash(project.getOwner())},
                {"承建单位", orDash(project.getContractor())},
                {"验收单位", orDash(project.getAcceptanceOrg())},
                {"备注说明", orDash(project.getNote())},
        };
        kvTable(doc, rows, 2600, 6600);
    }

    /** ── 三、验收组织与人员分工（产业验收报告标配章节） ── */
    private void chapterOrganization(XWPFDocument doc, AcceptanceProject project,
                                     List<Map<String, Object>> items, Map<String, String> realNames) {
        heading1(doc, "三、验收组织与人员分工");
        // 测试执行人：本批次各检查项负责人的真实姓名（去重）
        Set<String> executors = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String owner = s(it.get("owner"));
            if (!owner.isBlank()) {
                executors.add(realNames.getOrDefault(owner, owner));
            }
        }
        String executorText = executors.isEmpty() ? "-" : String.join("、", executors);
        String[][] rows = {
                {"建设单位代表", project != null ? orDash(project.getOwner()) : "-",
                        "现场负责人", "组织协调与验收结果确认"},
                {"承建单位负责人", project != null ? orDash(project.getContractor()) : "-",
                        "现场负责人", "工程交付与问题整改落实"},
                {"验收单位代表", project != null ? orDash(project.getAcceptanceOrg()) : "-",
                        "现场负责人", "验收过程监督与结论审定"},
                {"测试执行", "验收工作台（本系统）", executorText, "按验收方案执行采集、判定与记录"},
        };
        String[] head = {"角色", "单位 / 来源", "人员", "主要职责"};
        XWPFTable t = dataTable(doc, head, new int[]{1600, 2300, 2500, 2800}, rows.length);
        for (int i = 0; i < rows.length; i++) {
            setRow(t, i + 1, rows[i], false);
        }
        blank(doc, 1);
        para(doc, "注：人员名单取自系统账号与工程档案，现场签到以签字栏为准。",
                FONT_BODY, 9, false, "808080", ParagraphAlignment.LEFT, 0);
    }

    /** ── 四、测试方法与环境说明（证明方法可信，并向读者交代模拟环境属性） ── */
    private void chapterMethod(XWPFDocument doc) {
        heading1(doc, "四、测试方法与环境说明");
        for (String line : new String[]{
                "采集通道：通过 SSH 逐台登录设备管理地址，执行 display 系列命令采集运行状态与配置，连通性项采用 TCP 探测。",
                "判定方式：各检查项预置判定规则（如「OSPF 满邻居数」不少于「期望邻居数」），采集结果结构化为指标后由引擎自动判定；涉及业务影响的人工确认项，由指定负责人实测后录入结论。",
                "测试环境：在 eNSP 中搭建与工程同构的园区网络仿真环境（拓扑结构与设备型号见系统数字孪生视图）；设备管理平面、SSH 采集通道与判定引擎按真机链路运行，物理介质与仪表测试环节由仿真环境替代。",
                "数据来源：报告数据全部取自批次快照，生成报告时不重新执行任何测试，同批次结果可完整复现。"}) {
            bullet(doc, line);
        }
    }

    /** ── 五、验收项清单（方案准则） ── */
    private void chapterChecklist(XWPFDocument doc, AcceptancePlan plan) {
        heading1(doc, "五、验收项清单与判定准则");
        List<Map<String, Object>> checks = parse(plan.getChecksJson()).stream()
                .filter(c -> !Boolean.FALSE.equals(c.get("enabled"))).toList();
        if (checks.isEmpty()) {
            para(doc, "（方案未配置检查项）", FONT_BODY, 11, false, "808080", ParagraphAlignment.LEFT, 0);
            return;
        }
        String[] head = {"序号", "编号", "检查项", "负责人", "级别", "判定准则"};
        int[] w = {900, 1300, 3300, 1200, 1000, 3300};
        XWPFTable t = dataTable(doc, head, w, checks.size());
        int r = 1;
        for (Map<String, Object> c : checks) {
            setRow(t, r, new String[]{
                    String.valueOf(r), s(c.get("code")), s(c.get("title")), s(c.get("owner")),
                    severityLabel(s(c.get("severity"))), AcceptanceRuleText.describe(c)}, false);
            r++;
        }
    }

    /** ── 六、分项测试结果：判定汇总表 + 逐项判定说明（专业验收版式，命令回显进附录 A） ── */
    private void chapterResultSummary(XWPFDocument doc, AcceptancePlan plan,
                                      List<Map<String, Object>> items, Map<String, String> realNames) {
        heading1(doc, "六、分项测试结果");
        if (items.isEmpty()) {
            para(doc, "（批次快照为空）", FONT_BODY, 11, false, "808080", ParagraphAlignment.LEFT, 0);
            return;
        }
        // 判定准则按检查项编号从方案取（旧批次快照不含准则，也能补全）
        // 判定准则显示一句话中文（与方案设计/工作台同口径），解析不了回退表达式
        Map<String, String> exprByCode = new java.util.HashMap<>();
        for (Map<String, Object> c : parse(plan.getChecksJson())) {
            exprByCode.put(s(c.get("code")), AcceptanceRuleText.describe(c));
        }

        // 6.1 判定汇总表：一表看全部结论
        para(doc, "6.1  分项判定汇总", FONT_HEAD, 12, true, "1F3864", ParagraphAlignment.LEFT, 0);
        String[] head = {"编号", "检查项", "判定准则", "关键实测值", "单项结论"};
        int[] w = {900, 1900, 2800, 2400, 1200};
        XWPFTable t = dataTable(doc, head, w, items.size());
        int r = 1;
        for (Map<String, Object> it : items) {
            String[] label = statusLabel(s(it.get("status")));
            setRow(t, r, new String[]{
                    s(it.get("code")), s(it.get("title")),
                    exprByCode.getOrDefault(s(it.get("code")), "-"),
                    metricsBrief(it.get("metrics")), label[0]}, false);
            XWPFTableCell verdictCell = t.getRow(r).getCell(4);
            shade(verdictCell, tint(label[1]));
            run(verdictCell.getParagraphs().get(0), label[0], FONT_HEAD, 10, true, label[1]);
            r++;
        }

        // 6.2 逐项判定说明：准则 → 实测 → 结论 → 问题，文字化表达，评审可直接引用
        para(doc, "6.2  逐项判定说明", FONT_HEAD, 12, true, "1F3864", ParagraphAlignment.LEFT, 0);
        int idx = 1;
        for (Map<String, Object> it : items) {
            String code = s(it.get("code"));
            String owner = s(it.get("owner"));
            String ownerName = owner.isBlank() ? "-" : realNames.getOrDefault(owner, owner);
            String[] label = statusLabel(s(it.get("status")));
            para(doc, "6.2." + idx + "  " + s(it.get("title")) + "（" + code + "）",
                    FONT_HEAD, 11, true, "333333", ParagraphAlignment.LEFT, 0);
            para(doc, "判定准则：" + exprByCode.getOrDefault(code, "-"), FONT_BODY, 10,
                    false, "333333", ParagraphAlignment.LEFT, 0);
            para(doc, "实测情况：" + orDash(s(it.get("message"))) + "。关键实测值："
                            + metricsBrief(it.get("metrics")) + "。执行耗时 "
                            + (it.get("durationMs") != null ? it.get("durationMs") + " ms" : "-")
                            + "，负责人 " + ownerName + "。",
                    FONT_BODY, 10, false, "333333", ParagraphAlignment.LEFT, 0);
            XWPFParagraph verdict = doc.createParagraph();
            verdict.setIndentationLeft(240);
            run(verdict, "判定结论：" + label[0], FONT_HEAD, 10, true, label[1]);
            Object f = it.get("findings");
            if (f instanceof List<?> list && !list.isEmpty()) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(240);
                        run(p, "问题记录（" + s(m.get("device")) + "，" + severityLabel(s(m.get("severity")))
                                        + "）：" + s(m.get("detail")),
                                FONT_BODY, 10, true, "C00000");
                    }
                }
            }
            idx++;
        }
    }

    /** ── 附录 A：采集原始记录（设备回显备查，正文不放命令输出） ── */
    private void appendixRaw(XWPFDocument doc, List<Map<String, Object>> items) {
        doc.createParagraph().setPageBreak(true);
        heading1(doc, "附录 A  采集原始记录（备查）");
        para(doc, "以下为各检查项采集时的设备回显原文，仅作过程备查与追溯，不作为验收判定依据。",
                FONT_BODY, 10, false, "595959", ParagraphAlignment.LEFT, 0);
        boolean any = false;
        for (Map<String, Object> it : items) {
            String raw = s(it.get("rawOutput"));
            if (raw.isBlank()) {
                continue;
            }
            any = true;
            para(doc, s(it.get("code")) + "  " + s(it.get("title")), FONT_HEAD, 10, true, "333333",
                    ParagraphAlignment.LEFT, 0);
            XWPFTable box = dataTable(doc, new String[0], new int[]{9200}, 1);
            XWPFTableCell cell = box.getRow(0).getCell(0);
            shade(cell, "F7F7F7");
            cell.removeParagraph(0);
            raw.lines().limit(25).forEach(l -> {
                XWPFParagraph p = cell.addParagraph();
                p.setSpacingAfter(0);
                run(p, l.isBlank() ? " " : l, FONT_MONO, 8, false, "333333");
            });
            blank(doc, 1);
        }
        if (!any) {
            para(doc, "（本批次无原始回显记录）", FONT_BODY, 10, false, "808080", ParagraphAlignment.LEFT, 0);
        }
    }

    /** 关键实测值摘要：中文指标名=值（与前端 metricsText 同口径），过滤内部指标，用于汇总表 */
    private String metricsBrief(Object metricsObj) {
        return AcceptanceRuleText.metricsBrief(metricsObj);
    }

    /** ── 七、问题清单（含整改责任人与建议期限，形成整改闭环） ── */
    private void chapterFindings(XWPFDocument doc, List<Map<String, Object>> items,
                                 AcceptanceBatch batch, Map<String, String> realNames) {
        heading1(doc, "七、问题清单");
        String deadline = batch.getFinishedAt() != null ? DAY.format(batch.getFinishedAt().plusDays(7)) : "-";
        List<Object[]> findings = new ArrayList<>();
        Map<String, String> ownerByCode = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            ownerByCode.put(s(it.get("code")), s(it.get("owner")));
            Object f = it.get("findings");
            if (f instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        findings.add(new Object[]{s(it.get("code")), m});
                    }
                }
            }
        }
        if (findings.isEmpty()) {
            para(doc, "本批次未发现问题。", FONT_BODY, 11, false, "00B050", ParagraphAlignment.LEFT, 0);
            return;
        }
        String[] head = {"编号", "所在项", "设备", "问题描述", "等级", "整改责任人", "建议整改期限"};
        int[] w = {850, 1000, 1400, 2850, 800, 1150, 1150};
        XWPFTable t = dataTable(doc, head, w, findings.size());
        int r = 1;
        for (Object[] e : findings) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) e[1];
            String owner = ownerByCode.getOrDefault((String) e[0], "");
            String responsible = owner.isBlank() ? "承建单位" : realNames.getOrDefault(owner, owner);
            setRow(t, r, new String[]{
                    String.format("P-%03d", r), (String) e[0], s(fm.get("device")),
                    s(fm.get("detail")), severityLabel(s(fm.get("severity"))), responsible, deadline}, false);
            // 问题描述红色加粗，提示整改
            run(t.getRow(r).getCell(3).getParagraphs().get(0), s(fm.get("detail")), FONT_BODY, 10, true, "C00000");
            r++;
        }
        blank(doc, 1);
        para(doc, "注：建议整改期限默认为验收完成之日起 7 个自然日，复检通过后方可出具正式通过结论。",
                FONT_BODY, 9, false, "808080", ParagraphAlignment.LEFT, 0);
    }

    /** ── 八、验收结论（三级结论：通过 / 有条件通过 / 不通过） ── */
    private void chapterConclusion(XWPFDocument doc, AcceptanceBatch batch, List<Map<String, Object>> items) {
        heading1(doc, "八、验收结论与统计");
        long pass = count(items, "PASS");
        long finding = count(items, "FINDING");
        long fail = count(items, "FAIL");
        long skip = count(items, "SKIPPED");
        int total = items.size();
        String rate = total == 0 ? "-" : String.format("%.1f%%", pass * 100.0 / total);

        String[][] rows = {
                {"检查项总数", String.valueOf(total)},
                {"通过（PASS）", String.valueOf(pass)},
                {"发现问题（FINDING）", String.valueOf(finding)},
                {"不通过（FAIL）", String.valueOf(fail)},
                {"跳过（SKIPPED）", String.valueOf(skip)},
                {"一次通过率", rate},
        };
        kvTable(doc, rows, 3200, 3000);

        blank(doc, 1);
        String verdict;
        if (fail + skip > 0) {
            verdict = "不通过：存在 " + (fail + skip) + " 项未达标或未完成，承建单位整改完成后须重新组织验收。";
        } else if (finding > 0) {
            verdict = "有条件通过：通过 " + pass + "/" + total + " 项，另有 " + finding
                    + " 项存在待整改问题（整改责任与期限见问题清单），整改完成并复检通过后出具正式通过结论。";
        } else {
            verdict = "通过：" + pass + "/" + total + " 项全部通过，本次验收通过。";
        }
        heading2(doc, "结论意见");
        para(doc, verdict, FONT_HEAD, 12, true, verdictColor(verdict), ParagraphAlignment.LEFT, 0);
        if (batch.getStatusMessage() != null && !batch.getStatusMessage().isBlank()) {
            para(doc, "批次备注：" + batch.getStatusMessage(), FONT_BODY, 10, false, "595959",
                    ParagraphAlignment.LEFT, 0);
        }
    }

    /** ── 九、签字栏 ── */
    private void chapterSignature(XWPFDocument doc) {
        heading1(doc, "九、签字栏");
        String[][] rows = {
                {"建设单位（签章）", "", "日期", ""},
                {"承建单位（签章）", "", "日期", ""},
                {"验收单位（签章）", "", "日期", ""},
        };
        XWPFTable t = dataTable(doc, new String[0], new int[]{2600, 3200, 1300, 2100}, 3);
        for (int i = 0; i < rows.length; i++) {
            setRow(t, i, rows[i], false);
            t.getRow(i).setHeight(600);
        }
        blank(doc, 2);
        para(doc, "（本页无正文，为签字页）", FONT_BODY, 9, false, "808080", ParagraphAlignment.CENTER, 0);
    }

    // ────────────────────────── 排版工具 ──────────────────────────

    /** A4 纵向页 */
    private void setA4(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr() : doc.getDocument().getBody().addNewSectPr();
        CTPageSz sz = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        sz.setW(BigInteger.valueOf(11906));
        sz.setH(BigInteger.valueOf(16838));
        sz.setOrient(STPageOrientation.PORTRAIT);
    }

    private void heading1(XWPFDocument doc, String text) {
        blank(doc, 1);
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.LEFT);
        p.setSpacingBefore(160);
        p.setSpacingAfter(120);
        run(p, text, FONT_HEAD, 15, true, "1F3864");
        p.setBorderBottom(Borders.SINGLE);
    }

    private void heading2(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(120);
        p.setSpacingAfter(80);
        run(p, text, FONT_HEAD, 12, true, "1F3864");
    }

    private void para(XWPFDocument doc, String text, String font, int size, boolean bold,
                      String color, ParagraphAlignment align, int indent) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(align);
        if (indent > 0) {
            p.setFirstLineIndent(indent);
        }
        run(p, text, font, size, bold, color);
    }

    private void bullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(480);
        p.setSpacingAfter(40);
        run(p, "· " + text, FONT_BODY, 11, false, "333333");
    }

    private void blank(XWPFDocument doc, int n) {
        for (int i = 0; i < n; i++) {
            doc.createParagraph().setSpacingAfter(0);
        }
    }

    private void run(XWPFParagraph p, String text, String font, int size, boolean bold, String color) {
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily(font);
        r.setFontSize(size);
        r.setBold(bold);
        r.setColor(color);
    }

    /** 两列键值表：左列灰底表头风格 */
    private XWPFTable kvTable(XWPFDocument doc, String[][] rows, int wKey, int wVal) {
        XWPFTable t = dataTable(doc, new String[0], new int[]{wKey, wVal}, rows.length);
        for (int i = 0; i < rows.length; i++) {
            setRow(t, i, rows[i], false);
            shade(t.getRow(i).getCell(0), "F2F2F2");
            run(t.getRow(i).getCell(0).getParagraphs().get(0), rows[i][0], FONT_HEAD, 10, true, "333333");
        }
        return t;
    }

    /** 带表头的数据表（head 为空则不建表头行） */
    private XWPFTable dataTable(XWPFDocument doc, String[] head, int[] widths, int bodyRows) {
        int rows = head.length + Math.max(bodyRows, 0);
        XWPFTable t = doc.createTable(Math.max(rows, 1), Math.max(widths.length, 1));
        t.setWidth("100%");
        t.setTableAlignment(TableRowAlign.CENTER);
        for (int r = 0; r < t.getRows().size(); r++) {
            for (int c = 0; c < widths.length && c < t.getRow(r).getTableCells().size(); c++) {
                t.getRow(r).getCell(c).setWidth(String.valueOf(widths[c]));
            }
        }
        if (head.length > 0) {
            setRow(t, 0, head, true);
            for (int c = 0; c < head.length; c++) {
                shade(t.getRow(0).getCell(c), "1F3864");
            }
        }
        return t;
    }

    private void setRow(XWPFTable t, int r, String[] vals, boolean isHead) {
        for (int c = 0; c < vals.length && c < t.getRow(r).getTableCells().size(); c++) {
            setCell(t.getRow(r).getCell(c), vals[c],
                    isHead ? FONT_HEAD : FONT_BODY, 10, isHead,
                    isHead ? "FFFFFF" : "333333", 0);
        }
    }

    private void setCell(XWPFTableCell cell, String text, String font, int size, boolean bold,
                         String color, int width) {
        if (width > 0) {
            cell.setWidth(String.valueOf(width));
        }
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setSpacingBefore(40);
        p.setSpacingAfter(40);
        run(p, text == null || text.isBlank() ? "-" : text, font, size, bold, color);
    }

    private void shade(XWPFTableCell cell, String hex) {
        cell.setColor(hex);
    }

    /** 结果行淡色底：绿/橙/红各给浅底色 */
    private String tint(String hex) {
        return switch (hex) {
            case "00B050" -> "EAF6EE";
            case "ED7D31" -> "FDF2E9";
            case "C00000" -> "FDECEC";
            default -> "F2F2F2";
        };
    }

    private String[] statusLabel(String status) {
        String[] v = STATUS_LABEL.get(status);
        return v != null ? v : new String[]{status, "595959"};
    }

    private String severityLabel(String severity) {
        return switch (severity) {
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            default -> severity.isBlank() ? "-" : severity;
        };
    }

    private String verdictColor(String verdict) {
        if (verdict.startsWith("不通过")) {
            return "C00000";
        }
        if (verdict.startsWith("通过")) {
            return "00B050";
        }
        return "ED7D31";
    }

    private String joinMetrics(Map<?, ?> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> {
            if (b.length() > 0) {
                b.append("；");
            }
            b.append(k).append(" = ").append(v);
        });
        return b.length() > 0 ? b.toString() : "-";
    }

    private long count(List<Map<String, Object>> items, String status) {
        return items.stream().filter(i -> status.equals(s(i.get("status")))).count();
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private List<Map<String, Object>> parse(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String s(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public byte[] renderBytes(ReportEntry e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(e.content());
        } catch (Exception ignored) {
            // 内存写入不失败
        }
        return bos.toByteArray();
    }
}
